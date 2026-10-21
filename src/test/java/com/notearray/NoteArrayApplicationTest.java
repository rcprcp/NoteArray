package com.notearray;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.notearray.resource.AttachmentResource;
import com.notearray.resource.NoteResource;
import com.notearray.resource.SpaResource;
import com.notearray.resource.UserResource;
import com.notearray.security.CsrfFilter;
import com.notearray.security.SecurityHeadersFilter;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NoteArrayApplicationTest {

    @Test
    void resourceClassesAreRegistered() {
        NoteArrayApplication app = new NoteArrayApplication();
        Set<Class<?>> classes = app.getClasses();

        assertTrue(classes.contains(AttachmentResource.class),
            "AttachmentResource should be registered in NoteArrayApplication");
        assertTrue(classes.contains(UserResource.class),
            "UserResource should be registered in NoteArrayApplication");
        assertTrue(classes.contains(NoteResource.class),
            "NoteResource should be registered in NoteArrayApplication");
        assertTrue(classes.contains(SpaResource.class),
            "SpaResource should be registered in NoteArrayApplication");
        assertTrue(classes.contains(CsrfFilter.class),
            "CsrfFilter should be registered in NoteArrayApplication");
        assertTrue(classes.contains(SecurityHeadersFilter.class),
            "SecurityHeadersFilter should be registered in NoteArrayApplication");
    }

    @Test
    void unauthenticatedRouteRedirectsToLogin() {
        SpaResource spa = new SpaResource();
        Response response = spa.route("overview");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals(URI.create("/login"), response.getLocation());
    }

    @Test
    void applicationCanBeInstantiated() {
        assertDoesNotThrow(() -> new NoteArrayApplication());
    }

    @Test
    void prodDisablesSwaggerOpenApiAndPrometheusMetrics() throws java.io.IOException {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("application.properties")) {
            props.load(in);
        }
        assertEquals(null, props.getProperty("quarkus.swagger-ui.always-include"));
        assertEquals("false", props.getProperty("%prod.quarkus.swagger-ui.enable"));
        assertEquals("false", props.getProperty("%prod.quarkus.smallrye-openapi.enable"));
        assertEquals("false", props.getProperty("%prod.quarkus.micrometer.export.prometheus.enabled"));
    }

    /**
     * REVIEW finding #23: prod config hygiene.
     */
    @Test
    void prodConfigHygiene() throws java.io.IOException {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("application.properties")) {
            props.load(in);
        }

        // Removed stale keys: Quarkus logged them as "unrecognized", and SameSite is
        // enforced by the Set-Cookie header built in SessionService.
        assertNull(props.getProperty("quarkus.http.cookie.same-site-cookie"));
        assertNull(props.getProperty("quarkus.http.cookie.same-site-cookie.value"));

        // %prod datasource values must come from env vars WITHOUT dev fallbacks:
        // the property exists but expands only ${NOTEARRAY_DB_*} with no default.
        String prodUrl = props.getProperty("%prod.quarkus.datasource.jdbc.url");
        assertEquals("${NOTEARRAY_DB_URL}", prodUrl,
                "%prod jdbc url must require NOTEARRAY_DB_URL (no silent dev default)");
        assertEquals("${NOTEARRAY_DB_USERNAME}", props.getProperty("%prod.quarkus.datasource.username"));
        assertEquals("${NOTEARRAY_DB_PASSWORD}", props.getProperty("%prod.quarkus.datasource.password"));

        // App-level logging is DEBUG only in dev; no global DEBUG in prod.
        assertNull(props.getProperty("quarkus.log.category.\"com.notearray\".level"),
                "global com.notearray DEBUG must not remain (prod)");
        assertEquals("DEBUG", props.getProperty("%dev.quarkus.log.category.\"com.notearray\".level"));
        assertEquals("INFO", props.getProperty("quarkus.log.level"));

        // Access log + request id correlation.
        assertEquals("true", props.getProperty("quarkus.http.access-log.enabled"));
        String pattern = props.getProperty("quarkus.http.access-log.pattern");
        assertTrue(pattern != null && pattern.contains("X-Request-Id"),
                "access log should include X-Request-Id");
    }
}
