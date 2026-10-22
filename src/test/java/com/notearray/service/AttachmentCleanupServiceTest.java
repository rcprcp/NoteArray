package com.notearray.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Orphan detection for attachments: which links a note's content points at, and
 * which attachments therefore need to be marked (or kept alive).
 */
class AttachmentCleanupServiceTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static String url(UUID id) {
        return "/api/attachments/" + id;
    }

    // --- referencedAttachmentIds ---------------------------------------------

    @Test
    void findsEveryLinkForm() {
        String content = String.join("\n",
                "![screenshot](" + url(A) + ")",
                "[report](" + url(A) + "?download=1)",
                "See <a href=\"" + url(B) + "\">the file</a> and",
                "<img src=\"http://localhost:8080" + url(B) + "\" />",
                "an absolute link: https://notes.example.com" + url(A) + "?download=1");
        assertEquals(Set.of(A, B), AttachmentCleanupService.referencedAttachmentIds(content));
    }

    @Test
    void ignoresNonAttachmentLinksAndEmptyContent() {
        String content = "plain text, [a link](https://example.com), /api/attachments/not-a-uuid";
        assertTrue(AttachmentCleanupService.referencedAttachmentIds(content).isEmpty());
        assertTrue(AttachmentCleanupService.referencedAttachmentIds(null).isEmpty());
        assertTrue(AttachmentCleanupService.referencedAttachmentIds("").isEmpty());
    }

    @Test
    void acceptsUppercaseIds() {
        String content = "![" + "x" + "](https://host/api/attachments/"
                + A.toString().toUpperCase() + ")";
        assertEquals(Set.of(A), AttachmentCleanupService.referencedAttachmentIds(content));
    }

    // --- idsToMarkOrphaned ---------------------------------------------------

    @Test
    void marksOnlyAttachmentsNothingReferences() {
        Set<UUID> referencedInNote = Set.of(A);
        Set<UUID> elsewhere = Set.of(B);
        Set<UUID> marked = AttachmentCleanupService.idsToMarkOrphaned(
                Set.of(A, B), referencedInNote, elsewhere::contains);
        assertTrue(marked.isEmpty(), "linked in this note or another one → keep");
    }

    @Test
    void marksAttachmentsNoNoteLinksAnyMore() {
        UUID orphan = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Set<UUID> marked = AttachmentCleanupService.idsToMarkOrphaned(
                new HashSet<>(Set.of(A, orphan)), Set.of(A), id -> false);
        assertEquals(Set.of(orphan), marked);
    }

    @Test
    void sharedAttachmentSurvivesOneNoteDroppingIt() {
        // A is uploaded on this note but a second note still links it.
        Set<UUID> marked = AttachmentCleanupService.idsToMarkOrphaned(
                Set.of(A), Set.of(), id -> true);
        assertFalse(marked.contains(A));
    }
}
