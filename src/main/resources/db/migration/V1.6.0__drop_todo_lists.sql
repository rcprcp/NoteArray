-- Standalone TODO lists were removed. Task checkboxes now live inside note
-- content as markdown (- [ ] / - [x]), so there is no separate task store.
DROP TABLE IF EXISTS todo_items;
DROP TABLE IF EXISTS todo_lists;
