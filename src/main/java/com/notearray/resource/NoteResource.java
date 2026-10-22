package com.notearray.resource;

import com.notearray.entity.Note;
import com.notearray.entity.NoteAttachment;
import com.notearray.entity.User;
import com.notearray.service.SessionService;
import com.notearray.service.StorageQuota;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.WebApplicationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Path("/api/notes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NoteResource {

    /** Maximum compressed size of an uploaded import archive (Joplin JEX). */
    static final long DEFAULT_MAX_ARCHIVE_BYTES = 50L * 1024 * 1024;      // 50 MB
    /** Default cumulative uncompressed budget for a whole import (anti zip-bomb). */
    static final long DEFAULT_MAX_TOTAL_EXPANSION_BYTES = 256L * 1024 * 1024; // 256 MB
    /** Default maximum number of notes (.md entries) per import. */
    static final int DEFAULT_MAX_IMPORT_ENTRIES = 10_000;
    /** Bounded parallel imports so each buffered archive fits comfortably in heap. */
    private static final int IMPORT_PERMITS = 2;
    private static final Semaphore IMPORT_SEMAPHORE = new Semaphore(IMPORT_PERMITS);
    /** Maximum size of a single imported note body, matching the notes.content column limit. */
    static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024; // 10 MB
    /** Maximum lengths matching the notes table column definitions. */
    static final int MAX_TITLE_LENGTH = 255;
    static final int MAX_TAGS_LENGTH = 10_000;

    private static final Logger LOG = Logger.getLogger(NoteResource.class);

    @Context
    HttpHeaders headers;

    @Inject
    SessionService sessions;

    @Inject
    ObjectMapper objectMapper;

    /** Limits are configurable so prod can raise them for larger accounts. */
    @ConfigProperty(name = "notearray.import.max-archive-bytes",
            defaultValue = "" + DEFAULT_MAX_ARCHIVE_BYTES)
    long maxArchiveBytes;

    @ConfigProperty(name = "notearray.import.max-total-expansion-bytes",
            defaultValue = "" + DEFAULT_MAX_TOTAL_EXPANSION_BYTES)
    long maxTotalExpansionBytes;

    @ConfigProperty(name = "notearray.import.max-entries",
            defaultValue = "" + DEFAULT_MAX_IMPORT_ENTRIES)
    int maxImportEntries;

    /**
     * Cumulative expansion budget for one import request: total uncompressed
     * bytes across every archive entry (notes and metadata alike). A zip bomb
     * can inflate far past the compressed size; a 50 MB archive is useless as
     * a defense on its own, so the abort condition lives on DEcompressed bytes.
     */
    static final class ExpansionBudget {
        private final long maxTotal;
        private long usedTotal;

        ExpansionBudget(long maxTotal) {
            this.maxTotal = maxTotal;
        }

        /** Throws {@link WebApplicationException} (413) when the budget is exhausted. */
        void add(long bytes) {
            usedTotal += Math.max(0L, bytes);
            if (usedTotal > maxTotal) {
                throw importLimitExceeded(
                        "Archive expands beyond the maximum uncompressed import size ("
                                + (maxTotal / (1024 * 1024)) + " MB)");
            }
        }

        long used() {
            return usedTotal;
        }
    }

    /** A Joplin resource blob extracted from the archive's {@code resources/} directory. */
    record JexResource(String extension, byte[] data) {
        /** Identifies the resource file by name: {@code resources/<resourceId>.<ext>}. */
        static String resourceIdOf(String name) {
            String base = name.replaceFirst("^\\./?resources/", "");
            base = base.substring(base.lastIndexOf('/') + 1); // nested path → bare filename
            int dot = base.lastIndexOf('.');
            return dot > 0 ? base.substring(0, dot) : base;
        }

        static String extensionOf(String name) {
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        }
    }

    /** 413 response for an import that breached a configured limit. */
    static WebApplicationException importLimitExceeded(String message) {
        return new WebApplicationException(Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                .entity(message)
                .build());
    }

    /** True when admitting one more note of {@code nextNoteBytes} would pass the plan storage limit. */
    static boolean quotaWouldExceed(long currentUsedBytes, long importedBytes, long nextNoteBytes, long limitBytes) {
        return currentUsedBytes + importedBytes + Math.max(0L, nextNoteBytes) > limitBytes;
    }

    /** Request body. ownerId is ignored; owner is always the authenticated user. */
    public record NoteRequest(String title, String content, String tags, Boolean pinned, Long version) {
        public NoteRequest(String title, String content, String tags, Boolean pinned) {
            this(title, content, tags, pinned, null);
        }
    }

    public record ImportResult(int imported, int errors, int attachments, List<String> messages) {}

    public record StorageUsage(long usedBytes, long limitBytes, long noteCount, boolean overQuota) {}

    /** Get the authenticated user from the session cookie. Returns null if not logged in. */
    private User getAuthenticatedUser() {
        return sessions.resolve(headers.getCookies().get(SessionService.COOKIE_NAME));
    }

    /**
     * Check if caller has permission to access a note.
     * Superusers can access any note; regular users can only access their own.
     */
    private boolean canAccessNote(User caller, Note note) {
        return caller.superuser || note.ownerId.equals(caller.id);
    }

    /**
     * List notes. Superusers see all; regular users see only their own.
     *
     * Optional full-text search:
     *   q              - search text
     *   searchTitles   - search the title (default false)
     *   searchContent  - search the note content (default false)
     *   searchTags     - search the tags as a partial match (default false):
     *                    every tag token containing the query matches, so
     *                    "grq" finds tokens like grq and grq/cobol; notes
     *                    without any tags are included as well
     * If q is provided, at least one of searchTitles / searchContent /
     * searchTags must be true (else 400).
     *
     * Optional filters (all combined with AND):
     *   tags           - repeatable; note must have every listed tag
     *   untagged       - only notes without tags
     *   hasAttachments - only notes with at least one attachment
     *   pinned         - only pinned notes
     *   createdFrom / createdTo - ISO-8601 instants, inclusive range on creation time
     *   updatedFrom / updatedTo - ISO-8601 instants, inclusive range on modification time
     */
    @GET
    @SuppressWarnings("unchecked")
    public Response list(@QueryParam("q") String q,
                         @QueryParam("searchTitles") @jakarta.ws.rs.DefaultValue("false") boolean searchTitles,
                         @QueryParam("searchContent") @jakarta.ws.rs.DefaultValue("false") boolean searchContent,
                         @QueryParam("searchTags") @jakarta.ws.rs.DefaultValue("false") boolean searchTags,
                         @QueryParam("tags") List<String> tags,
                         @QueryParam("tagPrefix") List<String> tagPrefixes,
                         @QueryParam("untagged") @jakarta.ws.rs.DefaultValue("false") boolean untagged,
                         @QueryParam("hasAttachments") @jakarta.ws.rs.DefaultValue("false") boolean hasAttachments,
                         @QueryParam("pinned") @jakarta.ws.rs.DefaultValue("false") boolean pinned,
                         @QueryParam("createdFrom") String createdFrom,
                         @QueryParam("createdTo") String createdTo,
                         @QueryParam("updatedFrom") String updatedFrom,
                         @QueryParam("updatedTo") String updatedTo) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        boolean hasQuery = q != null && !q.isBlank();
        if (hasQuery && !searchTitles && !searchContent && !searchTags) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Select at least one of searchTitles, searchContent or searchTags").build();
        }

        Instant cFrom, cTo, uFrom, uTo;
        try {
            cFrom = parseInstant(createdFrom);
            cTo = parseInstant(createdTo);
            uFrom = parseInstant(updatedFrom);
            uTo = parseInstant(updatedTo);
        } catch (DateTimeParseException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Dates must be ISO-8601 instants, e.g. 2024-01-31T00:00:00Z").build();
        }

        StringBuilder sql = new StringBuilder("SELECT n.* FROM notes n WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        if (hasQuery) {
            sql.append(" AND (");
            StringBuilder or = new StringBuilder();
            if (searchTitles) {
                or.append("n.title_tsv @@ plainto_tsquery('english', :q)");
            }
            if (searchContent) {
                if (!or.isEmpty()) or.append(" OR ");
                or.append("n.content_tsv @@ plainto_tsquery('english', :q)");
            }
            if (searchTags) {
                if (!or.isEmpty()) or.append(" OR ");
                // Partial tag search: every tag token containing the query
                // matches (token = one space-delimited entry, possibly a
                // hierarchical grq/cobol path), so "sup" finds super-duper.
                // Notes with no tags at all are included too — they have no
                // tags to match, so a tag search does not exclude them.
                or.append("((' ' || lower(coalesce(n.tags, '')) || ' ') LIKE :tagq"
                        + " OR btrim(coalesce(n.tags, '')) = '')");
                params.put("tagq", "%" + escapeLike(q) + "%");
            }
            sql.append(or).append(")");
            // Native queries reject setParameter for names absent from the SQL:
            // :q only exists when a full-text predicate was rendered.
            if (searchTitles || searchContent) {
                params.put("q", q.trim());
            }
        }
        if (!user.superuser) {
            sql.append(" AND n.owner_id = :owner");
            params.put("owner", user.id);
        }
        if (untagged) {
            sql.append(" AND btrim(coalesce(n.tags, '')) = ''");
        }
        if (hasAttachments) {
            sql.append(" AND EXISTS (SELECT 1 FROM note_attachments a WHERE a.note_id = n.id)");
        }
        if (pinned) {
            sql.append(" AND n.pinned = true");
        }
        int i = 0;
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null || tag.isBlank()) continue;
                /*
                 * A tag whose value ends with '/' selects a whole folder
                 * subtree: grq/ matches the token grq itself AND every deeper
                 * token grq/…. Plain tags keep the exact-token match.
                 */
                if (tag.trim().endsWith("/")) {
                    // grq/ → match the grq token itself AND every grq/… token.
                    String folderPath = tag.trim().replaceAll("/+$", "");
                    if (!folderPath.isBlank()) {
                        appendTagPrefixCondition(sql, params, escapeLike(folderPath));
                    }
                    continue;
                }
                String name = "tag" + i++;
                sql.append(" AND (' ' || lower(coalesce(n.tags, '')) || ' ') LIKE :").append(name);
                params.put(name, "% " + escapeLike(tag) + " %");
            }
        }
        if (tagPrefixes != null) {
            for (String prefix : tagPrefixes) {
                if (prefix == null || prefix.isBlank()) continue;
                appendTagPrefixCondition(sql, params, escapeLike(prefix));
            }
        }
        if (cFrom != null) { sql.append(" AND n.created_at >= :cFrom"); params.put("cFrom", cFrom); }
        if (cTo != null) { sql.append(" AND n.created_at <= :cTo"); params.put("cTo", cTo); }
        if (uFrom != null) { sql.append(" AND n.updated_at >= :uFrom"); params.put("uFrom", uFrom); }
        if (uTo != null) { sql.append(" AND n.updated_at <= :uTo"); params.put("uTo", uTo); }
        sql.append(" ORDER BY n.created_at DESC");

        Query query = Note.getEntityManager().createNativeQuery(sql.toString(), Note.class);
        params.forEach(query::setParameter);
        List<Note> results = query.getResultList();
        applyAttachmentCounts(results, user);
        return Response.ok(results).build();
    }

    /** Lowercase a tag token and escape the three SQL LIKE wildcards. */
    static String escapeLike(String tag) {
        return tag.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Hierarchical tags: a prefix matches the token itself AND every deeper
     * token {@code Work/…} (one LIKE per alternative, param names derived from
     * the ever-growing map so repeated calls stay unique).
     */
    static void appendTagPrefixCondition(StringBuilder sql, Map<String, Object> params, String escapedPrefix) {
        String exact = "prefix" + params.size();
        String deeper = "prefix" + (params.size() + 1);
        sql.append(" AND ((' ' || lower(coalesce(n.tags, '')) || ' ') LIKE :").append(exact)
                .append(" OR (' ' || lower(coalesce(n.tags, '')) || ' ') LIKE :").append(deeper).append(")");
        params.put(exact, "% " + escapedPrefix + " %");
        params.put(deeper, "% " + escapedPrefix + "/% %");
    }

    /**
     * Fill {@link Note#attachmentCount} for the returned notes in one grouped
     * query, so the list page can show an attachment indicator without loading
     * any blobs. Notes without attachments keep their 0.
     */
    @SuppressWarnings("unchecked")
    private static void applyAttachmentCounts(List<Note> notes, User user) {
        if (notes == null || notes.isEmpty()) {
            return;
        }
        StringBuilder countsSql = new StringBuilder("SELECT a.note_id, count(*) FROM note_attachments a");
        Map<String, Object> countsParams = new HashMap<>();
        if (!user.superuser) {
            countsSql.append(" WHERE a.owner_id = :owner");
            countsParams.put("owner", user.id);
        }
        countsSql.append(" GROUP BY a.note_id");
        Query countsQuery = Note.getEntityManager().createNativeQuery(countsSql.toString());
        countsParams.forEach(countsQuery::setParameter);
        Map<UUID, Note> byId = new HashMap<>();
        for (Note note : notes) {
            byId.put(note.id, note);
        }
        for (Object[] row : (List<Object[]>) countsQuery.getResultList()) {
            Note note = byId.get(row[0]);
            if (note != null) {
                note.attachmentCount = ((Number) row[1]).longValue();
            }
        }
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value.trim());
    }

    /**
     * Return the total storage (note markdown bytes + binary attachment bytes)
     * across all notes owned by the authenticated user.
     */
    @GET
    @Path("/storage")
    public Response storageUsage() {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Object[] row = (Object[]) Note.getEntityManager()
                .createNativeQuery(
                        "SELECT "
                                + "COALESCE((SELECT SUM(OCTET_LENGTH(content)) FROM notes WHERE owner_id = :owner), 0) "
                                + "+ COALESCE((SELECT SUM(byte_size) FROM note_attachments WHERE owner_id = :owner), 0), "
                                + "(SELECT COUNT(*) FROM notes WHERE owner_id = :owner)")
                .setParameter("owner", user.id)
                .getSingleResult();
        long usedBytes = ((Number) row[0]).longValue();
        long noteCount = ((Number) row[1]).longValue();
        long limitBytes = StorageQuota.limitBytesForPlan(user.plan);
        boolean overQuota = usedBytes >= limitBytes;
        return Response.ok(new StorageUsage(usedBytes, limitBytes, noteCount, overQuota)).build();
    }

    @POST
    @Path("/{id}/attachments")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response uploadAttachment(
            @PathParam("id") UUID noteId,
            @QueryParam("filename") String filenameParam,
            InputStream bodyStream) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Note note = Note.findById(noteId);
        if (note == null || !canAccessNote(user, note)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String rawContentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        String resolvedType = AttachmentResource.normalizeContentType(rawContentType, filenameParam);
        if (resolvedType == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Only PNG, JPEG, GIF, WebP images and PDF files are supported")
                    .build();
        }

        byte[] bytes;
        try {
            bytes = bodyStream.readNBytes(StorageQuota.MAX_ATTACHMENT_BYTES + 1);
        } catch (IOException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Failed to read uploaded file")
                    .build();
        }
        if (bytes.length == 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Uploaded file is empty")
                    .build();
        }
        if (bytes.length > StorageQuota.MAX_ATTACHMENT_BYTES) {
            return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                    .entity("Attachment exceeds the maximum size of "
                            + (StorageQuota.MAX_ATTACHMENT_BYTES / (1024 * 1024)) + " MB")
                    .build();
        }

        long currentUsed = calculateTotalStorageBytes(note.ownerId);
        User owner = User.findById(note.ownerId);
        String plan = owner != null ? owner.plan : user.plan;
        Response quotaError = StorageQuota.checkCanCreate(currentUsed, bytes.length, plan);
        if (quotaError != null) {
            return quotaError;
        }

        String cleanName = AttachmentResource.sanitizeFilename(filenameParam, resolvedType);
        NoteAttachment attachment = new NoteAttachment();
        attachment.noteId = note.id;
        attachment.ownerId = note.ownerId;
        attachment.filename = cleanName;
        attachment.contentType = resolvedType;
        attachment.byteSize = bytes.length;
        attachment.data = bytes;
        attachment.persist();

        String url = "/api/attachments/" + attachment.id;
        return Response.created(URI.create(url))
                .entity(new AttachmentResource.AttachmentResponse(
                        attachment.id,
                        attachment.noteId,
                        attachment.filename,
                        attachment.contentType,
                        attachment.byteSize,
                        url))
                .build();
    }

    public static long calculateTotalStorageBytes(UUID ownerId) {
        Number total = (Number) Note.getEntityManager()
                .createNativeQuery(
                        "SELECT "
                                + "COALESCE((SELECT SUM(OCTET_LENGTH(content)) FROM notes WHERE owner_id = :owner), 0) "
                                + "+ COALESCE((SELECT SUM(byte_size) FROM note_attachments WHERE owner_id = :owner), 0)")
                .setParameter("owner", ownerId)
                .getSingleResult();
        return total != null ? total.longValue() : 0L;
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Note note = Note.findById(id);
        if (note == null || !canAccessNote(user, note)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(note).build();
    }

    @POST
    @Transactional
    public Response create(NoteRequest req) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (req == null || req.title() == null || req.content() == null
                || req.title().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("title and content are required").build();
        }
        String lengthError = validateLengths(req.title(), req.content(), req.tags());
        if (lengthError != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(lengthError).build();
        }
        String sanitizedContent = sanitizeContent(req.content());
        long deltaBytes = sanitizedContent.getBytes(StandardCharsets.UTF_8).length;
        Response quotaError = StorageQuota.checkCanCreate(calculateTotalStorageBytes(user.id), deltaBytes, user.plan);
        if (quotaError != null) {
            return quotaError;
        }
        Note note = new Note();
        note.ownerId = user.id;
        note.title = req.title().trim();
        note.content = sanitizedContent;
        note.tags = (req.tags() != null) ? req.tags().trim() : "";
        note.pinned = Boolean.TRUE.equals(req.pinned());
        note.persist();
        return Response.created(URI.create("/api/notes/" + note.id)).entity(note).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") UUID id, NoteRequest req) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Note note = Note.findById(id);
        if (note == null || !canAccessNote(user, note)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (req == null || (req.title() == null && req.content() == null && req.tags() == null
                && req.pinned() == null)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("title, content, tags, or pinned is required").build();
        }
        if (req.title() != null && req.title().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("title must not be blank").build();
        }
        String lengthError = validateLengths(req.title(), req.content(), req.tags());
        if (lengthError != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(lengthError).build();
        }
        Response conflict = checkVersionConflict(note, req);
        if (conflict != null) {
            return conflict;
        }
        if (req.title() != null) {
            note.title = req.title().trim();
        }
        if (req.content() != null) {
            String sanitizedContent = sanitizeContent(req.content());
            long oldBytes = note.content != null ? note.content.getBytes(StandardCharsets.UTF_8).length : 0L;
            long newBytes = sanitizedContent.getBytes(StandardCharsets.UTF_8).length;
            if (newBytes > oldBytes) {
                User owner = User.findById(note.ownerId);
                String plan = owner != null ? owner.plan : user.plan;
                Response quotaError = StorageQuota.checkQuota(
                        calculateTotalStorageBytes(note.ownerId),
                        newBytes - oldBytes,
                        plan);
                if (quotaError != null) {
                    return quotaError;
                }
            }
            note.content = sanitizedContent;
        }
        if (req.tags() != null) {
            note.tags = req.tags().trim();
        }
        if (req.pinned() != null) {
            note.pinned = req.pinned();
        }
        try {
            note.flush();
        } catch (OptimisticLockException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Note was modified in another session; please reload").build();
        } catch (PersistenceException e) {
            if (e.getCause() instanceof OptimisticLockException) {
                return Response.status(Response.Status.CONFLICT)
                        .entity("Note was modified in another session; please reload").build();
            }
            throw e;
        }
        return Response.ok(note).build();
    }

    /**
     * Returns a 409 Conflict response when the caller supplied an expected
     * {@code version} that does not match the persisted note's current version,
     * or {@code null} when there is no conflict.
     */
    static Response checkVersionConflict(Note note, NoteRequest req) {
        if (req != null && req.version() != null && req.version().longValue() != note.version) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Note was modified in another session; please reload").build();
        }
        return null;
    }

    /** Null if the request fields fit the notes table column limits, else a 400 message. */
    private static String validateLengths(String title, String content, String tags) {
        if (title != null && title.trim().length() > MAX_TITLE_LENGTH) {
            return "title must be at most " + MAX_TITLE_LENGTH + " characters";
        }
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            return "content must be at most " + (MAX_CONTENT_LENGTH / (1024 * 1024)) + " MB";
        }
        if (tags != null && tags.trim().length() > MAX_TAGS_LENGTH) {
            return "tags must be at most " + MAX_TAGS_LENGTH + " characters";
        }
        return null;
    }

    /** Truncate without splitting a UTF-16 surrogate pair (avoids lone surrogates in JSON). */
    private static String truncateTitle(String title) {
        if (title.length() <= MAX_TITLE_LENGTH) {
            return title;
        }
        int end = MAX_TITLE_LENGTH;
        if (Character.isLowSurrogate(title.charAt(end))) {
            end--;
        }
        return title.substring(0, end);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") UUID id) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Note note = Note.findById(id);
        if (note == null || !canAccessNote(user, note)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        note.delete();
        return Response.noContent().build();
    }

    /**
     * Import a Joplin JEX file (supports both ZIP and TAR archives).
     * Flattens folder hierarchy into space-delimited tags.
     *
     * <p>Resource bounds / anti zip-bombing (REVIEW finding #5):
     * <ul>
     *   <li>the compressed upload is capped at {@code notearray.import.max-archive-bytes}
     *       (default 50 MB);</li>
     *   <li>the <b>cumulative uncompressed</b> size of all entries is capped
     *       ({@code notearray.import.max-total-expansion-bytes}, default 256 MB) — the
     *       abort condition lives on decompressed bytes, where bombs actually expand;</li>
     *   <li>the number of imported notes is capped ({@code notearray.import.max-entries},
     *       default 10,000);</li>
     *   <li>at most 2 imports run in parallel, additional requests get 503 + Retry-After;</li>
     *   <li>plan storage quota is enforced per imported note; on exceed the whole
     *       import transaction rolls back (413), so an import can fill the plan's
     *       storage exactly but never overshoot it.</li>
     * </ul>
     */
    @POST
    @Path("/import/joplin")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Transactional
    public Response importJoplin(InputStream jexStream) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        long currentUsed = calculateTotalStorageBytes(user.id);
        Response initialQuotaError = StorageQuota.checkCanCreate(currentUsed, 0L, user.plan);
        if (initialQuotaError != null) {
            return initialQuotaError;
        }

        // Bound concurrent imports: each request buffers its archive on the heap,
        // so parallel unbounded imports are a memory-DoS vector.
        if (!IMPORT_SEMAPHORE.tryAcquire()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "60")
                    .entity("A note import is already running; try again in a minute").build();
        }

        try {
            List<String> messages = new ArrayList<>();
            int imported = 0;
            int errors = 0;

            // Read at most maxArchiveBytes + 1 so an oversized upload can be detected
            // without buffering more than the limit.
            int readLimit = (int) Math.min(maxArchiveBytes, (long) Integer.MAX_VALUE - 2);
            byte[] data;
            try {
                data = jexStream.readNBytes(readLimit + 1);
            } catch (IOException e) {
                LOG.warn("Failed to read JEX upload", e);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("Failed to read JEX file").build();
            }
            if (data.length > maxArchiveBytes) {
                return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                        .entity("JEX file exceeds the maximum import size of "
                                + (maxArchiveBytes / (1024 * 1024)) + " MB").build();
            }

            Map<String, String> folderMap = new HashMap<>();
            Map<String, String> folderParentMap = new HashMap<>();
            Map<String, String> noteMap = new HashMap<>();

            boolean isZip = data.length > 3 && data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04;
            boolean isTar = data.length > 262 && (new String(data, 257, 5, StandardCharsets.US_ASCII)).equals("ustar");

            // Cumulative uncompressed budget across every entry (notes + metadata).
            ExpansionBudget budget = new ExpansionBudget(maxTotalExpansionBytes);
            long planLimitBytes = StorageQuota.limitBytesForPlan(user.plan);
            // Shown in the overrun message so it reflects the caller's actual plan (100 MB / 1 GB / 5 GB).
            String planLimitLabel = StorageQuota.limitLabelForPlan(user.plan);
            long importedBytes = 0;
            int noteEntries = 0;
            int attachmentsImported = 0;
            int skippedItems = 0;

            try {
                if (isZip) {
                    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
                        ZipEntry entry;
                        byte[] buffer = new byte[8192];
                        while ((entry = zis.getNextEntry()) != null) {
                            if (entry.getName().endsWith(".md")) {
                                continue;
                            }
                            if (!entry.getName().endsWith(".json")) {
                                continue;
                            }
                            try {
                                JsonNode node = objectMapper.readTree(readZipEntryAsString(zis, buffer, budget));
                                String type = node.has("type_") ? node.get("type_").asText() : "";
                                String id = node.has("id") ? node.get("id").asText() : "";
                                if (!id.isEmpty() && JOPLIN_TYPE_FOLDER.equals(type)) {
                                    String title = node.has("title") ? node.get("title").asText() : "Untitled";
                                    folderMap.put(id, title);
                                    String folderParent = node.has("parent_id") ? node.get("parent_id").asText() : "";
                                    if (!folderParent.isEmpty()) {
                                        folderParentMap.put(id, folderParent);
                                    }
                                } else if (!id.isEmpty() && (JOPLIN_TYPE_NOTE.equals(type) || JOPLIN_TYPE_TODO.equals(type))) {
                                    String parentId = node.has("parent_id") ? node.get("parent_id").asText() : "";
                                    if (!parentId.isEmpty()) {
                                        noteMap.put(id, parentId);
                                    }
                                }
                            } catch (WebApplicationException we) {
                                throw we;
                            } catch (Exception e) {
                                LOG.debugf(e, "Could not parse Joplin metadata entry %s", entry.getName());
                                messages.add("Warning: Could not parse metadata file");
                            }
                        }
                    }

                    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
                        ZipEntry entry;
                        byte[] buffer = new byte[8192];
                        while ((entry = zis.getNextEntry()) != null) {
                            if (!entry.getName().endsWith(".md")) {
                                continue;
                            }
                            if (++noteEntries > maxImportEntries) {
                                throw importLimitExceeded(
                                        "Archive contains too many notes (limit "
                                                + maxImportEntries + "); aborting the entire import");
                            }
                            try {
                                String content = readZipEntryAsString(zis, buffer, budget);
                                String filename = entry.getName();
                                int dot = filename.lastIndexOf('.');
                                if (dot < 0) {
                                    continue;
                                }
                                String noteId = filename.substring(0, dot);
                                List<String> folderTags = new ArrayList<>();
                                String folderId = noteMap.get(noteId);
                                if (folderId != null) {
                                    String chain = folderPathTag(folderId, folderMap, folderParentMap);
                                    if (chain != null) {
                                        folderTags.add(chain);
                                    }
                                }
                                Note note = buildJoplinNote(user, noteId, content, folderTags);
                                long noteBytes = note.content == null ? 0L
                                        : note.content.getBytes(StandardCharsets.UTF_8).length;
                                if (quotaWouldExceed(currentUsed, importedBytes, noteBytes, planLimitBytes)) {
                                    // Unchecked → the @Transactional import rolls back entirely.
                                    throw importLimitExceeded(
                                            "This import would exceed your plan storage limit (" + planLimitLabel
                                                    + "). Please upgrade your plan or delete notes to free up space.");
                                }
                                note.persist();
                                importedBytes += noteBytes;
                                imported++;
                            } catch (WebApplicationException we) {
                                throw we;
                            } catch (Exception e) {
                                errors++;
                                LOG.debugf(e, "Failed to import Joplin ZIP entry %s", entry.getName());
                                messages.add("Error importing note: could not read entry");
                            }
                        }
                    }
                } else {
                    /*
                     * JEX (TAR) layout: <noteId>.md entries at the root plus a
                     * resources/ directory holding every attachment, stored as
                     * <resourceId>.<extension>. Notes reference them with
                     * inline links like [gpu-optimizer.pdf](:/234939b91e…).
                     * Importing those attachments means three things:
                     *   1. extract the blobs (pass 1 below, charged to the same
                     *      expansion budget as everything else),
                     *   2. create a NoteAttachment row per referenced resource,
                     *      named after the link text,
                     *   3. rewrite the note's links to /api/attachments/{id}
                     *      so they keep working after the migration.
                     *
                     * The same index pass also catalogs Joplin item metadata:
                     * every .md is an item (note type_ 1, todo type_ 3, folder
                     * type_ 2, resource stub type_ 4) and the folders supply
                     * the hierarchy notes are filed under. Only real notes (and
                     * todos) make it to pass 2 — folders and resource stubs are
                     * org data, not content to import. Hand-built archives of
                     * bare .md files (no metadata footer anywhere) keep the
                     * legacy all-notes behavior.
                     */
                    Map<String, JexResource> resources = new HashMap<>();
                    boolean joplinArchive = false;
                    try (TarArchiveInputStream tis = new TarArchiveInputStream(new ByteArrayInputStream(data))) {
                        TarArchiveEntry entry;
                        byte[] buffer = new byte[8192];
                        while ((entry = tis.getNextTarEntry()) != null) {
                            if (entry.isDirectory()) {
                                continue;
                            }
                            String name = entry.getName().replaceFirst("^\\./", "");
                            if (name.startsWith("resources/")) {
                                try {
                                    byte[] blob = readResourceEntry(tis, buffer, budget);
                                    resources.put(JexResource.resourceIdOf(name),
                                            new JexResource(JexResource.extensionOf(name), blob));
                                } catch (IOException oversized) {
                                    // Per-blob cap (10 MB): skip it and let the note pass
                                    // report the dangling link later.
                                    LOG.debugf(oversized, "Resource %s exceeds the per-attachment limit", name);
                                    messages.add("Warning: resource '" + name
                                            + "' exceeds the " + (StorageQuota.MAX_ATTACHMENT_BYTES / (1024 * 1024))
                                            + " MB per-attachment limit and was skipped");
                                }
                                continue;
                            }
                            if (!name.endsWith(".md")) {
                                continue;
                            }
                            try {
                                String itemContent = readTarEntryAsString(tis, buffer, budget);
                                JoplinItemMeta meta = joplinItemMeta(itemContent);
                                if (meta == null) {
                                    continue; // bare markdown; decides nothing on its own
                                }
                                joplinArchive = true;
                                if (JOPLIN_TYPE_FOLDER.equals(meta.type())) {
                                    folderMap.put(meta.id(), firstContentLine(itemContent));
                                    if (!meta.parentId().isEmpty()) {
                                        folderParentMap.put(meta.id(), meta.parentId());
                                    }
                                } else if (JOPLIN_TYPE_NOTE.equals(meta.type())
                                        || JOPLIN_TYPE_TODO.equals(meta.type())) {
                                    noteMap.put(meta.id(), meta.parentId());
                                }
                            } catch (IOException oversized) {
                                throw oversized; // budget aborts bubble up as 413
                            } catch (Exception e) {
                                LOG.debugf(e, "Could not parse Joplin item entry %s", name);
                                messages.add("Warning: Could not parse metadata file");
                            }
                        }
                    }

                    try (TarArchiveInputStream tis = new TarArchiveInputStream(new ByteArrayInputStream(data))) {
                        TarArchiveEntry entry;
                        byte[] buffer = new byte[8192];
                        while ((entry = tis.getNextTarEntry()) != null) {
                            if (entry.isDirectory()) {
                                continue;
                            }
                            String name = entry.getName();
                            if (!name.endsWith(".md")) {
                                continue;
                            }
                            String basename = name.replaceFirst("^.*/", "");
                            String itemKey = basename.lastIndexOf('.') > 0
                                    ? basename.substring(0, basename.lastIndexOf('.'))
                                    : basename;
                            if (joplinArchive && !noteMap.containsKey(itemKey)) {
                                // Folders (type_ 2), resource stubs (type_ 4), tag/master-key
                                // items (type_ 5+) are organizational — not content to import.
                                skippedItems++;
                                continue;
                            }
                            if (++noteEntries > maxImportEntries) {
                                throw importLimitExceeded(
                                        "Archive contains too many notes (limit "
                                                + maxImportEntries + "); aborting the entire import");
                            }
                            try {
                                String content = readTarEntryAsString(tis, buffer, budget);
                                if (basename.isEmpty()) {
                                    continue;
                                }
                                int dot = basename.lastIndexOf('.');
                                if (dot < 0) {
                                    continue;
                                }
                                String noteId = basename.substring(0, dot);
                                List<String> folderTags = new ArrayList<>();
                                String folderId = noteMap.get(noteId);
                                if (folderId != null) {
                                    String chain = folderPathTag(folderId, folderMap, folderParentMap);
                                    if (chain != null) {
                                        folderTags.add(chain);
                                    }
                                }
                                Note note = buildJoplinNote(user, noteId, content, folderTags);
                                String noteContent = note.content;
                                List<NoteAttachment> prepared = new ArrayList<>();
                                Map<String, NoteAttachment> byResourceId = new HashMap<>();
                                List<String> attachmentMessages = attachJoplinResources(
                                        note, resources, prepared, byResourceId);
                                long attachmentBytes = prepared.stream()
                                        .mapToLong(a -> a.byteSize)
                                        .sum();
                                long noteBytes = (noteContent == null ? 0L
                                        : noteContent.getBytes(StandardCharsets.UTF_8).length)
                                        + attachmentBytes;
                                if (quotaWouldExceed(currentUsed, importedBytes, noteBytes, planLimitBytes)) {
                                    throw importLimitExceeded(
                                            "This import would exceed your plan storage limit (" + planLimitLabel
                                                    + "). Please upgrade your plan or delete notes to free up space.");
                                }
                                // Persist order matters for the note_id FK and link ids:
                                // note row first, then attachments (their UUIDs are only
                                // assigned at flush, and noteId is only known once the
                                // note row has been written), then the rewritten content.
                                note.persist();
                                note.flush();
                                for (NoteAttachment attachment : prepared) {
                                    attachment.noteId = note.id;
                                    attachment.persist();
                                }
                                note.flush(); // flushes new attachments, assigning their ids
                                note.content = rewriteJoplinResourceLinks(noteContent, byResourceId);
                                note.flush();
                                importedBytes += noteBytes;
                                attachmentsImported += prepared.size();
                                imported++;
                                if (!attachmentMessages.isEmpty()) {
                                    messages.addAll(attachmentMessages);
                                }
                            } catch (WebApplicationException we) {
                                throw we;
                            } catch (Exception e) {
                                errors++;
                                LOG.debugf(e, "Failed to import Joplin TAR entry %s", name);
                                messages.add("Error importing note: could not read entry");
                            }
                        }
                    }
                }
            } catch (WebApplicationException we) {
                // Unchecked → @Transactional rolls the whole import back.
                throw we;
            } catch (Exception e) {
                LOG.warn("Failed to process JEX file", e);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("Failed to process JEX file").build();
            }

            if (skippedItems > 0) {
                messages.add("Info: skipped " + skippedItems
                        + " archive item(s) that are folder/attachment metadata, not note content");
            }

            // Deduplicate messages so a 10,000-entry failure doesn't flood the UI.
            List<String> distinctMessages = new ArrayList<>(new LinkedHashSet<>(messages));
            return Response.ok(new ImportResult(imported, errors, attachmentsImported, distinctMessages)).build();
        } finally {
            IMPORT_SEMAPHORE.release();
        }
    }
    /**
     * Import a single Markdown file as a new note.
     */
    @POST
    @Path("/import/markdown")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Transactional
    public Response importMarkdown(InputStream mdStream) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        try {
            byte[] bytes = mdStream.readNBytes(MAX_CONTENT_LENGTH + 1);
            if (bytes.length > MAX_CONTENT_LENGTH) {
                return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                        .entity("Markdown file exceeds the maximum size of "
                                + (MAX_CONTENT_LENGTH / (1024 * 1024)) + " MB").build();
            }
            String content = stripBom(new String(bytes, StandardCharsets.UTF_8));
            String title = "Untitled";
            if (!content.isEmpty()) {
                String[] lines = content.split("\n", 2);
                if (lines[0].startsWith("#")) {
                    title = lines[0].replaceAll("^#+\\s*", "").trim();
                } else if (!lines[0].isEmpty()) {
                    title = lines[0].trim();
                }
            }
            String sanitizedContent = sanitizeContent(content);
            long deltaBytes = sanitizedContent.getBytes(StandardCharsets.UTF_8).length;
            Response quotaError = StorageQuota.checkCanCreate(calculateTotalStorageBytes(user.id), deltaBytes, user.plan);
            if (quotaError != null) {
                return quotaError;
            }
            Note note = new Note();
            note.ownerId = user.id;
            note.title = title.isEmpty() ? "Untitled" : truncateTitle(title);
            note.content = sanitizedContent;
            note.tags = "markdown-import";
            note.persist();
            return Response.ok(note).build();
        } catch (Exception e) {
            LOG.warn("Failed to process markdown upload", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Failed to process markdown file").build();
        }
    }

    /**
     * Read the current archive entry as UTF-8. Decoding happens once, after the
     * whole entry is read, so multi-byte characters straddling the 8 KB buffer
     * boundary are not corrupted. A leading UTF-8 BOM is dropped so heading
     * detection ("# Title") is not defeated by it. Entries larger than the note
     * content limit are rejected, and every byte read is charged to the import's
     * cumulative {@link ExpansionBudget} (which aborts the whole import with 413
     * when decompressed data exceeds the configured total).
     */
    private String readEntryAsString(InputStream entryStream, byte[] buffer, ExpansionBudget budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int len;
        while ((len = entryStream.read(buffer)) > 0) {
            if (out.size() + len > MAX_CONTENT_LENGTH) {
                throw new IOException("archive entry exceeds "
                        + (MAX_CONTENT_LENGTH / (1024 * 1024)) + " MB");
            }
            budget.add(len);
            out.write(buffer, 0, len);
        }
        return stripBom(out.toString(StandardCharsets.UTF_8));
    }

    /** Drop a leading UTF-8 BOM, which would otherwise defeat "# heading" detection. */
    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    /**
     * Strip dangerous HTML elements, inline event-handler attributes, and
     * executable URI schemes ({@code javascript:}, {@code vbscript:},
     * {@code data:text/html}) from note content on ingest.
     */
    public static String sanitizeContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String sanitized = content;
        // Strip <script>, <iframe>, <object>, <embed> blocks and self-closing tags
        sanitized = sanitized.replaceAll("(?is)<(script|iframe|object|embed)\\b[^>]*>.*?</\\1\\s*>", "");
        sanitized = sanitized.replaceAll("(?is)<(script|iframe|object|embed)\\b[^>]*/?>", "");
        // Strip inline event handlers inside HTML tags (e.g. onerror=..., onclick=...)
        sanitized = sanitized.replaceAll("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        // Neutralize javascript:, vbscript:, and data:text/html URIs in href/src attributes and markdown links
        sanitized = sanitized.replaceAll("(?i)(href|src)\\s*=\\s*([\"']?)\\s*(javascript|vbscript|data\\s*:\\s*text/html)\\s*:[^\"'\\s>]*\\2", "$1=$2#$2");
        sanitized = sanitized.replaceAll("(?i)\\]\\(\\s*(javascript|vbscript|data\\s*:\\s*text/html)\\s*:[^)]*\\)", "](#)");
        return sanitized;
    }

    /**
     * Read a binary archive entry (a Joplin resource/attachment) fully, charging
     * every byte to the import's {@link ExpansionBudget}. Throws IOException when the
     * entry exceeds the per-attachment limit, which surfaces as a per-note error (not
     * an import abort).
     */
    private byte[] readResourceEntry(InputStream entryStream, byte[] buffer, ExpansionBudget budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int len;
        while ((len = entryStream.read(buffer)) > 0) {
            if (out.size() + len > StorageQuota.MAX_ATTACHMENT_BYTES) {
                throw new IOException("archive entry exceeds "
                        + (StorageQuota.MAX_ATTACHMENT_BYTES / (1024 * 1024)) + " MB");
            }
            budget.add(len);
            out.write(buffer, 0, len);
        }
        return out.toByteArray();
    }

    private String readZipEntryAsString(ZipInputStream zis, byte[] buffer, ExpansionBudget budget) throws IOException {
        return readEntryAsString(zis, buffer, budget);
    }

    private String readTarEntryAsString(TarArchiveInputStream tis, byte[] buffer, ExpansionBudget budget) throws IOException {
        return readEntryAsString(tis, buffer, budget);
    }

    /**
     * Build a note from one Joplin markdown entry: strips the metadata footer,
     * derives the title (first heading, else first line, else the note id) and
     * combines the caller's folder tags with any tags found in the metadata
     * footer. Falls back to the "joplin-import" tag when no tags apply.
     */
    private Note buildJoplinNote(User user, String noteId, String content, List<String> folderTags) {
        String metaTags = extractJoplinTags(content);
        content = stripJoplinMetadata(content);

        String title = noteId;
        if (!content.isEmpty()) {
            String[] lines = content.split("\n", 2);
            if (lines[0].startsWith("#")) {
                title = lines[0].replaceAll("^#+\\s*", "").trim();
            } else if (!lines[0].isEmpty()) {
                String first = lines[0].trim();
                if (!first.equals(noteId)) {
                    title = first;
                }
            }
        }

        Note note = new Note();
        note.ownerId = user.id;
        note.title = title.isEmpty() ? "Untitled" : truncateTitle(title);
        note.content = sanitizeContent(content);

        note.tags = buildJoplinTags(
                folderTags != null && !folderTags.isEmpty() ? String.join(" ", folderTags) : null,
                metaTags);
        return note;
    }

    /**
     * Index of the first line of the trailing Joplin "key: value" metadata block,
     * or -1 if no such block is present. The block must be contiguous to the end
     * and contain both "id:" and "type_:" keys.
     */
    private static int joplinMetadataStart(String[] lines) {
        int i = lines.length - 1;
        while (i >= 0 && lines[i].isBlank()) {
            i--;
        }
        while (i >= 0 && lines[i].matches("^\\s*[A-Za-z_][A-Za-z0-9_]*\\s*:.*")) {
            i--;
        }
        int blockStart = i + 1;
        if (blockStart >= lines.length) {
            return -1;
        }
        boolean hasId = false;
        boolean hasType = false;
        for (int j = blockStart; j < lines.length; j++) {
            if (lines[j].matches("^\\s*id\\s*:.*")) {
                hasId = true;
            } else if (lines[j].matches("^\\s*type_\\s*:.*")) {
                hasType = true;
            }
        }
        return (hasId && hasType) ? blockStart : -1;
    }

    /**
     * Joplin exports append a block of "key: value" metadata to the end of each
     * Markdown file (starting with "id:"). Strip that trailing block so the note
     * body only contains the user's content. The block is only removed when it
     * is contiguous to the end and contains both "id:" and "type_:" keys, to
     * avoid truncating legitimate content that happens to contain "id:".
     */
    private String stripJoplinMetadata(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // Split on CRLF or LF so metadata detection also works on Windows exports
        // (the key/value regexes anchor with $ semantics that \r would break).
        String[] lines = content.split("\r?\n", -1);
        int blockStart = joplinMetadataStart(lines);
        if (blockStart < 0) {
            return content;
        }
        if (blockStart > 0 && lines[blockStart - 1].isBlank()) {
            blockStart--;
        }
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < blockStart; j++) {
            sb.append(lines[j]);
            if (j < blockStart - 1) {
                sb.append("\n");
            }
        }
        return sb.toString().replaceAll("\n+$", "");
    }

    /** One Joplin in-note resource reference: {@code [text](:/<resourceId>)} or its {@code !}-marked image form. */
    record JexRef(boolean image, String linkText, String resourceId, String fullMatch) {}

    /** The Joplin item type bits we care about from a {@code type_:} metadata footer line. */
    static final String JOPLIN_TYPE_NOTE = "1";
    static final String JOPLIN_TYPE_TODO = "3";
    static final String JOPLIN_TYPE_FOLDER = "2";
    /** Ancestor chain cap: deeper folder runs keep the segments closest to the note. */
    static final int MAX_FOLDER_DEPTH = 24;
    /** Per-segment cap on a sanitized folder name inside a path tag. */
    static final int MAX_FOLDER_SEGMENT_LENGTH = 100;

    /** id / type_ / parent_id parsed out of a Joplin item's metadata footer. */
    record JoplinItemMeta(String id, String type, String parentId) {}

    /**
     * Parse {@code id:}, {@code type_:} and {@code parent_id:} from the item's trailing
     * Joplin metadata block, or return null when there is no valid block (e.g. plain
     * markdown files in a hand-built archive).
     */
    static JoplinItemMeta joplinItemMeta(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        String[] lines = content.split("\r?\n", -1);
        int blockStart = joplinMetadataStart(lines);
        if (blockStart < 0) {
            return null;
        }
        java.util.regex.Pattern keyLine = java.util.regex.Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(.*)$");
        String id = "", type = "", parent = "";
        for (int j = blockStart; j < lines.length; j++) {
            Matcher m = keyLine.matcher(lines[j]);
            if (!m.matches()) {
                continue;
            }
            switch (m.group(1)) {
                case "id" -> id = m.group(2).trim();
                case "type_" -> type = m.group(2).trim();
                case "parent_id" -> parent = m.group(2).trim();
                default -> { }
            }
        }
        if (id.isEmpty()) {
            return null;
        }
        return new JoplinItemMeta(id, type, parent);
    }

    /** First non-blank line of the item body — a folder's title is its body's only line. */
    private static String firstContentLine(String content) {
        for (String line : content.split("\r?\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    /**
     * One folder name inside a path tag: lowercase, whitespace runs (and any
     * stray path separators inside the title) become {@code _}, unicode letters
     * and digits survive, everything else is dropped. {@code /} is reserved
     * strictly for the hierarchy itself, so it can never appear inside a segment.
     */
    static String sanitizeFolderSegment(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String segment = title.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s/\\\\]+", "_")
                .replaceAll("[^\\p{L}\\p{N}_-]", "");
        if (segment.length() > MAX_FOLDER_SEGMENT_LENGTH) {
            segment = segment.substring(0, MAX_FOLDER_SEGMENT_LENGTH);
        }
        return segment;
    }

    /**
     * A note's full folder chain (grandparent/…/parent) as one hierarchical,
     * {@code /}-separated tag, or null when the note is not in a folder (or the
     * chain renders entirely empty). Guards against cycles and caps the chain
     * at {@link #MAX_FOLDER_DEPTH} levels, keeping the ones closest to the note.
     */
    static String folderPathTag(String folderId,
                                Map<String, String> folderTitles,
                                Map<String, String> folderParents) {
        java.util.Deque<String> segments = new java.util.ArrayDeque<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        String current = folderId;
        while (current != null && !current.isEmpty()
                && !visited.contains(current) && segments.size() < MAX_FOLDER_DEPTH) {
            visited.add(current);
            String segment = sanitizeFolderSegment(folderTitles.get(current));
            if (!segment.isEmpty()) {
                segments.addFirst(segment);
            }
            current = folderParents.get(current);
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    /**
     * Assemble a note's Tags string from its folder path (if any) and the
     * metadata footer's tags, defaulting to the {@code joplin-import} tag. The
     * folder path is the first casualty when the result would overflow the
     * tags column.
     */
    static String buildJoplinTags(String folderTag, String metaTags) {
        List<String> parts = new ArrayList<>();
        if (folderTag != null && !folderTag.isBlank()) {
            parts.add(folderTag);
        }
        if (metaTags != null && !metaTags.isBlank()) {
            parts.add(metaTags);
        }
        if (parts.isEmpty()) {
            parts.add("joplin-import");
        }
        String tags = String.join(" ", parts);
        if (tags.length() > MAX_TAGS_LENGTH && parts.size() > 1) {
            parts.remove(0);
            if (parts.isEmpty()) {
                parts.add("joplin-import");
            }
            tags = String.join(" ", parts);
        }
        if (tags.length() > MAX_TAGS_LENGTH) {
            tags = tags.substring(0, MAX_TAGS_LENGTH);
        }
        return tags;
    }

    /**
     * Joplin embeds attachments in note markdown as {@code [filename.ext](:/<resourceId>)};
     * images carry a leading {@code !} ({@code ![alt](:/<resourceId>)}). The resource id is
     * the 32-hex content hash Joplin stores files under, matching the <code>resources/</code>
     * directory of the JEX archive.
     */
    private static final Pattern JEX_REF_PATTERN = Pattern.compile(
            "(?i)(!?)(\\[([^\\]\\r\\n]*)\\]\\(:\\/([a-f0-9]{32})\\))");

    /** Parse every resource reference out of a note's markdown, in document order. */
    static List<JexRef> parseJoplinResourceRefs(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<JexRef> refs = new ArrayList<>();
        Matcher m = JEX_REF_PATTERN.matcher(content);
        while (m.find()) {
            refs.add(new JexRef(!m.group(1).isEmpty(), m.group(3), m.group(4).toLowerCase(Locale.ROOT), m.group(0)));
        }
        return refs;
    }

    /**
     * Resolve the note's {@code [name](:/id)} references against the archive's extracted
     * resources: builds the {@link NoteAttachment} rows to import (files only, no persist
     * yet — the caller persists the note first so the {@code note_id} FK can resolve) and
     * collects human-readable warnings. A resource that is missing from the archive (or
     * that was skipped for size) keeps its original link.
     */
    private List<String> attachJoplinResources(
            Note note,
            Map<String, JexResource> resources,
            List<NoteAttachment> prepared,
            Map<String, NoteAttachment> byResourceId) {
        List<String> messages = new ArrayList<>();
        for (JexRef ref : parseJoplinResourceRefs(note.content)) {
            if (byResourceId.containsKey(ref.resourceId())) {
                // Same resource referenced twice in one note — one attachment serves both links.
                continue;
            }
            JexResource resource = resources.get(ref.resourceId());
            if (resource == null) {
                String displayName = ref.linkText().isBlank() ? ref.resourceId() : ref.linkText();
                messages.add("Warning: attachment '" + displayName + "' referenced by note '"
                        + note.title + "' is not in the import archive; its link was left as-is");
                continue;
            }
            NoteAttachment attachment = new NoteAttachment();
            attachment.ownerId = note.ownerId;
            attachment.contentType = contentTypeForExtension(resource.extension());
            attachment.filename = joplinAttachmentFileName(ref, resource.extension());
            attachment.byteSize = resource.data().length;
            attachment.data = resource.data();
            prepared.add(attachment);
            byResourceId.put(ref.resourceId(), attachment);
        }
        return messages;
    }

    /**
     * Where the file name for an imported attachment comes from: the link's display text
     * (e.g. {@code [gpu-optimizer-camera-ready.pdf](:/…)} → {@code gpu-optimizer-camera-ready.pdf}),
     * sanitized; falling back to {@code <resourceId>.<extension>} when the text is empty
     * (image refs typically are).
     */
    static String joplinAttachmentFileName(JexRef ref, String extension) {
        String raw = ref.linkText() == null ? "" : ref.linkText();
        if (raw.isBlank()) {
            raw = ref.resourceId() + (extension == null || extension.isEmpty() ? "" : "." + extension);
        }
        String name = raw.replaceAll("[\\r\\n\\\\/<>:*?\"|\\u0000-\\u001F]", "_").trim();
        if (name.isEmpty()) {
            name = "attachment" + (extension == null || extension.isEmpty() ? "" : "." + extension);
        }
        if (name.length() > 255) {
            // Keep the tail so the extension survives.
            name = name.substring(name.length() - 255);
        }
        return name;
    }

    /**
     * Content type for an imported attachment, derived from the extension Joplin
     * exported it under. Whitelisted types (the same set the manual upload endpoint
     * accepts) keep their real type so images render inline and PDFs preview; every
     * other type is stored as a plain download — imports migrate legacy data of any
     * kind, the upload endpoint's whitelist stays tight.
     */
    static String contentTypeForExtension(String extension) {
        return switch (extension == null ? "" : extension.toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    /**
     * Rewrite the note's Joplin resource links to point at the imported attachments.
     * Images keep their {@code ![…]} form and render inline; every other attachment
     * becomes a {@code ?download=1} link so clicking always saves the file (mirroring
     * the UI's blob-download behavior). Links whose resource was not imported keep
     * the original {@code :/…} form untouched.
     */
    static String rewriteJoplinResourceLinks(String content, Map<String, NoteAttachment> byResourceId) {
        if (content == null || content.isEmpty() || byResourceId == null || byResourceId.isEmpty()) {
            return content;
        }
        Matcher m = JEX_REF_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            NoteAttachment attachment = byResourceId.get(m.group(4).toLowerCase(Locale.ROOT));
            String replacement;
            if (attachment == null || attachment.id == null) {
                replacement = m.group(0);
            } else {
                String url = "/api/attachments/" + attachment.id;
                String text = m.group(3);
                if (!m.group(1).isEmpty()) {
                    // Image: preserve the !…() form so it renders inline.
                    replacement = "![" + text + "](" + url + ")";
                } else {
                    // Document: labeled (empty-text links become the filename) so it reads
                    // sensibly, and saved to disk on click instead of navigating.
                    String label = text.isBlank() ? attachment.filename : text;
                    replacement = "[" + label + "](" + url + "?download=1)";
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Extract tags from the Joplin metadata footer, if present. Supports both
     * space-delimited ("tags: a b c") and comma-delimited ("tags: a, b, c") forms.
     * Returns a space-delimited tag string (the format used by the Tags field).
     */
    private String extractJoplinTags(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\r?\n", -1);
        int blockStart = joplinMetadataStart(lines);
        if (blockStart < 0) {
            return "";
        }
        for (int i = blockStart; i < lines.length; i++) {
            if (lines[i].matches("^\\s*tags?\\s*:.*")) {
                String value = lines[i].substring(lines[i].indexOf(':') + 1).trim();
                if (value.startsWith("[") && value.endsWith("]")) {
                    value = value.substring(1, value.length() - 1);
                }
                String[] parts = value.split("[,\\s]+");
                StringBuilder sb = new StringBuilder();
                for (String part : parts) {
                    String tag = part.replaceAll("[^a-zA-Z0-9_-]", "").trim();
                    if (!tag.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append(" ");
                        }
                        sb.append(tag);
                    }
                }
                return sb.toString();
            }
        }
        return "";
    }
}
