package com.notearray.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.notearray.resource.NoteResource;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class SecurityHeadersFilterTest {

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
