package com.notearray.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One item in a {@link TodoList}. Items may be nested through {@code parentId};
 * siblings are ordered by {@code position} (stored as {@code sort_order}).
 * Deleting a parent cascades to its children at the database level.
 */
@Entity
@Table(name = "todo_items")
public class TodoItem extends BaseEntity {

    @Column(name = "list_id", nullable = false)
    public UUID listId;

    @Column(name = "parent_id")
    public UUID parentId;

    @Column(name = "body", nullable = false, length = 2000)
    public String text = "";

    @Column(name = "checked", nullable = false)
    public boolean checked = false;

    @Column(name = "sort_order", nullable = false)
    public int position = 0;

    /** NONE, LOW, MEDIUM or HIGH. */
    @Column(name = "priority", nullable = false, length = 16)
    public String priority = "NONE";

    @Column(name = "due_date")
    public LocalDate dueDate;

    @Version
    @Column(name = "version", nullable = false)
    public long version = 0L;
}
