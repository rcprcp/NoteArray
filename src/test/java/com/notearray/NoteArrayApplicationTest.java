package com.notearray;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import jakarta.ws.rs.ApplicationPath;
import org.junit.jupiter.api.Test;

import com.notearray.resource.NoteResource;
import com.notearray.resource.UserResource;
import com.notearray.security.SecurityHeadersFilter;

class NoteArrayApplicationTest {

    @Test
    void resourceClassesAreRegistered() {
        NoteArrayApplication app = new NoteArrayApplication();
        Set<Class<?>> classes = app.getClasses();

        assertTrue(classes.contains(UserResource.class),
            "UserResource should be registered in NoteArrayApplication");
        assertTrue(classes.contains(NoteResource.class),
            "NoteResource should be registered in NoteArrayApplication");
        assertTrue(classes.contains(SecurityHeadersFilter.class),
            "SecurityHeadersFilter should be registered in NoteArrayApplication");
    }

    @Test
    void applicationCanBeInstantiated() {
        assertDoesNotThrow(() -> new NoteArrayApplication());
    }
}