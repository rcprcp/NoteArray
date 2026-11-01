package com.notearray.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/**
 * A standalone TODO list owned by one user. A list may optionally be linked to
 * one note; deleting the note keeps the list and clears the link.
 */
@Entity
@Table(name = "todo_lists")
public class TodoList extends BaseEntity {

    @Column(name = "owner_id", nullable = false)
    public UUID ownerId;

    @Column(name = "title", nullable = false, length = 255)
    public String title = "";

    @Column(name = "note_id")
    public UUID noteId;

    @Column(name = "pinned", nullable = false)
    public boolean pinned = false;

    @Version
    @Column(name = "version", nullable = false)
    public long version = 0L;
}
