-- Standalone TODO lists, separate from notes.
-- Lists can optionally be linked to one note; deleting that note keeps the list
-- (note_id becomes NULL). Items may be nested through parent_id and are ordered
-- per parent with sort_order.

CREATE TABLE todo_lists (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    note_id UUID REFERENCES notes(id) ON DELETE SET NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_todo_lists_owner_updated ON todo_lists (owner_id, updated_at DESC);
CREATE INDEX idx_todo_lists_note ON todo_lists (note_id) WHERE note_id IS NOT NULL;

CREATE TABLE todo_items (
    id UUID PRIMARY KEY,
    list_id UUID NOT NULL REFERENCES todo_lists(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES todo_items(id) ON DELETE CASCADE,
    body VARCHAR(2000) NOT NULL,
    checked BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    priority VARCHAR(16) NOT NULL DEFAULT 'NONE',
    due_date DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_todo_items_list_parent_order ON todo_items (list_id, parent_id, sort_order);
CREATE INDEX idx_todo_items_due_date ON todo_items (due_date) WHERE due_date IS NOT NULL;
