-- Preferred clock for note timestamps in the notes list.
-- 'auto' follows the browser locale, 'h12' forces a 12-hour clock and
-- 'h23' forces a 24-hour clock.
ALTER TABLE users
    ADD COLUMN hour_cycle VARCHAR(8) NOT NULL DEFAULT 'auto';

ALTER TABLE users
    ADD CONSTRAINT users_hour_cycle_valid
        CHECK (hour_cycle IN ('auto', 'h12', 'h23'));
