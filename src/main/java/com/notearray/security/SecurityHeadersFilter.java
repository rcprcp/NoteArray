package com.notearray.security;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.UUID;

/**
 * Echoes or generates an {@code X-Request-Id} on every REST response so each API
 * request can be traced in the access log ({@code quarkus.http.access-log.pattern}
 * references it).
 *
 * <p>This filter deliberately does <b>not</b> set the other security headers
 * ({@code Content-Security-Policy}, {@code X-Frame-Options},
 * {@code X-Content-Type-Options}, {@code Referrer-Policy},
 * {@code Permissions-Policy} and, in prod, {@code Strict-Transport-Security}).
 * A JAX-RS response filter only runs for REST endpoints, so headers set here
 * never reached the static SPA document ({@code /}, {@code /index.html},
 * {@code /assets/*}) — the document CSP and anti-framing exist to protect. Those
 * headers are now defined once, globally, via {@code quarkus.http.header.*} in
 * {@code application.properties}; setting them here as well additionally emitted
 * duplicate headers on every REST response.</p>
 */
@Provider
public class SecurityHeadersFilter implements ContainerResponseFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Length cap for caller-supplied request ids (defends against log abuse). */
    static final int MAX_REQUEST_ID_LENGTH = 128;

    @Override
    public void filter(ContainerRequestContext containerRequestContext, ContainerResponseContext containerResponseContext) throws IOException {
        containerResponseContext.getHeaders().putSingle(REQUEST_ID_HEADER, requestIdFor(containerRequestContext));
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
}
