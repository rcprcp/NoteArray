package com.notearray.security;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Applies security headers to every REST response, and echoes or generates an
 * {@code X-Request-Id} so every API request can be traced in the access log
 * ({@code quarkus.http.access-log.pattern} references it).
 *
 * <p>Tight anti-framing defaults ({@code X-Frame-Options: DENY} and
 * {@code frame-ancestors 'none'}) protect HTML documents and endpoints from
 * clickjacking. The one deliberate exception is the attachment download
 * endpoint ({@code GET /api/attachments/{id}}): the application's own
 * attachment preview modal renders PDFs in a same-origin {@code <iframe>}, so
 * those responses allow framing from {@code 'self'} only. Other sites can
 * still never embed them.</p>
 */
@Provider
public class SecurityHeadersFilter implements ContainerResponseFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Length cap for caller-supplied request ids (defends against log abuse). */
    static final int MAX_REQUEST_ID_LENGTH = 128;

    /** Default Content-Security-Policy: nothing may frame our responses. */
    public static final String CSP_VALUE = "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; "
            + "connect-src 'self'; "
            + "font-src 'self'; "
            + "object-src 'none'; "
            + "frame-ancestors 'none'; "
            + "base-uri 'self'; "
            + "form-action 'self';";

    /**
     * Content-Security-Policy for same-origin-embeddable content (attachment
     * downloads rendered in the app's preview modal). Identical to
     * {@link #CSP_VALUE} except {@code frame-ancestors} is {@code 'self'}.
     */
    public static final String CSP_SELF_EMBEDDABLE_VALUE = "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; "
            + "connect-src 'self'; "
            + "font-src 'self'; "
            + "object-src 'none'; "
            + "frame-ancestors 'self'; "
            + "base-uri 'self'; "
            + "form-action 'self';";

    public static final String HSTS_VALUE = "max-age=31536000; includeSubDomains; preload";

    /** URI path (relative to the application root) that may be framed by 'self'. */
    static final String EMBEDDABLE_PATH_PREFIX = "api/attachments/";

    @ConfigProperty(name = "notearray.session.cookie-secure", defaultValue = "false")
    boolean hstsEnabled;

    public SecurityHeadersFilter() {
    }

    SecurityHeadersFilter(boolean hstsEnabled) {
        this.hstsEnabled = hstsEnabled;
    }

    @Override
    public void filter(ContainerRequestContext containerRequestContext, ContainerResponseContext containerResponseContext) throws IOException {
        boolean embeddable = isEmbeddableAttachmentPath(containerRequestContext);
        containerResponseContext.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
        containerResponseContext.getHeaders().putSingle("X-Frame-Options", embeddable ? "SAMEORIGIN" : "DENY");
        containerResponseContext.getHeaders().putSingle("Referrer-Policy", "strict-origin-when-cross-origin");
        containerResponseContext.getHeaders().putSingle("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        containerResponseContext.getHeaders().putSingle("Content-Security-Policy",
                embeddable ? CSP_SELF_EMBEDDABLE_VALUE : CSP_VALUE);
        containerResponseContext.getHeaders().putSingle(REQUEST_ID_HEADER, requestIdFor(containerRequestContext));

        if (hstsEnabled) {
            containerResponseContext.getHeaders().putSingle("Strict-Transport-Security", HSTS_VALUE);
        }
    }

    /**
     * The request id to report for this request: the caller's {@code X-Request-Id}
     * header when present (sanitized and length-capped so it can neither inject
     * response headers nor forge arbitrary log lines), else a generated id.
     */
    static String requestIdFor(ContainerRequestContext requestContext) {
        String incoming = requestContext == null ? null : requestContext.getHeaderString(REQUEST_ID_HEADER);
        String id = incoming == null || incoming.isBlank() ? randomRequestId() : incoming.trim();
        // Keep only url/UUID-safe characters; strips CR/LF and control characters
        // that could smuggle extra headers or pollute log lines.
        id = id.replaceAll("[^A-Za-z0-9._-]", "");
        if (id.isEmpty()) {
            id = randomRequestId();
        }
        return id.length() > MAX_REQUEST_ID_LENGTH ? id.substring(0, MAX_REQUEST_ID_LENGTH) : id;
    }

    /** Short, collision-practically-impossible opaque request id (first 18 chars of a UUID). */
    static String randomRequestId() {
        return UUID.randomUUID().toString().substring(0, 18);
    }

    static boolean isEmbeddableAttachmentPath(ContainerRequestContext requestContext) {
        if (requestContext == null || requestContext.getUriInfo() == null) {
            return false;
        }
        String path = requestContext.getUriInfo().getPath();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path.startsWith(EMBEDDABLE_PATH_PREFIX);
    }
}
