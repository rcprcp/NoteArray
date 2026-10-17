-- Every list/search query ends with ORDER BY created_at DESC; index it so the
-- sort doesn't scan the owner's full result set on each request.
CREATE INDEX idx_notes_created_at ON notes (created_at);
