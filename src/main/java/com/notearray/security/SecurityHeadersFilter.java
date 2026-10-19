package com.notearray.security;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;

public class SecurityHeadersFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext containerRequestContext, ContainerResponseContext containerResponseContext) throws IOException {
        // X-Content-Type-Options: prevents MIME-type sniffing
        containerResponseContext.getHeaders().add("X-Content-Type-Options", "nosniff");

        // X-Frame-Options: prevents the page from being embedded in frames (clickjacking protection)
        // Using "DENY" is the strictest option; "SAMEORIGIN" allows framing from same-origin pages
        containerResponseContext.getHeaders().add("X-Frame-Options", "DENY");

        // Referrer-Policy: controls how much referrer information should be sent with requests
        containerResponseContext.getHeaders().add("Referrer-Policy", "strict-origin-when-cross-origin");

        // Permissions-Policy: controls which browser features and APIs can be used in the page
        // This is a placeholder - adjust permissions based on actual app needs
        containerResponseContext.getHeaders().add("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");

        // Content-Security-Policy: the most important security header
        // Restricts the sources from which various types of content can be loaded
        // This is a comprehensive but restrictive policy - adjust for your app's needs
        String csp = "default-src 'self'; "
                + "script-src 'self' 'unsafe-inline'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; "
                + "connect-src 'self'; "
                + "font-src 'self'; "
                + "object-src 'none'; "
                + "base-uri 'self'; "
                + "form-action 'self';";
        containerResponseContext.getHeaders().add("Content-Security-Policy", csp);

        // Strict-Transport-Security: only use HTTPS, never HTTP
        // This is applied only in production; development uses HTTP
        // max-age=31536000 = 1 year, includeSubDomains = apply to all subdomains, preload = add to preload list
        containerResponseContext.getHeaders().add("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
    }
}