package com.notearray.resource;

import com.notearray.entity.Note;
import com.notearray.entity.NoteAttachment;
import com.notearray.entity.User;
import com.notearray.service.SessionService;
import com.notearray.service.StorageQuota;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Path("/api")
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

    private User getAuthenticatedUser() {
        return sessions.resolve(headers.getCookies().get(SessionService.COOKIE_NAME));
    }

    private boolean canAccessNote(User caller, Note note) {
        return caller.superuser || note.ownerId.equals(caller.id);
    }

    private boolean canAccessAttachment(User caller, NoteAttachment attachment) {
        return caller.superuser || attachment.ownerId.equals(caller.id);
    }

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

    @POST
    @Path("/notes/{noteId}/attachments")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response upload(
            @PathParam("noteId") UUID noteId,
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
        String resolvedType = normalizeContentType(rawContentType, filenameParam);
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

        long currentUsed = NoteResource.calculateTotalStorageBytes(note.ownerId);
        User owner = User.findById(note.ownerId);
        String plan = owner != null ? owner.plan : user.plan;
        Response quotaError = StorageQuota.checkCanCreate(currentUsed, bytes.length, plan);
        if (quotaError != null) {
            return quotaError;
        }

        String cleanName = sanitizeFilename(filenameParam, resolvedType);
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
                .entity(new AttachmentResponse(
                        attachment.id,
                        attachment.noteId,
                        attachment.filename,
                        attachment.contentType,
                        attachment.byteSize,
                        url))
                .build();
    }

    @GET
    @Path("/attachments/{id}")
    public Response download(@PathParam("id") UUID id) {
        User user = getAuthenticatedUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        NoteAttachment attachment = NoteAttachment.findById(id);
        if (attachment == null || !canAccessAttachment(user, attachment)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(attachment.data, attachment.contentType)
                .header("Content-Disposition", "inline; filename=\"" + attachment.filename + "\"")
                .header("Cache-Control", "private, max-age=86400")
                .build();
    }
}
