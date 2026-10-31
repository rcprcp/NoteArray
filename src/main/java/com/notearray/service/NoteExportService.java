package com.notearray.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notearray.entity.Note;
import com.notearray.entity.NoteAttachment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a portable ZIP export of one user's notes and attachments.
 *
 * <p>Layout of the archive:
 * <pre>
 *   &lt;title&gt;.json
 *   attachments/&lt;attachment-id&gt;-&lt;filename&gt;
 * </pre>
 *
 * <p>Note files sit at the archive root. Duplicate titles get a {@code -2},
 * {@code -3}, … suffix so every entry stays unique without an id prefix.
 *
 * <p>Each note JSON contains:
 * <ul>
 *   <li>{@code title} — the note title;</li>
 *   <li>{@code tags} — an array of the note's space-delimited tags (may be empty);</li>
 *   <li>{@code pinned} — whether the note is pinned;</li>
 *   <li>{@code attachments} — relative paths of the attachments the note links
 *       to, pointing at files in this same archive (may be empty);</li>
 *   <li>{@code content} — the markdown body (may be empty).</li>
 * </ul>
 *
 * <p>The archive is written to a temporary file rather than buffered in memory,
 * so a large account does not need heap space proportional to its attachment
 * storage. Attachment blobs are written one at a time and detached from the
 * persistence context afterwards.
 */
@ApplicationScoped
public class NoteExportService {

    /** Keeps exported names readable while staying well inside filesystem limits. */
    static final int MAX_NAME_SEGMENT_LENGTH = 80;

    @Inject
    ObjectMapper objectMapper;

    /** One note file inside the export archive. */
    public record ExportNote(String title, List<String> tags, boolean pinned,
                             List<String> attachments, String content) {}

    /**
     * Write the user's full export to a temporary ZIP file and return its path.
     * The caller owns the file and must delete it once the response is sent.
     */
    @Transactional
    public Path exportToTempFile(UUID ownerId) throws IOException {
        Path archive = Files.createTempFile("notearray-export-", ".zip");
        boolean complete = false;
        try (OutputStream fileOut = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(
                     new BufferedOutputStream(fileOut), StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            Map<UUID, String> attachmentPaths = writeAttachments(zip, ownerId);
            writeNotes(zip, ownerId, attachmentPaths);
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(archive);
            }
        }
        return archive;
    }

    /** Write every attachment owned by the user; return its archive path by attachment id. */
    private Map<UUID, String> writeAttachments(ZipOutputStream zip, UUID ownerId) throws IOException {
        // Select ids only: loading the whole NoteAttachment list would pull every
        // blob into memory at once. Each entity is loaded (and detached) in turn.
        List<UUID> ids = NoteAttachment.getEntityManager()
                .createQuery("SELECT a.id FROM NoteAttachment a"
                        + " WHERE a.ownerId = :owner ORDER BY a.filename, a.id", UUID.class)
                .setParameter("owner", ownerId)
                .getResultList();
        Map<UUID, String> paths = new LinkedHashMap<>();
        for (UUID id : ids) {
            NoteAttachment attachment = NoteAttachment.findById(id);
            if (attachment == null) {
                continue;
            }
            String path = attachmentEntryName(attachment.id, attachment.filename);
            zip.putNextEntry(new ZipEntry(path));
            if (attachment.data != null && attachment.data.length > 0) {
                zip.write(attachment.data);
            }
            zip.closeEntry();
            paths.put(attachment.id, path);
            // Release the blob so a large account is not held in the persistence
            // context until the whole archive is built.
            NoteAttachment.getEntityManager().detach(attachment);
        }
        return paths;
    }

    /** Write one JSON file per note owned by the user. */
    private void writeNotes(ZipOutputStream zip, UUID ownerId, Map<UUID, String> attachmentPaths)
            throws IOException {
        // Ids only for the same reason as attachments: note bodies can be large,
        // so only the note currently being written stays in memory.
        List<UUID> ids = Note.getEntityManager()
                .createQuery("SELECT n.id FROM Note n"
                        + " WHERE n.ownerId = :owner ORDER BY n.createdAt, n.id", UUID.class)
                .setParameter("owner", ownerId)
                .getResultList();
        Set<String> usedNames = new HashSet<>();
        for (UUID id : ids) {
            Note note = Note.findById(id);
            if (note == null) {
                continue;
            }
            List<String> linked = new ArrayList<>();
            Set<UUID> referenced = AttachmentCleanupService.referencedAttachmentIds(note.content);
            for (UUID attachmentId : referenced) {
                String path = attachmentPaths.get(attachmentId);
                if (path != null) {
                    linked.add(path);
                }
            }
            linked.sort(Comparator.naturalOrder());

            ExportNote exported = new ExportNote(
                    note.title == null ? "" : note.title,
                    tagsOf(note.tags),
                    note.pinned,
                    linked,
                    note.content == null ? "" : note.content);
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(exported);

            zip.putNextEntry(new ZipEntry(uniqueNoteEntryName(note.title, usedNames)));
            zip.write(json);
            zip.closeEntry();
            Note.getEntityManager().detach(note);
        }
    }

    /** Split the stored space-delimited tag string into an ordered list. */
    static List<String> tagsOf(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String tag : tags.trim().split("\\s+")) {
            if (!tag.isEmpty()) {
                result.add(tag);
            }
        }
        return result;
    }

    /** Archive entry for a note: {@code <title>.json} at the archive root. */
    static String noteEntryName(String title) {
        String name = sanitizeName(title, "untitled");
        if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            name = name.substring(0, name.length() - 5);
        }
        return name + ".json";
    }

    /**
     * Note entry name that does not collide with an earlier note: duplicate
     * titles get {@code -2}, {@code -3}, … before the extension. Comparison is
     * case-insensitive so the archive extracts cleanly on any filesystem.
     */
    static String uniqueNoteEntryName(String title, Set<String> usedLowercase) {
        String base = noteEntryName(title);
        String stem = base.substring(0, base.length() - 5);
        String candidate = base;
        int suffix = 2;
        while (!usedLowercase.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = stem + "-" + suffix++ + ".json";
        }
        return candidate;
    }

    /** Archive entry for an attachment: {@code attachments/<uuid>-<filename>}. */
    static String attachmentEntryName(UUID attachmentId, String filename) {
        return "attachments/" + attachmentId + "-" + sanitizeName(filename, "attachment");
    }

    /**
     * Download name for the archive:
     * {@code {user}-{local date}-{HH_mm_ss}-export.zip}. The user name is
     * sanitized so it is safe inside Content-Disposition.
     */
    public static String exportFilename(String userName, Instant now, ZoneId zone) {
        String safeUser = sanitizeName(userName, "user");
        ZoneId effectiveZone = zone != null ? zone : ZoneOffset.UTC;
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HH_mm_ss", Locale.ROOT)
                .withZone(effectiveZone)
                .format(now);
        return safeUser + "-" + timestamp + "-export.zip";
    }

    /**
     * Resolve the browser-supplied IANA time zone (for example
     * {@code America/New_York}); invalid or missing values fall back to UTC.
     */
    public static ZoneId exportZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * Reduce a user-supplied title/filename to a safe path segment: no
     * separators or control characters, no leading/trailing punctuation, and
     * bounded length. Never returns an empty string.
     */
    static String sanitizeName(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String name = raw.trim()
                .replaceAll("[^\\p{L}\\p{N}._-]+", "-")
                .replaceAll("^[-.]+", "")
                .replaceAll("[-.]+$", "");
        if (name.isEmpty()) {
            return fallback;
        }
        if (name.length() > MAX_NAME_SEGMENT_LENGTH) {
            name = name.substring(0, MAX_NAME_SEGMENT_LENGTH).replaceAll("[-.]+$", "");
        }
        return name.isEmpty() ? fallback : name;
    }
}
