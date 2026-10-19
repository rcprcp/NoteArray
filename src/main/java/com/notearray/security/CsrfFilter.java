package com.notearray.security;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Defense-in-depth CSRF protection for state-changing requests ({@code POST},
 * {@code PUT}, {@code DELETE}, {@code PATCH}).
 *
 * <h2>CSRF Security Invariants</h2>
 * NoteArray relies on three independent layers to prevent Cross-Site Request
 * Forgery:
 * <ol>
 *   <li><b>{@code SameSite=Strict} session cookie</b> ({@link com.notearray.service.SessionService}):
 *       browsers do not attach {@code NOTEARRAY_SESSION} to cross-site requests.</li>
 *   <li><b>Non-form {@code Content-Type} enforcement</b>: write endpoints only
 *       consume {@code application/json} (or {@code application/octet-stream} for
 *       imports), which HTML {@code <form>} elements cannot send and which forces
 *       a CORS preflight on cross-origin {@code fetch}/{@code XHR}.</li>
 *   <li><b>{@code Sec-Fetch-Site} and {@code Origin} verification</b> (this filter):
 *       blocks cross-site state-changing requests with {@code 403 Forbidden} even
 *       if {@code SameSite} or CORS settings are ever relaxed in the future.</li>
 * </ol>
 */
@Provider
@PreMatching
public class CsrfFilter implements ContainerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> ALLOWED_FETCH_SITES = Set.of("same-origin", "same-site", "none");

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String method = requestContext.getMethod();
        if (method == null || SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return;
        }

        String secFetchSite = requestContext.getHeaderString("Sec-Fetch-Site");
        if (secFetchSite != null && !secFetchSite.isBlank()) {
            String normalizedSite = secFetchSite.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_FETCH_SITES.contains(normalizedSite)) {
                abortForbidden(requestContext);
                return;
            }
        }

        String origin = requestContext.getHeaderString("Origin");
        if (origin != null && !origin.isBlank()) {
            String trimmedOrigin = origin.trim();
            if ("null".equalsIgnoreCase(trimmedOrigin)) {
                abortForbidden(requestContext);
                return;
            }
            String originHost = extractUriHost(trimmedOrigin);
            String targetHost = extractTargetHost(requestContext);
            if (originHost == null || (targetHost != null && !originHost.equalsIgnoreCase(targetHost))) {
                abortForbidden(requestContext);
            }
        }
    }

    static String extractUriHost(String uriString) {
        try {
            URI uri = URI.create(uriString);
            return uri.getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String extractTargetHost(ContainerRequestContext requestContext) {
        String hostHeader = requestContext.getHeaderString("Host");
        if (hostHeader != null && !hostHeader.isBlank()) {
            String trimmed = hostHeader.trim();
            // IPv6 literal in brackets, e.g. [::1]:8080
            if (trimmed.startsWith("[")) {
                int close = trimmed.indexOf(']');
                return close > 0 ? trimmed.substring(0, close + 1) : trimmed;
            }
            int colon = trimmed.indexOf(':');
            return colon >= 0 ? trimmed.substring(0, colon) : trimmed;
        }
        if (requestContext.getUriInfo() != null && requestContext.getUriInfo().getRequestUri() != null) {
            return requestContext.getUriInfo().getRequestUri().getHost();
        }
        return null;
    }

    private static void abortForbidden(ContainerRequestContext requestContext) {
        requestContext.abortWith(
                Response.status(Response.Status.FORBIDDEN)
                        .entity("Cross-site request blocked")
                        .build());
    }
}
