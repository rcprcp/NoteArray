-- Per-note editor height (client drag handle). NULL means "use the default".
-- The range matches the UI clamp (160..1200 px) and is enforced server-side too.
ALTER TABLE notes
    ADD COLUMN editor_height SMALLINT NULL;

ALTER TABLE notes
    ADD CONSTRAINT notes_editor_height_range
        CHECK (editor_height IS NULL OR editor_height BETWEEN 160 AND 1200);
