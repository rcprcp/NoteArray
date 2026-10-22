package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hierarchical folder tags on Joplin import: item-type filtering, folder
 * chains rendered as {@code /}-separated tags, segment sanitation and the
 * tags-column cap.
 */
class JoplinFolderTagsTest {

    // --- joplinItemMeta ------------------------------------------------------

    private static String noteFooter(String id, String parent, String type) {
        return "Some Content\n\nid: " + id + "\nparent_id: " + parent + "\ntype_: " + type + "\n";
    }

    @Test
    void parsesItemMetaFromFooter() {
        NoteResource.JoplinItemMeta meta = NoteResource.joplinItemMeta(
                noteFooter("055baababcae45ed98b0cea9ec3e570b", "3bedbe6e849a49a291b1e84e45374f08", "2"));
        assertEquals("055baababcae45ed98b0cea9ec3e570b", meta.id());
        assertEquals("2", meta.type());
        assertEquals("3bedbe6e849a49a291b1e84e45374f08", meta.parentId());
    }

    @Test
    void bareMarkdownHasNoMeta() {
        assertNull(NoteResource.joplinItemMeta("# Just a heading\n\nplain body text\n"));
        assertNull(NoteResource.joplinItemMeta(null));
        assertNull(NoteResource.joplinItemMeta(""));
    }

    @Test
    void incompleteFooterIsNotAnItem() {
        // id-only block (no type_) is not a recognized Joplin item footer.
        assertNull(NoteResource.joplinItemMeta("text\n\nid: 123\n"));
    }

    // --- sanitizeFolderSegment -------------------------------------------------

    @Test
    void whitespaceBecomesUnderscore() {
        assertEquals("screenshot_2023-12-12", NoteResource.sanitizeFolderSegment("Screenshot 2023-12-12"));
        assertEquals("groovy_-_make", NoteResource.sanitizeFolderSegment("Groovy -  make"));
        // Slashes are reserved for hierarchy, never part of a segment.
        assertEquals("a_b", NoteResource.sanitizeFolderSegment("A/B"));
    }

    @Test
    void unicodeLettersAndDigitsSurvive() {
        assertEquals("études", NoteResource.sanitizeFolderSegment("Études"));
        assertEquals("日本語_ノート", NoteResource.sanitizeFolderSegment("日本語 ノート"));
        assertEquals("groovy2", NoteResource.sanitizeFolderSegment("Groovy2"));
        assertEquals("", NoteResource.sanitizeFolderSegment("*?!"));
        assertEquals("", NoteResource.sanitizeFolderSegment(null));
    }

    // --- folderPathTag -----------------------------------------------------------

    @Test
    void buildsTheFullAncestorChain() {
        Map<String, String> titles = new HashMap<>();
        titles.put("root", "Work");
        titles.put("mid", "Projects");
        titles.put("leaf", "Acme");
        Map<String, String> parents = new HashMap<>();
        parents.put("mid", "root");
        parents.put("leaf", "mid");
        assertEquals("work/projects/acme", NoteResource.folderPathTag("leaf", titles, parents));
    }

    @Test
    void folderlessOrUnknownFolderYieldsNull() {
        assertNull(NoteResource.folderPathTag(null, Map.of(), Map.of()));
        assertNull(NoteResource.folderPathTag("", Map.of(), Map.of()));
        assertNull(NoteResource.folderPathTag("ghost", Map.of("root", "Work"), Map.of()));
    }

    @Test
    void cyclesAreBrokenNotLooped() {
        Map<String, String> titles = new HashMap<>();
        titles.put("a", "A");
        titles.put("b", "B");
        Map<String, String> parents = new HashMap<>();
        parents.put("a", "b");
        parents.put("b", "a");
        String tag = NoteResource.folderPathTag("b", titles, parents);
        assertTrue(tag.equals("a/b") || tag.equals("b/a"), "cycle must terminate: " + tag);
    }

    @Test
    void deepChainsKeepTheSegmentsClosestToTheNote() {
        Map<String, String> titles = new HashMap<>();
        Map<String, String> parents = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            titles.put("f" + i, "L" + i);
            parents.put("f" + i, i == 0 ? null : "f" + (i - 1));
        }
        parents.put("f0", "");
        String tag = NoteResource.folderPathTag("f29", titles, parents);
        assertEquals(NoteResource.MAX_FOLDER_DEPTH, tag.split("/").length);
        // Deepest kept, oldest dropped: starts at level (30 - 24).
        assertTrue(tag.startsWith("l6/"), tag);
        assertTrue(tag.endsWith("l29"), tag);
    }

    // --- buildJoplinTags ----------------------------------------------------------

    @Test
    void tagsFallBackAndJoin() {
        assertEquals("joplin-import", NoteResource.buildJoplinTags(null, ""));
        assertEquals("work/projects/acme", NoteResource.buildJoplinTags("work/projects/acme", null));
        assertEquals("work/projects/a b c", NoteResource.buildJoplinTags("work/projects/a", "b c"));
    }

    @Test
    void overlongTagsDropTheFolderPathFirst() {
        String hugeMeta = "m".repeat(11_000);
        assertEquals(NoteResource.MAX_TAGS_LENGTH, NoteResource.buildJoplinTags("work", hugeMeta).length());
        // The folder path, not the metadata tags, is sacrificed.
        assertTrue(!NoteResource.buildJoplinTags("work", hugeMeta).startsWith("work"));
    }
}
