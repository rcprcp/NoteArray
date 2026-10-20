-- Optimistic locking version column for notes to prevent silent lost updates across tabs/devices.
ALTER TABLE notes ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
