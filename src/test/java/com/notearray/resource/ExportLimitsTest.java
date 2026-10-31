package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 503 messaging when the system-wide export permits are exhausted. */
class ExportLimitsTest {

    @Test
    void userWithoutAnExportIsToldTheLimitIsSystemWide() {
        assertEquals("system wide concurrent export limit has been reached, try again in a moment.",
                NoteResource.exportBusyMessage(false));
    }

    @Test
    void userWithAnInFlightExportIsToldAboutTheirOwnExport() {
        assertEquals("You already have a note export in progress; try again in a moment.",
                NoteResource.exportBusyMessage(true));
    }
}
