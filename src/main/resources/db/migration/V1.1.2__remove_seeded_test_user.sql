-- The seeded test account (mickey@mickey.com / "mickey") was a known default
-- credential on every deployment. Remove it; accounts are created through the
-- application's signup form instead.
DELETE FROM users WHERE email = 'mickey@mickey.com';
