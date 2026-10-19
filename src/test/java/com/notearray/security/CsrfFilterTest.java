package com.notearray.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CsrfFilterTest {

    private static ContainerRequestContext requestContext(
            String method,
            Map<String, String> headers,
            AtomicReference<Response> abortedWith) {
        return (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[] { ContainerRequestContext.class },
                (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getMethod":
                            return method;
                        case "getHeaderString":
                            return headers.get((String) args[0]);
                        case "abortWith":
                            abortedWith.set((Response) args[0]);
                            return null;
                        default:
                            return null;
                    }
                });
    }

    @Test
    void allowsSafeGetRequestsEvenFromCrossSiteNavigation() throws IOException {
        AtomicReference<Response> aborted = new AtomicReference<>();
        CsrfFilter filter = new CsrfFilter();
        filter.filter(requestContext("GET", Map.of(
                "Sec-Fetch-Site", "cross-site",
                "Origin", "https://evil.example",
                "Host", "localhost:8080"), aborted));
        assertNull(aborted.get());
    }

    @Test
    void blocksCrossSiteSecFetchSiteOnPost() throws IOException {
        AtomicReference<Response> aborted = new AtomicReference<>();
        CsrfFilter filter = new CsrfFilter();
        filter.filter(requestContext("POST", Map.of(
                "Sec-Fetch-Site", "cross-site",
                "Host", "localhost:8080"), aborted));
        assertNotNull(aborted.get());
        assertEquals(403, aborted.get().getStatus());
    }

    @Test
    void blocksMismatchedOriginOnStateChangingRequests() throws IOException {
        AtomicReference<Response> aborted = new AtomicReference<>();
        CsrfFilter filter = new CsrfFilter();
        filter.filter(requestContext("DELETE", Map.of(
                "Origin", "https://evil.example",
                "Host", "notearray.example.com"), aborted));
        assertNotNull(aborted.get());
        assertEquals(403, aborted.get().getStatus());
    }

    @Test
    void blocksNullOriginOnPost() throws IOException {
        AtomicReference<Response> aborted = new AtomicReference<>();
        CsrfFilter filter = new CsrfFilter();
        filter.filter(requestContext("POST", Map.of(
                "Origin", "null",
                "Host", "localhost:8080"), aborted));
        assertNotNull(aborted.get());
        assertEquals(403, aborted.get().getStatus());
    }

    @Test
    void allowsSameOriginAndSameSiteDevProxyRequests() throws IOException {
        AtomicReference<Response> aborted = new AtomicReference<>();
        CsrfFilter filter = new CsrfFilter();
        filter.filter(requestContext("PUT", Map.of(
                "Sec-Fetch-Site", "same-site",
                "Origin", "http://localhost:3000",
                "Host", "localhost:8080"), aborted));
        assertNull(aborted.get());
    }

    @Test
    void allowsDirectNonBrowserRequestsWithoutOriginOrSecFetchSite() throws IOException {
        AtomicReference<Response> aborted = new AtomicReference<>();
        CsrfFilter filter = new CsrfFilter();
        filter.filter(requestContext("POST", Map.of("Host", "localhost:8080"), aborted));
        assertNull(aborted.get());
    }
}
