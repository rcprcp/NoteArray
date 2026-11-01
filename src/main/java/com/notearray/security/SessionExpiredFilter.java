package com.notearray.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Gives unauthenticated API responses a machine-readable body so the SPA can
 * tell an expired session apart from a failed login attempt:
 *
 * <pre>HTTP/1.1 401 Unauthorized
 * {"code":"session_expired"}</pre>
 *
 * <p>The login endpoints keep their existing human-readable messages (for
 * example "invalid or expired verification code"); the client excludes those
 * paths from the re-authentication prompt anyway.</p>
 */
@ApplicationScoped
public class SessionExpiredFilter {

    public static final String CODE = "session_expired";

    @ServerResponseFilter
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo() == null ? "" : request.getUriInfo().getPath();
        if (shouldDecorate(response.getStatus(), path, response.getEntity() == null)) {
            response.setEntity(Map.of("code", CODE));
            response.getHeaders().putSingle(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
        }
    }

    /**
     * True only for a body-less 401 from a protected endpoint. A 401 that
     * already carries a message (login/throttling) must not be rewritten.
     */
    static boolean shouldDecorate(int status, String path, boolean emptyBody) {
        if (status != 401 || !emptyBody) {
            return false;
        }
        String normalized = path == null ? "" : path;
        return !normalized.startsWith("users/login")
                && !normalized.equals("users")
                && !normalized.equals("users/logout");
    }
}
