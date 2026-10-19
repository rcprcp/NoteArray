package com.notearray.security;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
public class SecurityHeadersFilter implements ContainerResponseFilter {

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

    public static final String HSTS_VALUE = "max-age=31536000; includeSubDomains; preload";

    @ConfigProperty(name = "notearray.session.cookie-secure", defaultValue = "false")
    boolean hstsEnabled;

    public SecurityHeadersFilter() {
    }

    SecurityHeadersFilter(boolean hstsEnabled) {
        this.hstsEnabled = hstsEnabled;
    }

    @Override
    public void filter(ContainerRequestContext containerRequestContext, ContainerResponseContext containerResponseContext) throws IOException {
        containerResponseContext.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
        containerResponseContext.getHeaders().putSingle("X-Frame-Options", "DENY");
        containerResponseContext.getHeaders().putSingle("Referrer-Policy", "strict-origin-when-cross-origin");
        containerResponseContext.getHeaders().putSingle("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        containerResponseContext.getHeaders().putSingle("Content-Security-Policy", CSP_VALUE);

        if (hstsEnabled) {
            containerResponseContext.getHeaders().putSingle("Strict-Transport-Security", HSTS_VALUE);
        }
    }
}
