package com.notearray.resource;

import com.notearray.entity.NoteAttachment;
import com.notearray.entity.User;
import com.notearray.service.SessionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Attachment download endpoint ({@code GET /api/attachments/{id}}) and shared
 * attachment helpers (MIME whitelist, filename sanitization). Note: the upload
 * endpoint lives in {@link NoteResource} ({@code POST /api/notes/{id}/attachments})
 * because all notes-subresource routes must belong to the resource class that owns
 * the {@code /api/notes} root, or the routes will not match at runtime.
 */
@Path("/api/attachments")
public class AttachmentResource {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "application/pdf");

    @Context
    HttpHeaders headers;

    @Inject
    SessionService sessions;

    public record AttachmentResponse(
            UUID id,
            UUID noteId,
            String filename,
            String contentType,
            long byteSize,
            String url) {}

    public static boolean isAllowedContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String base = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return ALLOWED_CONTENT_TYPES.contains(base);
    }

    public static String normalizeContentType(String contentType, String filename) {
        if (contentType != null && !contentType.isBlank()) {
            String base = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (ALLOWED_CONTENT_TYPES.contains(base)) {
                return base;
            }
        }
        if (filename != null) {
            String lower = filename.trim().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".pdf")) return "application/pdf";
        }
        return null;
    }

    public static String sanitizeFilename(String rawFilename, String contentType) {
        String name = rawFilename == null ? "" : rawFilename.replaceAll("[\\r\\n\\\\/\"<>|:*?\\u0000-\\u001F]", "_").trim();
        if (name.isEmpty()) {
            name = "application/pdf".equals(contentType) ? "document.pdf" : "image.png";
        }
        if (name.length() > 255) {
            name = name.substring(name.length() - 255);
        }
        return name;
    }

    private boolean canAccessAttachment(User caller, NoteAttachment attachment) {
        return caller.superuser || attachment.ownerId.equals(caller.id);
    }

    @GET
    @Path("/{id}")
    public Response download(@PathParam("id") UUID id, @QueryParam("download") String downloadParam) {
        User user = sessions.resolve(headers.getCookies().get(SessionService.COOKIE_NAME));
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        NoteAttachment attachment = NoteAttachment.findById(id);
        if (attachment == null || !canAccessAttachment(user, attachment)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // Inline when previewing in the app; attachment (download prompt with the
        // stored filename) when ?download is set. Any non-"false" request parameter
        // value (e.g. ?download=1 or ?download=true) enables the download mode.
        boolean forceDownload = downloadParam != null
                && !"false".equalsIgnoreCase(downloadParam.trim());
        String disposition = forceDownload ? "attachment" : "inline";
        return Response.ok(attachment.data, attachment.contentType)
                .header("Content-Disposition",
                        disposition + "; filename=\"" + attachment.filename + "\"")
                .header("Cache-Control", "private, max-age=86400")
                .build();
    }
}
