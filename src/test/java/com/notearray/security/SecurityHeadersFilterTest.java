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
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Properties;
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
    void setsRequestIdAndLeavesSecurityHeadersToGlobalConfig() throws IOException {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        new SecurityHeadersFilter().filter(requestContextWithPath("api/notes"), responseContextWithHeaders(headers));

        assertNotNull(headers.getFirst(SecurityHeadersFilter.REQUEST_ID_HEADER));

        // These are owned by quarkus.http.header.* in application.properties. Setting
        // them here too would emit duplicates on REST responses and would not reach the
        // static SPA document, because a JAX-RS filter does not run for static resources.
        assertNull(headers.getFirst("Content-Security-Policy"));
        assertNull(headers.getFirst("X-Frame-Options"));
        assertNull(headers.getFirst("X-Content-Type-Options"));
        assertNull(headers.getFirst("Referrer-Policy"));
        assertNull(headers.getFirst("Permissions-Policy"));
        assertNull(headers.getFirst("Strict-Transport-Security"));
    }

    @Test
    void applicationPropertiesDeclareGlobalSecurityHeaders() throws IOException {
        Properties props = new Properties();
        try (InputStream in = SecurityHeadersFilterTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties must be on the test classpath");
            props.load(in);
        }

        assertEquals("nosniff", props.getProperty("quarkus.http.header.\"X-Content-Type-Options\".value"));
        assertEquals("SAMEORIGIN", props.getProperty("quarkus.http.header.\"X-Frame-Options\".value"));
        assertEquals("strict-origin-when-cross-origin",
                props.getProperty("quarkus.http.header.\"Referrer-Policy\".value"));
        assertEquals("camera=(), microphone=(), geolocation=(), payment=()",
                props.getProperty("quarkus.http.header.\"Permissions-Policy\".value"));

        // Global CSP is what actually reaches the static SPA document: a JAX-RS filter
        // would not. frame-ancestors must be 'self' (not 'none') so the attachment
        // preview modal can still frame same-origin files.
        String csp = props.getProperty("quarkus.http.header.\"Content-Security-Policy\".value");
        assertNotNull(csp, "CSP must be set globally so the static SPA document is covered");
        assertTrue(csp.contains("default-src 'self'"));
        assertTrue(csp.contains("script-src 'self';"));
        assertFalse(csp.contains("script-src 'self' 'unsafe-inline'"));
        assertTrue(csp.contains("frame-ancestors 'self'"));
        assertFalse(csp.contains("frame-ancestors 'none'"));
        assertTrue(csp.contains("object-src 'none'"));

        assertEquals("max-age=31536000; includeSubDomains; preload",
                props.getProperty("%prod.quarkus.http.header.\"Strict-Transport-Security\".value"));
        assertEquals("false", props.getProperty("%prod.quarkus.smallrye-health.enabled"),
                "health endpoints expose DB status and must be off in prod");
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
        new SecurityHeadersFilter().filter(null, responseContextWithHeaders(headers));
        Object generated = headers.getFirst(SecurityHeadersFilter.REQUEST_ID_HEADER);
        assertNotNull(generated, "X-Request-Id must always be emitted");
        assertTrue(((String) generated).matches("[A-Za-z0-9._-]{18}"),
                "generated id should be 18 url-safe chars, was: " + generated);

        // Same for a request context with no X-Request-Id header.
        MultivaluedMap<String, Object> headers2 = new MultivaluedHashMap<>();
        new SecurityHeadersFilter()
                .filter(requestContextWithPath("api/notes"), responseContextWithHeaders(headers2));
        assertNotNull(headers2.getFirst(SecurityHeadersFilter.REQUEST_ID_HEADER));
    }

    @Test
    void echoesCallerRequestIdWhenWellFormed() throws IOException {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        new SecurityHeadersFilter().filter(
                requestContext("api/notes", "req_abc123.def-456"),
                responseContextWithHeaders(headers));
        assertEquals("req_abc123.def-456", headers.getFirst(SecurityHeadersFilter.REQUEST_ID_HEADER));
    }

    @Test
    void sanitizesMaliciousRequestId_untrustedHeaderCannotInjectOrForge() throws IOException {
        // CR/LF header-injection attempt: the reported value must be a single,
        // injection-free token.
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        new SecurityHeadersFilter().filter(
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
