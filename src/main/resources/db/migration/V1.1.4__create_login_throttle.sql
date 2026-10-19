-- Throttling state for the login and signup endpoints. A single key/value table
-- backs both mechanisms so there is one place to purge:
--   'email:<lowercased email>'  -> consecutive failed-login backoff per account
--   'login-ip:<client ip>'      -> hourly request window for POST /api/users/login
--   'signup-ip:<client ip>'     -> hourly request window for POST /api/users
CREATE TABLE login_throttle (
    throttle_key         VARCHAR(320)             PRIMARY KEY,
    consecutive_failures INTEGER                  NOT NULL DEFAULT 0,
    window_count         INTEGER                  NOT NULL DEFAULT 0,
    window_started_at    TIMESTAMP WITH TIME ZONE,
    next_allowed_at      TIMESTAMP WITH TIME ZONE,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Supports the hourly purge of stale rows.
CREATE INDEX idx_login_throttle_updated_at ON login_throttle (updated_at);
