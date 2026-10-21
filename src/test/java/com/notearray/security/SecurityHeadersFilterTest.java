package com.notearray.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        return requestContext(path, (String) null);
    }

    private static ContainerRequestContext requestContext(String path, String requestIdHeader) {
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
                    if ("getHeaderString".equals(method.getName())) {
                        return requestIdHeader;
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

    @Test
    void generatesRequestIdWhenCallerSendsNone() throws IOException {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        // null request context (unit test) must not throw; header is still generated.
        new SecurityHeadersFilter(false).filter(null, responseContextWithHeaders(headers));
        Object generated = headers.getFirst(SecurityHeadersFilter.REQUEST_ID_HEADER);
        assertNotNull(generated, "X-Request-Id must always be emitted");
        assertTrue(((String) generated).matches("[A-Za-z0-9._-]{18}"),
                "generated id should be 18 url-safe chars, was: " + generated);

        // Same for a request context with no X-Request-Id header.
        MultivaluedMap<String, Object> headers2 = new MultivaluedHashMap<>();
        new SecurityHeadersFilter(false)
                .filter(requestContextWithPath("api/notes"), responseContextWithHeaders(headers2));
        assertNotNull(headers2.getFirst(SecurityHeadersFilter.REQUEST_ID_HEADER));
    }

    @Test
    void echoesCallerRequestIdWhenWellFormed() throws IOException {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        new SecurityHeadersFilter(false).filter(
                requestContext("api/notes", "req_abc123.def-456"),
                responseContextWithHeaders(headers));
        assertEquals("req_abc123.def-456", headers.getFirst(SecurityHeadersFilter.REQUEST_ID_HEADER));
    }

    @Test
    void sanitizesMaliciousRequestId_untrustedHeaderCannotInjectOrForge() throws IOException {
        // CR/LF header-injection attempt: the reported value must be a single,
        // injection-free token.
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        new SecurityHeadersFilter(false).filter(
                requestContext("api/notes", "abc\r\nSet-Cookie: x=1<script>%20"),
                responseContextWithHeaders(headers));
        String reported = (String) headers.getFirst(SecurityHeadersFilter.REQUEST_ID_HEADER);
        assertFalse(reported.contains("\r") || reported.contains("\n"),
                "CR/LF must be stripped from the echoed request id");
        assertFalse(headers.containsKey("Set-Cookie"));
        for (char c : reported.toCharArray()) {
            assertTrue(c == '-' || c == '_' || c == '.' || Character.isLetterOrDigit(c),
                    "unexpected character in reported id: " + reported);
        }
    }

    @Test
    void capsRequestIdLength() {
        StringBuilder longId = new StringBuilder("0123456789");
        for (int i = 0; i < 20; i++) {
            longId.append("abcdefghij");
        }
        String capped = SecurityHeadersFilter.requestIdFor(
                requestContext("api/notes", longId.toString()));
        assertEquals(SecurityHeadersFilter.MAX_REQUEST_ID_LENGTH, capped.length());
        for (char c : capped.toCharArray()) {
            assertTrue(c == '-' || c == '_' || c == '.' || Character.isLetterOrDigit(c));
        }
    }
}
