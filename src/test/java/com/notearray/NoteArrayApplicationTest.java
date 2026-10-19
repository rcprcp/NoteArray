package com.notearray;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.notearray.resource.NoteResource;
import com.notearray.resource.SpaResource;
import com.notearray.resource.UserResource;
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

        assertTrue(classes.contains(UserResource.class),
            "UserResource should be registered in NoteArrayApplication");
        assertTrue(classes.contains(NoteResource.class),
            "NoteResource should be registered in NoteArrayApplication");
        assertTrue(classes.contains(SpaResource.class),
            "SpaResource should be registered in NoteArrayApplication");
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
}
