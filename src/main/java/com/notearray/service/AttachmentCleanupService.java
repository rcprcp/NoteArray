package com.notearray.service;

import com.notearray.entity.Note;
import com.notearray.entity.NoteAttachment;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Reclaims attachments whose link was deleted from the note content.
 *
 * <p>Editing a note to remove an image/link used to leave the bytes behind
 * forever: the row only disappeared when its note (or the whole account) was
 * deleted. Now every note save sweeps that note's attachments, marks the ones
 * no note references any more as {@code orphaned_at}, and clears the mark if
 * the reference comes back (undo, cut/paste, re-add). A scheduled job then
 * deletes marked rows once they are older than the grace window, which keeps a
 * transient edit between two auto-saves from destroying a file.
 *
 * <p>References are matched by the attachment URL in the note text, so the same
 * attachment can be linked from several notes: it is only reclaimed when the
 * last of them stops referencing it.
 */
@ApplicationScoped
public class AttachmentCleanupService {

    private static final Logger LOG = Logger.getLogger(AttachmentCleanupService.class);

    /** {@code /api/attachments/<uuid>}, with or without a query string, any URL form. */
    static final Pattern ATTACHMENT_URL = Pattern.compile(
            "/api/attachments/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    /** How long an unreferenced attachment is kept before the purge job removes it. */
    @ConfigProperty(name = "notearray.attachments.orphan-grace", defaultValue = "PT24H")
    Duration orphanGrace;

    /**
     * Attachment ids referenced by a note's content. Form-agnostic on purpose:
     * it matches the URL inside markdown links/images ({@code ![](…)}), HTML
     * anchors/images, {@code ?download=1} links and absolute URLs alike.
     */
    public static Set<UUID> referencedAttachmentIds(String content) {
        Set<UUID> ids = new HashSet<>();
        if (content == null || content.isEmpty()) {
            return ids;
        }
        Matcher m = ATTACHMENT_URL.matcher(content);
        while (m.find()) {
            try {
                ids.add(UUID.fromString(m.group(1).toLowerCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Not a valid UUID — nothing to reference.
            }
        }
        return ids;
    }

    /**
     * Which of the given attachments should be marked orphaned: those the note
     * no longer references and no other note of the owner references either.
     * Pure so the sweep decision is unit-testable.
     */
    static Set<UUID> idsToMarkOrphaned(Set<UUID> attachmentIds,
                                       Set<UUID> referencedInNote,
                                       Predicate<UUID> referencedElsewhere) {
        Set<UUID> orphaned = new HashSet<>();
        for (UUID id : attachmentIds) {
            if (referencedInNote.contains(id) || referencedElsewhere.test(id)) {
                continue;
            }
            orphaned.add(id);
        }
        return orphaned;
    }

    /** True when a note other than {@code noteId} of the same owner still links the attachment. */
    static boolean referencedElsewhere(UUID attachmentId, UUID ownerId, UUID noteId) {
        return firstOtherNoteReferencing(attachmentId, ownerId, noteId) != null;
    }

    /**
     * A note of the same owner, other than {@code noteId}, whose content links the
     * attachment — or null when nothing else references it.
     */
    static Note firstOtherNoteReferencing(UUID attachmentId, UUID ownerId, UUID noteId) {
        List<Note> referrers = Note.getEntityManager()
                .createQuery("SELECT n FROM Note n WHERE n.ownerId = :owner AND n.id <> :noteId"
                        + " AND n.content LIKE :pattern", Note.class)
                .setParameter("owner", ownerId)
                .setParameter("noteId", noteId)
                .setParameter("pattern", "%/api/attachments/" + attachmentId + "%")
                .setMaxResults(1)
                .getResultList();
        return referrers.isEmpty() ? null : referrers.get(0);
    }

    /**
     * Attachments uploaded from a note that is about to be deleted: the ones another
     * note still links are re-homed to that note (so the delete cascade cannot take a
     * file that is still in use), the rest go with the note. Pure so the decision is
     * unit-testable; {@code otherReferrer} returns the note to keep an attachment for,
     * or null when nothing else links it.
     */
    static Map<UUID, UUID> attachmentsToRehome(Set<UUID> uploadedIds, Function<UUID, UUID> otherReferrer) {
        Map<UUID, UUID> rehome = new HashMap<>();
        for (UUID id : uploadedIds) {
            UUID target = otherReferrer.apply(id);
            if (target != null) {
                rehome.put(id, target);
            }
        }
        return rehome;
    }

    /**
     * Attachments the deleted note referenced but that live on another note: when
     * nothing else links them any more, start their grace clock now instead of waiting
     * for the uploader note's next save. Attachments uploaded from the deleted note are
     * excluded — those are re-homed or removed outright.
     */
    static Set<UUID> attachmentsToMarkOrphaned(Set<UUID> referencedFromDeletedNote,
                                               Set<UUID> uploadedFromDeletedNote,
                                               Set<UUID> alreadyOrphaned,
                                               Predicate<UUID> referencedElsewhere) {
        Set<UUID> mark = new HashSet<>();
        for (UUID id : referencedFromDeletedNote) {
            if (uploadedFromDeletedNote.contains(id) || alreadyOrphaned.contains(id)) {
                continue;
            }
            if (!referencedElsewhere.test(id)) {
                mark.add(id);
            }
        }
        return mark;
    }

    /**
     * Settle the attachments around a note deletion. Must run <em>before</em> the note
     * row is deleted: the {@code note_id} cascade would otherwise take every file
     * uploaded from it, including ones another note still links.
     */
    public void prepareNoteDeletion(Note note) {
        if (note == null || note.id == null) {
            return;
        }
        UUID ownerId = note.ownerId;
        UUID noteId = note.id;
        List<NoteAttachment> uploaded = NoteAttachment.getEntityManager()
                .createQuery("SELECT a FROM NoteAttachment a WHERE a.noteId = :noteId", NoteAttachment.class)
                .setParameter("noteId", noteId)
                .getResultList();
        Set<UUID> uploadedIds = new HashSet<>();
        for (NoteAttachment attachment : uploaded) {
            uploadedIds.add(attachment.id);
        }

        Map<UUID, UUID> rehome = attachmentsToRehome(uploadedIds, id -> {
            Note other = firstOtherNoteReferencing(id, ownerId, noteId);
            return other == null ? null : other.id;
        });
        for (NoteAttachment attachment : uploaded) {
            UUID target = rehome.get(attachment.id);
            if (target != null) {
                attachment.noteId = target;   // still linked elsewhere: keep the file
            } else {
                attachment.delete();          // this note was its last user
            }
        }

        Set<UUID> referenced = referencedAttachmentIds(note.content);
        Map<UUID, NoteAttachment> referencedElsewhereHere = new HashMap<>();
        Set<UUID> alreadyOrphaned = new HashSet<>();
        for (UUID id : referenced) {
            if (uploadedIds.contains(id)) {
                continue;
            }
            NoteAttachment attachment = NoteAttachment.findById(id);
            if (attachment == null || !ownerId.equals(attachment.ownerId)) {
                continue;
            }
            referencedElsewhereHere.put(id, attachment);
            if (attachment.orphanedAt != null) {
                alreadyOrphaned.add(id);
            }
        }
        Set<UUID> toMark = attachmentsToMarkOrphaned(referenced, uploadedIds, alreadyOrphaned,
                id -> firstOtherNoteReferencing(id, ownerId, noteId) != null);
        Instant now = Instant.now();
        for (UUID id : toMark) {
            referencedElsewhereHere.get(id).orphanedAt = now;
        }

        // Push the re-homing and removals to the database before the note row goes:
        // the FK cascade only sees the note_id values that are actually stored.
        note.flush();
    }

    /**
     * Mark or unmark this note's attachments after a save. Runs inside the
     * caller's transaction (the note update), so the marks commit with the
     * content they describe.
     */
    public void sweepNote(Note note) {
        if (note == null || note.id == null) {
            return;
        }
        List<NoteAttachment> attachments = NoteAttachment.getEntityManager()
                .createQuery("SELECT a FROM NoteAttachment a WHERE a.noteId = :noteId", NoteAttachment.class)
                .setParameter("noteId", note.id)
                .getResultList();
        if (attachments.isEmpty()) {
            return;
        }
        Set<UUID> referenced = referencedAttachmentIds(note.content);
        Set<UUID> ids = new HashSet<>();
        for (NoteAttachment attachment : attachments) {
            ids.add(attachment.id);
        }
        Set<UUID> toMark = idsToMarkOrphaned(ids, referenced,
                id -> referencedElsewhere(id, note.ownerId, note.id));
        Instant now = Instant.now();
        for (NoteAttachment attachment : attachments) {
            if (toMark.contains(attachment.id)) {
                if (attachment.orphanedAt == null) {
                    attachment.orphanedAt = now;
                }
            } else if (attachment.orphanedAt != null) {
                attachment.orphanedAt = null; // the reference came back
            }
        }
    }

    /** Delete attachments that have been unreferenced for longer than the grace window. */
    @Scheduled(every = "{notearray.attachments.purge-interval:1h}")
    @Transactional
    void purgeOrphanedAttachments() {
        Instant cutoff = Instant.now().minus(orphanGrace);
        long removed = NoteAttachment.delete("orphanedAt is not null and orphanedAt < ?1", cutoff);
        if (removed > 0) {
            LOG.infof("Purged %d unreferenced attachment(s) older than %s", removed, orphanGrace);
        }
    }
}
