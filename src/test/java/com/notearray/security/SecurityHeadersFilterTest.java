package com.notearray.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.notearray.resource.NoteResource;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import org.junit.jupiter.api.Test;

class SecurityHeadersFilterTest {

    private static ContainerRequestContext requestContextWithPath(String path) {
        UriInfo uriInfo = (UriInfo) Proxy.newProxyInstance(
                UriInfo.class.getClassLoader(),
                new Class<?>[] { UriInfo.class },
                (proxy, method, args) -> {
                    if ("getPath".equals(method.getName())) {
                        return path;
                    }
                    return null;
                });
        return (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[] { ContainerRequestContext.class },
                (proxy, method, args) -> {
                    if ("getUriInfo".equals(method.getName())) {
                        return uriInfo;
                    }
                    return null;
                });
    }

    private static ContainerResponseContext responseContextWithHeaders(MultivaluedMap<String, Object> headers) {
        return (ContainerResponseContext) Proxy.newProxyInstance(
                ContainerResponseContext.class.getClassLoader(),
                new Class<?>[] { ContainerResponseContext.class },
                (proxy, method, args) -> {
                    if ("getHeaders".equals(method.getName())) {
                        return headers;
                    }
                    return null;
                });
    }

    @Test
    void addsRequiredSecurityHeadersAndStrictCsp() throws IOException {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        SecurityHeadersFilter filter = new SecurityHeadersFilter(false);
        filter.filter(null, responseContextWithHeaders(headers));

        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
        assertEquals("DENY", headers.getFirst("X-Frame-Options"));
        assertEquals("strict-origin-when-cross-origin", headers.getFirst("Referrer-Policy"));
        assertEquals("camera=(), microphone=(), geolocation=(), payment=()", headers.getFirst("Permissions-Policy"));

        String csp = (String) headers.getFirst("Content-Security-Policy");
        assertTrue(csp.contains("default-src 'self'"));
        assertTrue(csp.contains("script-src 'self';"));
        assertFalse(csp.contains("script-src 'self' 'unsafe-inline'"));
        assertTrue(csp.contains("frame-ancestors 'none'"));
        assertTrue(csp.contains("object-src 'none'"));

        // HSTS should not be emitted when hstsEnabled is false (dev HTTP)
        assertNull(headers.getFirst("Strict-Transport-Security"));
    }

    @Test
    void allowsSelfFramingOnlyForAttachmentDownloadPath() throws IOException {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        SecurityHeadersFilter filter = new SecurityHeadersFilter(false);

        filter.filter(requestContextWithPath("api/attachments/d1cfd292"), responseContextWithHeaders(headers));
        assertEquals("SAMEORIGIN", headers.getFirst("X-Frame-Options"));
        assertTrue(((String) headers.getFirst("Content-Security-Policy")).contains("frame-ancestors 'self'"));
        assertFalse(((String) headers.getFirst("Content-Security-Policy")).contains("frame-ancestors 'none'"));

        // Any other path keeps the strict anti-framing default
        MultivaluedMap<String, Object> strict = new MultivaluedHashMap<>();
        filter.filter(requestContextWithPath("api/notes/abc"), responseContextWithHeaders(strict));
        assertEquals("DENY", strict.getFirst("X-Frame-Options"));
        assertTrue(((String) strict.getFirst("Content-Security-Policy")).contains("frame-ancestors 'none'"));
    }

    @Test
    void addsHstsHeaderWhenEnabledForProduction() throws IOException {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        SecurityHeadersFilter filter = new SecurityHeadersFilter(true);
        filter.filter(null, responseContextWithHeaders(headers));

        assertEquals(
                "max-age=31536000; includeSubDomains; preload",
                headers.getFirst("Strict-Transport-Security"));
    }

    @Test
    void sanitizesDangerousHtmlAndJavascriptLinksOnIngest() {
        String raw = "# Heading\n\n"
                + "<script>alert('xss')</script>\n"
                + "<img src=\"x\" onerror=\"alert(1)\">\n"
                + "[bad](javascript:alert(1))\n"
                + "[good](https://example.com)\n"
                + "[mail](mailto:user@example.com)";
        String cleaned = NoteResource.sanitizeContent(raw);

        assertFalse(cleaned.contains("<script>"));
        assertFalse(cleaned.contains("onerror"));
        assertFalse(cleaned.toLowerCase().contains("javascript:"));
        assertTrue(cleaned.contains("[good](https://example.com)"));
        assertTrue(cleaned.contains("[mail](mailto:user@example.com)"));
    }
}
