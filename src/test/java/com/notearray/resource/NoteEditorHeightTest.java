package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.notearray.entity.Note;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

class NoteEditorHeightTest {

    @Test
    void clampsRequestedHeightsIntoTheSupportedRange() {
        assertNull(NoteResource.normalizeEditorHeight(null));
        assertEquals(160, NoteResource.normalizeEditorHeight(1));
        assertEquals(160, NoteResource.normalizeEditorHeight(160));
        assertEquals(500, NoteResource.normalizeEditorHeight(500));
        assertEquals(1200, NoteResource.normalizeEditorHeight(1200));
        assertEquals(1200, NoteResource.normalizeEditorHeight(99999));
    }

    @Test
    void editorHeightIsNeverWrittenByEntityFlushes() throws Exception {
        // The dedicated native UPDATE is the only writer: a normal note save
        // (auto-save) must not carry the cosmetic height or bump the version.
        Column column = Note.class.getField("editorHeight").getAnnotation(Column.class);
        assertEquals("editor_height", column.name());
        assertFalse(column.updatable());
    }
}
