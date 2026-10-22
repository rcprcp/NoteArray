package com.notearray.service;

import com.notearray.entity.Note;
import com.notearray.entity.NoteAttachment;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
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
        return Note.count("ownerId = ?1 and id <> ?2 and content like ?3",
                ownerId, noteId, "%/api/attachments/" + attachmentId + "%") > 0;
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
