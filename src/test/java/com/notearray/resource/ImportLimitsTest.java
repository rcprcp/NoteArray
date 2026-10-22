package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.notearray.service.StorageQuota;
import jakarta.ws.rs.WebApplicationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * REVIEW finding #5: import resource bounds (zip bombs, oversized imports,
 * plan-quota enforcement, config wiring).
 */
class ImportLimitsTest {

    // --- ExpansionBudget ---------------------------------------------------

    @Test
    void expansionBudgetAcceptsEntriesWithinLimit() throws IOException {
        NoteResource.ExpansionBudget budget = new NoteResource.ExpansionBudget(1000);
        budget.add(600);
        budget.add(400);
        assertEquals(1000, budget.used());
    }

    @Test
    void expansionBudgetAbortsWhenCumulativeExpansionExceedsLimit() {
        NoteResource.ExpansionBudget budget = new NoteResource.ExpansionBudget(1000);
        budget.add(600);
        // Second entry pushes the cumulative total past the budget: whole import aborts.
        assertThrows(WebApplicationException.class, () -> budget.add(600));
    }

    @Test
    void expansionBudgetRejectsExactlyExceededByte() {
        NoteResource.ExpansionBudget budget = new NoteResource.ExpansionBudget(1000);
        budget.add(1000);
        // A single byte over the budget is already a bomb signature.
        assertThrows(WebApplicationException.class, () -> budget.add(1));
    }

    @Test
    void importLimitExceededMapsTo413() {
        WebApplicationException ex = NoteResource.importLimitExceeded("too big");
        assertEquals(413, ex.getResponse().getStatus());
    }

    // --- Plan-quota enforcement during import -------------------------------

    @Test
    void quotaWouldExceedDetectsOvershoot() {
        long limit = StorageQuota.FREE_LIMIT_BYTES;
        long used = limit - 100;
        long imported = 60;
        // 40 bytes of room left: a 40-byte note fits exactly, 41 overshoots.
        assertFalse(NoteResource.quotaWouldExceed(used, imported, 40, limit));
        assertTrue(NoteResource.quotaWouldExceed(used, imported, 41, limit));
    }

    @Test
    void quotaWouldExceedAllowsExactFitAndZeroByteNotes() {
        long limit = StorageQuota.FREE_LIMIT_BYTES;
        // A 0-byte note adds nothing (the "already at limit" case is caught by
        // the checkCanCreate preflight before the import loop).
        assertFalse(NoteResource.quotaWouldExceed(limit, 0, 0, limit));
        // Adding the exact remaining room fits; one byte over does not.
        assertFalse(NoteResource.quotaWouldExceed(limit - 1, 0, 1, limit));
        assertTrue(NoteResource.quotaWouldExceed(limit - 1, 0, 2, limit));
    }

    @Test
    void quotaWouldExceedTreatsNegativeSizesAsZero() {
        long limit = 1000;
        assertFalse(NoteResource.quotaWouldExceed(900, 0, -500, limit));
    }

    // --- Config wiring ------------------------------------------------------

    private static Properties loadProps() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("application.properties")) {
            props.load(in);
        }
        return props;
    }

    @Test
    void importLimitPropertiesAreConfigured() throws IOException {
        Properties props = loadProps();
        // Archive stays "fairly large" (50 MB) so imports can leverage plan storage;
        // the bomb defenses are the expansion budget and the entry cap.
        assertEquals("52428800", props.getProperty("notearray.import.max-archive-bytes"));
        assertEquals("268435456", props.getProperty("notearray.import.max-total-expansion-bytes"));
        assertEquals("10000", props.getProperty("notearray.import.max-entries"));
        assertEquals(50 * 1024 * 1024, NoteResource.DEFAULT_MAX_ARCHIVE_BYTES);
        assertEquals(256L * 1024 * 1024, NoteResource.DEFAULT_MAX_TOTAL_EXPANSION_BYTES);
        assertEquals(10_000, NoteResource.DEFAULT_MAX_IMPORT_ENTRIES);
    }
}
