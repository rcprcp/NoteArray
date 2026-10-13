package com.notearray.resource;

import com.notearray.entity.Note;
import com.notearray.entity.User;
import com.notearray.service.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
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
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

@Path("/api/notes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NoteResource {

    @Context
    HttpHeaders headers;

    @Inject
    SessionService sessions;

    @Inject
    ObjectMapper objectMapper;

    /** Request body. ownerId is ignored; owner is always the authenticated user. */
    public record NoteRequest(String title, String content, String tags, Boolean pinned) {}

    public record ImportResult(int imported, int errors, List<String> messages) {}

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
     * If q is provided, at least one of searchTitles / searchContent must be true (else 400).
     *
     * Optional filters (all combined with AND):
     *   tags           - repeatable; note must have every listed tag
     *   untagged       - only notes without tags
     *   pinned         - only pinned notes
     *   createdFrom / createdTo - ISO-8601 instants, inclusive range on creation time
     *   updatedFrom / updatedTo - ISO-8601 instants, inclusive range on modification time
     */
    @GET
    @SuppressWarnings("unchecked")
    public Response list(@QueryParam("q") String q,
                         @QueryParam("searchTitles") @jakarta.ws.rs.DefaultValue("false") boolean searchTitles,
                         @QueryParam("searchContent") @jakarta.ws.rs.DefaultValue("false") boolean searchContent,
                         @QueryParam("tags") List<String> tags,
                         @QueryParam("untagged") @jakarta.ws.rs.DefaultValue("false") boolean untagged,
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
        if (hasQuery && !searchTitles && !searchContent) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Select at least one of searchTitles or searchContent").build();
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
            if (searchTitles) {
                sql.append("n.title_tsv @@ plainto_tsquery('english', :q)");
            }
            if (searchContent) {
                if (searchTitles) sql.append(" OR ");
                sql.append("n.content_tsv @@ plainto_tsquery('english', :q)");
            }
            sql.append(")");
            params.put("q", q.trim());
        }
        if (!user.superuser) {
            sql.append(" AND n.owner_id = :owner");
            params.put("owner", user.id);
        }
        if (untagged) {
            sql.append(" AND btrim(coalesce(n.tags, '')) = ''");
        }
        if (pinned) {
            sql.append(" AND n.pinned = true");
        }
        int i = 0;
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null || tag.isBlank()) continue;
                String escaped = tag.trim().toLowerCase()
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                String name = "tag" + i++;
                sql.append(" AND (' ' || lower(coalesce(n.tags, '')) || ' ') LIKE :").append(name);
                params.put(name, "% " + escaped + " %");
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
        return Response.ok(results).build();
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value.trim());
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
        Note note = new Note();
        note.ownerId = user.id;
        note.title = req.title().trim();
        note.content = req.content();
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
        if (req.title() != null) {
            note.title = req.title().trim();
        }
        if (req.content() != null) {
            note.content = req.content();
        }
        if (req.tags() != null) {
            note.tags = req.tags().trim();
        }
        if (req.pinned() != null) {
            note.pinned = req.pinned();
        }
        return Response.ok(note).build();
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

        List<String> messages = new ArrayList<>();
        int imported = 0;
        int errors = 0;

        byte[] data;
        try {
            data = jexStream.readAllBytes();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Failed to read JEX file: " + e.getMessage()).build();
        }

        Map<String, String> folderMap = new HashMap<>();
        Map<String, String> noteMap = new HashMap<>();

        boolean isZip = data.length > 3 && data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04;
        boolean isTar = data.length > 262 && (new String(data, 257, 5, StandardCharsets.US_ASCII)).equals("ustar");

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
                            JsonNode node = objectMapper.readTree(readZipEntryAsString(zis, buffer));
                            String type = node.has("type_") ? node.get("type_").asText() : "";
                            String id = node.has("id") ? node.get("id").asText() : "";
                            if (!id.isEmpty() && "2".equals(type)) {
                                String title = node.has("title") ? node.get("title").asText() : "Untitled";
                                folderMap.put(id, title);
                            } else if (!id.isEmpty() && "1".equals(type)) {
                                String parentId = node.has("parent_id") ? node.get("parent_id").asText() : "";
                                if (!parentId.isEmpty()) {
                                    noteMap.put(id, parentId);
                                }
                            }
                        } catch (Exception e) {
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
                        try {
                            String content = readZipEntryAsString(zis, buffer);
                            String filename = entry.getName();
                            int dot = filename.lastIndexOf('.');
                            if (dot < 0) {
                                continue;
                            }
                            String noteId = filename.substring(0, dot);
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
                            note.title = title.isEmpty() ? "Untitled" : title.substring(0, Math.min(255, title.length()));
                            note.content = content;

                            List<String> tagParts = new ArrayList<>();
                            String folderId = noteMap.get(noteId);
                            if (folderId != null && folderMap.containsKey(folderId)) {
                                String folderTag = folderMap.get(folderId).replaceAll("[^a-zA-Z0-9\\s-]", "").trim();
                                if (!folderTag.isEmpty()) {
                                    tagParts.add(folderTag);
                                }
                            }
                            if (!metaTags.isEmpty()) {
                                tagParts.add(metaTags);
                            }
                            if (tagParts.isEmpty()) {
                                tagParts.add("joplin-import");
                            }
                            note.tags = String.join(" ", tagParts);

                            note.persist();
                            imported++;
                        } catch (Exception e) {
                            errors++;
                            messages.add("Error importing note: " + e.getMessage());
                        }
                    }
                }
            } else {
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
                        try {
                            String content = readTarEntryAsString(tis, buffer, (int) entry.getSize());
                            String filename = name.replaceFirst("^.*/", "");
                            if (filename.isEmpty()) {
                                continue;
                            }
                            int dot = filename.lastIndexOf('.');
                            if (dot < 0) {
                                continue;
                            }
                            String noteId = filename.substring(0, dot);
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
                            note.title = title.isEmpty() ? "Untitled" : title.substring(0, Math.min(255, title.length()));
                            note.content = content;
                            note.tags = metaTags.isEmpty() ? "joplin-import" : metaTags;
                            note.persist();
                            imported++;
                        } catch (Exception e) {
                            errors++;
                            messages.add("Error importing note: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Failed to process JEX file: " + e.getMessage()).build();
        }

        return Response.ok(new ImportResult(imported, errors, messages)).build();
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
            String content = new String(mdStream.readAllBytes(), StandardCharsets.UTF_8);
            String title = "Untitled";
            if (!content.isEmpty()) {
                String[] lines = content.split("\n", 2);
                if (lines[0].startsWith("#")) {
                    title = lines[0].replaceAll("^#+\s*", "").trim();
                } else if (!lines[0].isEmpty()) {
                    title = lines[0];
                }
            }
            Note note = new Note();
            note.ownerId = user.id;
            note.title = title.substring(0, Math.min(255, title.length()));
            note.content = content;
            note.tags = "markdown-import";
            note.persist();
            return Response.ok(note).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Failed to process markdown file: " + e.getMessage()).build();
        }
    }

    private String readZipEntryAsString(ZipInputStream zis, byte[] buffer) throws Exception {
        StringBuilder sb = new StringBuilder();
        int len;
        while ((len = zis.read(buffer)) > 0) {
            sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Index of the first line of the trailing Joplin "key: value" metadata block,
     * or -1 if no such block is present. The block must be contiguous to the end
     * and contain both "id:" and "type_:" keys.
     */
    private int joplinMetadataStart(String[] lines) {
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
        String[] lines = content.split("\n", -1);
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

    /**
     * Extract tags from the Joplin metadata footer, if present. Supports both
     * space-delimited ("tags: a b c") and comma-delimited ("tags: a, b, c") forms.
     * Returns a space-delimited tag string (the format used by the Tags field).
     */
    private String extractJoplinTags(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\n", -1);
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

    private String readTarEntryAsString(TarArchiveInputStream tis, byte[] buffer, int size) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (size > 0) {
            byte[] data = tis.readNBytes(size);
            sb.append(new String(data, StandardCharsets.UTF_8));
            return sb.toString();
        }
        int len;
        while ((len = tis.read(buffer)) > 0) {
            sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
