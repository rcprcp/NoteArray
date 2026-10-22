-- Orphan tracking for attachments whose link was removed from every note.
-- A scheduled job purges rows older than the grace window, so an attachment
-- survives a transient edit (the editor auto-saves every 2 s) and is only
-- reclaimed once no note references it any more.
ALTER TABLE note_attachments ADD COLUMN orphaned_at TIMESTAMP NULL;

CREATE INDEX idx_note_attachments_orphaned_at
    ON note_attachments (orphaned_at)
    WHERE orphaned_at IS NOT NULL;
