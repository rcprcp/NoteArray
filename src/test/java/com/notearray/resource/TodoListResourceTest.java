package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.notearray.entity.TodoItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure helpers behind the TODO list API. */
class TodoListResourceTest {

    @Test
    void normalizesPriorities() {
        assertEquals("NONE", TodoListResource.normalizePriority(null));
        assertEquals("NONE", TodoListResource.normalizePriority("  "));
        assertEquals("HIGH", TodoListResource.normalizePriority("high"));
        assertEquals("MEDIUM", TodoListResource.normalizePriority(" Medium "));
        assertNull(TodoListResource.normalizePriority("urgent"));
    }

    @Test
    void parsesIsoDueDatesOnly() {
        assertEquals(LocalDate.of(2026, 11, 1), TodoListResource.parseDueDate("2026-11-01"));
        assertNull(TodoListResource.parseDueDate(""));
        assertNull(TodoListResource.parseDueDate("next Tuesday"));
    }

    @Test
    void normalizesAndCapsTitles() {
        assertEquals("Groceries", TodoListResource.normalizeTitle("  Groceries  "));
        assertNull(TodoListResource.normalizeTitle("   "));
        assertEquals(255, TodoListResource.normalizeTitle("x".repeat(400)).length());
    }

    @Test
    void flattensItemsInTreeOrder() {
        UUID rootA = UUID.randomUUID();
        UUID childA1 = UUID.randomUUID();
        UUID childA2 = UUID.randomUUID();
        UUID rootB = UUID.randomUUID();

        TodoItem a = item(rootA, null, 0);
        TodoItem a1 = item(childA1, rootA, 1);
        TodoItem a2 = item(childA2, rootA, 0);
        TodoItem b = item(rootB, null, 1);

        List<TodoListResource.TodoItemView> ordered =
                TodoListResource.treeOrder(List.of(b, a2, a, a1));

        assertEquals(List.of(rootA, childA2, childA1, rootB),
                ordered.stream().map(TodoListResource.TodoItemView::id).toList());
        assertEquals(rootA, ordered.get(1).parentId());
    }

    private static TodoItem item(UUID id, UUID parentId, int position) {
        TodoItem item = new TodoItem();
        item.id = id;
        item.parentId = parentId;
        item.position = position;
        item.text = "item";
        item.createdAt = Instant.now();
        item.updatedAt = item.createdAt;
        return item;
    }
}
