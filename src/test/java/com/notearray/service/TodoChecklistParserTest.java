package com.notearray.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Markdown checklist parsing for "create TODO list from note". */
class TodoChecklistParserTest {

    @Test
    void parsesCheckedAndUncheckedItems() {
        List<TodoChecklistParser.ParsedItem> items = TodoChecklistParser.parse("""
                # Shopping
                - [ ] Buy milk
                - [x] Walk the dog
                * [X] Pay rent
                """);

        assertEquals(3, items.size());
        assertEquals("Buy milk", items.get(0).text());
        assertFalse(items.get(0).checked());
        assertTrue(items.get(1).checked());
        assertTrue(items.get(2).checked());
    }

    @Test
    void usesIndentationForNesting() {
        List<TodoChecklistParser.ParsedItem> items = TodoChecklistParser.parse("""
                - [ ] Parent
                  - [ ] Child
                    - [x] Grandchild
                - [ ] Second parent
                """);

        assertEquals(List.of(0, 1, 2, 0), items.stream().map(TodoChecklistParser.ParsedItem::depth).toList());
        assertEquals("Grandchild", items.get(2).text());
    }

    @Test
    void clampsDepthJumpsAndHandlesTabs() {
        List<TodoChecklistParser.ParsedItem> items = TodoChecklistParser.parse("""
                - [ ] Root
                \t- [ ] Tab child
                \t\t\t- [ ] Too deep
                """);

        assertEquals(List.of(0, 1, 2), items.stream().map(TodoChecklistParser.ParsedItem::depth).toList());
    }

    @Test
    void ignoresProseAndBlankInput() {
        assertTrue(TodoChecklistParser.parse(null).isEmpty());
        assertTrue(TodoChecklistParser.parse("").isEmpty());
        assertTrue(TodoChecklistParser.parse("Just a normal note.\n\n- not a checkbox").isEmpty());
    }

    @Test
    void truncatesOverlongItemText() {
        String longText = "x".repeat(TodoChecklistParser.MAX_TEXT_LENGTH + 10);
        List<TodoChecklistParser.ParsedItem> items = TodoChecklistParser.parse("- [ ] " + longText);
        assertEquals(TodoChecklistParser.MAX_TEXT_LENGTH, items.get(0).text().length());
    }
}
