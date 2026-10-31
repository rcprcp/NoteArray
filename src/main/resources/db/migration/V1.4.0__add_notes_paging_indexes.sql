-- Keyset pagination sorts used by GET /api/notes/page.
-- owner_id first so a regular user's pages stay within one index range; id is
-- the stable tie-breaker appended to every cursor.
CREATE INDEX IF NOT EXISTS idx_notes_owner_created
    ON notes (owner_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_notes_owner_updated
    ON notes (owner_id, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_notes_owner_title
    ON notes (owner_id, lower(title), id);
