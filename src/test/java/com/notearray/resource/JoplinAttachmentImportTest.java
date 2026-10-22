package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.notearray.entity.NoteAttachment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Joplin resource-import parsing/naming/rewriting (attachments embedded in
 * JEX notes via {@code [name.ext](:/<resourceId>)} links).
 */
class JoplinAttachmentImportTest {

    private static NoteAttachment attachment(String id, String contentType, String filename) {
        NoteAttachment a = new NoteAttachment();
        a.id = java.util.UUID.nameUUIDFromBytes(id.getBytes());
        a.contentType = contentType;
        a.filename = filename;
        return a;
    }

    // --- parseJoplinResourceRefs --------------------------------------------

    @Test
    void parsesFileAndImageRefs() {
        String content = "See [gpu-optimizer-camera-ready.pdf](:/234939b91ed17634d41da948bbc697b6) "
                + "and ![CIDR_ranges.png](:/E959BC341C674F96822A9324144DB328)!";
        List<NoteResource.JexRef> refs = NoteResource.parseJoplinResourceRefs(content);
        assertEquals(2, refs.size());
        NoteResource.JexRef file = refs.get(0);
        assertEquals(false, file.image());
        assertEquals("gpu-optimizer-camera-ready.pdf", file.linkText());
        assertEquals("234939b91ed17634d41da948bbc697b6", file.resourceId());
        NoteResource.JexRef image = refs.get(1);
        assertTrue(image.image());
        assertEquals("e959bc341c674f96822a9324144db328", image.resourceId());
        assertEquals("CIDR_ranges.png", image.linkText());
    }

    @Test
    void parsesEmptyTextImageRefs() {
        List<NoteResource.JexRef> refs = NoteResource.parseJoplinResourceRefs(
                "[](:/05b2890f6ada1b422f80a370486bd3dc) empty-text link, ![shot](:/ce7f0acb76a4f99e57b7f1c7b6de8e23)");
        assertEquals(2, refs.size());
        assertEquals("05b2890f6ada1b422f80a370486bd3dc", refs.get(0).resourceId());
        assertEquals("", refs.get(0).linkText());
        assertTrue(refs.get(1).image());
    }

    @Test
    void ignoresNonJoplinLinks() {
        // Ordinary URLs and links to sibling notes must not match the 32-hex resource id.
        assertEquals(0, NoteResource.parseJoplinResourceRefs(
                "[docs](https://joplinapp.org) [bug](:/tooshort) [x](:/234939b91ed17634d41da948bbc697bzz) []()").size());
        assertEquals(List.of(), NoteResource.parseJoplinResourceRefs(null));
        assertEquals(List.of(), NoteResource.parseJoplinResourceRefs(""));
    }

    // --- joplinAttachmentFileName --------------------------------------------

    @Test
    void fileNameComesFromTheLinkText() {
        NoteResource.JexRef ref = new NoteResource.JexRef(
                false, "gpu-optimizer-camera-ready.pdf", "234939b91ed17634d41da948bbc697b6", null);
        assertEquals("gpu-optimizer-camera-ready.pdf", NoteResource.joplinAttachmentFileName(ref, "pdf"));
    }

    @Test
    void blankLinkTextFallsBackToResourceWithExtension() {
        NoteResource.JexRef ref = new NoteResource.JexRef(
                true, "", "e959bc341c674f96822a9324144db328", null);
        assertEquals("e959bc341c674f96822a9324144db328.png", NoteResource.joplinAttachmentFileName(ref, "png"));
    }

    @Test
    void fileNameIsSanitizedAndCapped() {
        NoteResource.JexRef ref = new NoteResource.JexRef(false, "bad:name\r\n排/新", "id", null);
        assertEquals("bad_name__排_新", NoteResource.joplinAttachmentFileName(ref, "txt"));
        // Overlong names keep their tail (extension) after the 255-char cap.
        String longName = "x".repeat(300) + ".pdf";
        NoteResource.JexRef longRef = new NoteResource.JexRef(false, longName, "id", null);
        String capped = NoteResource.joplinAttachmentFileName(longRef, "pdf");
        assertEquals(255, capped.length());
        assertTrue(capped.endsWith(".pdf"));
    }

    // --- contentTypeForExtension ----------------------------------------------

    @Test
    void contentTypeFromExtension() {
        assertEquals("image/png", NoteResource.contentTypeForExtension("png"));
        assertEquals("image/jpeg", NoteResource.contentTypeForExtension("JPG"));
        assertEquals("application/pdf", NoteResource.contentTypeForExtension("pdf"));
        // Non-whitelisted types (legacy data: configs, sources, licenses) are plain downloads.
        assertEquals("application/octet-stream", NoteResource.contentTypeForExtension("json"));
        assertEquals("application/octet-stream", NoteResource.contentTypeForExtension("java"));
        assertEquals("application/octet-stream", NoteResource.contentTypeForExtension(""));
    }

    // --- rewriteJoplinResourceLinks --------------------------------------------

    @Test
    void rewritesFileLinksForDownload() {
        String content = "Read [gpu-optimizer-camera-ready.pdf](:/234939b91ed17634d41da948bbc697b6) now.";
        Map<String, NoteAttachment> byId = new HashMap<>();
        byId.put("234939b91ed17634d41da948bbc697b6",
                attachment("one", "application/pdf", "gpu-optimizer-camera-ready.pdf"));
        String out = NoteResource.rewriteJoplinResourceLinks(content, byId);
        String url = "/api/attachments/" + byId.get("234939b91ed17634d41da948bbc697b6").id;
        assertEquals("Read [gpu-optimizer-camera-ready.pdf](" + url + "?download=1) now.", out);
    }

    @Test
    void rewritesImageLinksForInlineRendering() {
        String content = "![CIDR_ranges.png](:/e959bc341c674f96822a9324144db328)";
        Map<String, NoteAttachment> byId = new HashMap<>();
        byId.put("e959bc341c674f96822a9324144db328",
                attachment("two", "image/png", "e959bc341c674f96822a9324144db328.png"));
        String url = "/api/attachments/" + byId.get("e959bc341c674f96822a9324144db328").id;
        assertEquals("![CIDR_ranges.png](" + url + ")", NoteResource.rewriteJoplinResourceLinks(content, byId));
    }

    @Test
    void emptyTextFileLinkUsesFilenameAsLabel() {
        String content = "[](:/580009b8458e8d614ca11b0ee697f7fb)";
        Map<String, NoteAttachment> byId = new HashMap<>();
        byId.put("580009b8458e8d614ca11b0ee697f7fb", attachment("three", "application/octet-stream", "pom.xml"));
        String url = "/api/attachments/" + byId.get("580009b8458e8d614ca11b0ee697f7fb").id;
        assertEquals("[pom.xml](" + url + "?download=1)", NoteResource.rewriteJoplinResourceLinks(content, byId));
    }

    @Test
    void missingResourcesKeepTheirOriginalLinks() {
        String content = "Stay [gold.pdf](:/f19fa26214764d0d9392bd48f9f2d0ff) untouched";
        assertEquals(content, NoteResource.rewriteJoplinResourceLinks(content, Map.of()));
    }

    @Test
    void sameResourceTwiceRewritesToTheSameUrl() {
        String content = "[a.json](:/40ec77c9bec55f9ab41b5cd20d5d8e8c) and again [test jython - Java.json](:/40ec77c9bec55f9ab41b5cd20d5d8e8c)";
        Map<String, NoteAttachment> byId = new HashMap<>();
        byId.put("40ec77c9bec55f9ab41b5cd20d5d8e8c", attachment("four", "application/octet-stream", "a.json"));
        String url = "/api/attachments/" + byId.get("40ec77c9bec55f9ab41b5cd20d5d8e8c").id;
        assertEquals("[a.json](" + url + "?download=1) and again [test jython - Java.json](" + url + "?download=1)",
                NoteResource.rewriteJoplinResourceLinks(content, byId));
    }

    @Test
    void dollarSignsInLabelsAndTextArePreservedVerbatim() {
        String content = "$100 [cost$a.md](:/234939b91ed17634d41da948bbc697b6) cost$a";
        Map<String, NoteAttachment> byId = new HashMap<>();
        byId.put("234939b91ed17634d41da948bbc697b6", attachment("five", "application/octet-stream", "cost$a.md"));
        String url = "/api/attachments/" + byId.get("234939b91ed17634d41da948bbc697b6").id;
        assertEquals("$100 [cost$a.md](" + url + "?download=1) cost$a",
                NoteResource.rewriteJoplinResourceLinks(content, byId));
    }

    @Test
    void jexResourceFileNameHelpers() {
        assertEquals("234939b91ed17634d41da948bbc697b6",
                NoteResource.JexResource.resourceIdOf("resources/234939b91ed17634d41da948bbc697b6.pdf"));
        assertEquals("pdf", NoteResource.JexResource.extensionOf("resources/234939b91ed17634d41da948bbc697b6.pdf"));
        // Windows-style archive names and extensionless entries still resolve.
        assertEquals("abc", NoteResource.JexResource.resourceIdOf("./resources/abc"));
        assertEquals("", NoteResource.JexResource.extensionOf("./resources/abc"));
    }
}
