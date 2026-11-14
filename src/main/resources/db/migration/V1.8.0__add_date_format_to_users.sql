-- Preferred date format for timestamps in the notes list.
-- 'adaptive' is the default (time today, date this year, year when older);
-- the other values pin an explicit shape. See ui/src/dateFormat.js.
ALTER TABLE users
    ADD COLUMN date_format VARCHAR(32) NOT NULL DEFAULT 'adaptive';

ALTER TABLE users
    ADD CONSTRAINT users_date_format_valid
        CHECK (date_format IN (
            'adaptive', 'day_month', 'month_day', 'mdy_slash',
            'dmy_slash', 'iso', 'relative', 'full'
        ));
