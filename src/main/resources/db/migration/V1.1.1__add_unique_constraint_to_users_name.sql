-- The application declares users.name as unique (User.name) but the schema never
-- enforced it, so duplicates could be created.  De-duplicate existing rows first by
-- suffixing the later ones, then add the constraint.
UPDATE users u
SET name = u.name || '-' || substring(u.id::text FROM 1 FOR 8)
FROM (
    SELECT id,
           row_number() OVER (PARTITION BY name ORDER BY created_at, id) AS rn
    FROM users
) dup
WHERE u.id = dup.id
  AND dup.rn > 1;

ALTER TABLE users ADD CONSTRAINT uq_users_name UNIQUE (name);
