package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.notearray.entity.Note;
import com.notearray.resource.NoteResource.NoteRequest;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class NoteVersionConflictTest {

    @Test
    void allowsUpdateWhenVersionMatches() {
        Note note = new Note();
        note.version = 3L;
        NoteRequest req = new NoteRequest("Title", "Content", "tags", false, 3L);

        assertNull(NoteResource.checkVersionConflict(note, req));
    }

    @Test
    void allowsUpdateWhenVersionIsOmitted() {
        Note note = new Note();
        note.version = 5L;
        NoteRequest req = new NoteRequest(null, null, null, true, null);

        assertNull(NoteResource.checkVersionConflict(note, req));
    }

    @Test
    void returns409ConflictWhenVersionIsStale() {
        Note note = new Note();
        note.version = 4L;
        NoteRequest req = new NoteRequest("Title", "Edited on device A", "tags", false, 3L);

        Response conflict = NoteResource.checkVersionConflict(note, req);
        assertNotNull(conflict);
        assertEquals(409, conflict.getStatus());
        assertEquals("Note was modified in another session; please reload", conflict.getEntity());
    }
}
