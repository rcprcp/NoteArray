package com.notearray.service;

import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.core.HttpHeaders;

/** Resolves the client address used as a rate-limit key. */
public final class ClientIp {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIp() {
    }

    /**
     * The address to key IP-based limits on.
     *
     * <p>By default this is the socket address of the connecting peer, which a
     * client cannot forge. Only when {@code trustProxy} is enabled — i.e. the app
     * runs behind a reverse proxy that overwrites or appends
     * {@code X-Forwarded-For} — is the header consulted, using its rightmost
     * entry (the one added by the trusted proxy). Enabling it without such a
     * proxy lets clients spoof the header and sidestep the limits.
     */
    public static String resolve(HttpServerRequest request, HttpHeaders headers, boolean trustProxy) {
        if (trustProxy) {
            String forwarded = headers == null ? null : headers.getHeaderString(X_FORWARDED_FOR);
            String candidate = rightmostForwarded(forwarded);
            if (candidate != null) {
                return candidate;
            }
        }
        if (request == null || request.remoteAddress() == null) {
            return "unknown";
        }
        String host = request.remoteAddress().hostAddress();
        return host == null ? "unknown" : host;
    }

    /** The rightmost non-blank entry of an X-Forwarded-For header, or null. */
    static String rightmostForwarded(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String[] parts = header.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String value = parts[i].trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
