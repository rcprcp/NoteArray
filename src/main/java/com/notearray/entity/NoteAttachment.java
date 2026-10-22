package com.notearray.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "note_attachments")
public class NoteAttachment extends BaseEntity {

    @Column(name = "note_id", nullable = false)
    public UUID noteId;

    @Column(name = "owner_id", nullable = false)
    public UUID ownerId;

    @Column(name = "filename", nullable = false, length = 255)
    public String filename;

    @Column(name = "content_type", nullable = false, length = 128)
    public String contentType;

    @Column(name = "byte_size", nullable = false)
    public long byteSize;

    /**
     * Set when no note references this attachment any more; a scheduled job
     * purges rows older than the grace window, and a re-added reference clears
     * it again.
     */
    @Column(name = "orphaned_at")
    public Instant orphanedAt;

    @JsonIgnore
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "data", nullable = false)
    public byte[] data;
}
