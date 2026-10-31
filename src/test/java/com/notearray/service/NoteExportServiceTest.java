package com.notearray.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Naming, tag splitting and JSON shape for the user data export. */
class NoteExportServiceTest {

    private static final UUID NOTE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ATTACHMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // --- tags ---------------------------------------------------------------

    @Test
    void splitsSpaceDelimitedTags() {
        assertEquals(List.of("work", "work/project", "urgent"), NoteExportService.tagsOf("  work   work/project\turgent "));
    }

    @Test
    void emptyTagsBecomeEmptyList() {
        assertEquals(List.of(), NoteExportService.tagsOf(null));
        assertEquals(List.of(), NoteExportService.tagsOf(""));
        assertEquals(List.of(), NoteExportService.tagsOf("   "));
    }

    // --- archive entry names ------------------------------------------------

    @Test
    void noteEntryKeepsTitleAndUsesJsonSuffix() {
        String name = NoteExportService.noteEntryName(NOTE_ID, "Quarterly Report");
        assertEquals("notes/" + NOTE_ID + "-Quarterly-Report.json", name);
    }

    @Test
    void noteEntryFallsBackForBlankTitleAndDoesNotDoubleJson() {
        assertTrue(NoteExportService.noteEntryName(NOTE_ID, "   ").endsWith("-untitled.json"));
        assertEquals("notes/" + NOTE_ID + "-backup.json",
                NoteExportService.noteEntryName(NOTE_ID, "backup.json"));
    }

    @Test
    void noteEntryStripsPathSeparators() {
        String name = NoteExportService.noteEntryName(NOTE_ID, "../../etc/passwd");
        assertEquals("notes/" + NOTE_ID + "-etc-passwd.json", name);
    }

    @Test
    void noteEntryKeepsUnicodeLetters() {
        assertEquals("notes/" + NOTE_ID + "-Café-日本語.json",
                NoteExportService.noteEntryName(NOTE_ID, "Café 日本語"));
    }

    @Test
    void attachmentEntryKeepsFilenameAndIsUniqueById() {
        String name = NoteExportService.attachmentEntryName(ATTACHMENT_ID, "photo.png");
        assertEquals("attachments/" + ATTACHMENT_ID + "-photo.png", name);
    }

    @Test
    void attachmentEntrySanitizesUnsafeFilename() {
        String name = NoteExportService.attachmentEntryName(ATTACHMENT_ID, "..\\\\..\\\\evil.png");
        assertEquals("attachments/" + ATTACHMENT_ID + "-evil.png", name);
        assertEquals("attachments/" + ATTACHMENT_ID + "-attachment",
                NoteExportService.attachmentEntryName(ATTACHMENT_ID, null));
    }

    @Test
    void longNamesAreTruncated() {
        String name = NoteExportService.sanitizeName("a".repeat(200), "fallback");
        assertEquals(NoteExportService.MAX_NAME_SEGMENT_LENGTH, name.length());
    }

    // --- download filename ---------------------------------------------------

    @Test
    void exportFilenameUsesUserAndUtcTimestamp() {
        Instant now = Instant.parse("2026-10-31T15:30:45Z");
        assertEquals("Jane-Doe-20261031-153045-export.zip",
                NoteExportService.exportFilename("Jane Doe", now));
    }

    @Test
    void exportFilenameSanitizesUserAndFallsBackWhenBlank() {
        Instant now = Instant.parse("2026-10-31T15:30:45Z");
        assertEquals("etc-passwd-20261031-153045-export.zip",
                NoteExportService.exportFilename("../../etc/passwd", now));
        assertEquals("user-20261031-153045-export.zip",
                NoteExportService.exportFilename("   ", now));
    }

    // --- JSON shape ---------------------------------------------------------

    @Test
    void noteJsonHasTheDocumentedFields() throws Exception {
        NoteExportService.ExportNote note = new NoteExportService.ExportNote(
                "Title",
                List.of("one", "two"),
                true,
                List.of("attachments/" + ATTACHMENT_ID + "-file.pdf"),
                "Body");
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(note));

        assertEquals("Title", json.get("title").asText());
        assertEquals("one", json.get("tags").get(0).asText());
        assertEquals("two", json.get("tags").get(1).asText());
        assertTrue(json.get("pinned").asBoolean());
        assertEquals("attachments/" + ATTACHMENT_ID + "-file.pdf",
                json.get("attachments").get(0).asText());
        assertEquals("Body", json.get("content").asText());
    }

    @Test
    void emptyFieldsSerializeAsEmptyArraysAndString() throws Exception {
        NoteExportService.ExportNote note = new NoteExportService.ExportNote("", List.of(), false, List.of(), "");
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(note));

        assertTrue(json.get("tags").isArray());
        assertEquals(0, json.get("tags").size());
        assertFalse(json.get("pinned").asBoolean());
        assertTrue(json.get("attachments").isArray());
        assertEquals(0, json.get("attachments").size());
        assertEquals("", json.get("content").asText());
    }
}
