package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.notearray.service.StorageQuota;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class AttachmentQuotaTest {

    @Test
    void planStorageQuotasMatchConfiguredTiers() {
        assertEquals(100L * 1024 * 1024, StorageQuota.limitBytesForPlan("free"));
        assertEquals(1L * 1024 * 1024 * 1024, StorageQuota.limitBytesForPlan("pro"));
        assertEquals(5L * 1024 * 1024 * 1024, StorageQuota.limitBytesForPlan("premium"));
        assertEquals(100L * 1024 * 1024, StorageQuota.limitBytesForPlan(null));
    }

    @Test
    void planLimitLabelMatchesTheCallerTier() {
        // Overrun messages must show the caller's own plan limit, not a fixed value.
        assertEquals("100 MB", StorageQuota.limitLabelForPlan("free"));
        assertEquals("1 GB", StorageQuota.limitLabelForPlan("pro"));
        assertEquals("5 GB", StorageQuota.limitLabelForPlan("premium"));
        assertEquals("100 MB", StorageQuota.limitLabelForPlan(null));
    }

    @Test
    void quotaMessagesArePlanAgnostic() {
        // The same guard serves every tier, so the text must not name (or default
        // to) a tier and must point at upgrade-or-delete. A Pro user at their
        // own 1 GB limit is the failing case here.
        Response exceeded = StorageQuota.checkCanCreate(1L * 1024 * 1024 * 1024, 0L, "pro");
        assertNotNull(exceeded);
        String text = String.valueOf(exceeded.getEntity());
        assertTrue(text.contains("Please upgrade your plan or delete notes"));
        assertFalse(text.contains("Pro"));
        assertFalse(text.contains("Free"));
    }

    @Test
    void enforcesPlanStorageQuotas() {
        long ninetyNineMb = 99L * 1024 * 1024;
        long twoMb = 2L * 1024 * 1024;

        // Within Free 100 MB limit
        assertNull(StorageQuota.checkQuota(ninetyNineMb, 512 * 1024, "free"));

        // Exceeds Free 100 MB limit -> 413
        Response freeExceeded = StorageQuota.checkQuota(ninetyNineMb, twoMb, "free");
        assertNotNull(freeExceeded);
        assertEquals(413, freeExceeded.getStatus());

        // Same usage is well within Pro 1 GB limit
        assertNull(StorageQuota.checkQuota(ninetyNineMb, twoMb, "pro"));

        // Creating even a 0-byte note when already at/above the Free 100 MB limit is rejected
        Response createAtLimit = StorageQuota.checkCanCreate(100L * 1024 * 1024, 0L, "free");
        assertNotNull(createAtLimit);
        assertEquals(413, createAtLimit.getStatus());

        // Shrinking an existing note while over quota is still allowed so the user can recover space
        assertNull(StorageQuota.checkQuota(105L * 1024 * 1024, -1024L, "free"));
    }

    @Test
    void validatesSupportedImageAndPdfTypesAndRejectsExecutableTypes() {
        assertEquals("image/png", AttachmentResource.normalizeContentType("image/png", "photo.png"));
        assertEquals("image/jpeg", AttachmentResource.normalizeContentType("application/octet-stream", "photo.jpg"));
        assertEquals("application/pdf", AttachmentResource.normalizeContentType("application/pdf", "doc.pdf"));

        // SVG and HTML must not be accepted as attachments
        assertNull(AttachmentResource.normalizeContentType("image/svg+xml", "vector.svg"));
        assertNull(AttachmentResource.normalizeContentType("text/html", "index.html"));
        assertFalse(AttachmentResource.isAllowedContentType("image/svg+xml"));
        assertTrue(AttachmentResource.isAllowedContentType("application/pdf"));
    }
}
