-- TOTP (Google Authenticator) two-factor authentication.
-- One optional row per user; `enabled = false` means an enrollment was started
-- but not confirmed. The secret is encrypted at rest (AES-256-GCM) with
-- notearray.totp.encryption-key, so backups do not expose raw seeds.

CREATE TABLE user_totp (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret_ciphertext TEXT NOT NULL,
    key_version SMALLINT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_used_step BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enabled_at TIMESTAMP
);

-- Single-use recovery codes; only a hash is stored.
CREATE TABLE user_recovery_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at TIMESTAMP
);

CREATE INDEX idx_user_recovery_codes_unused
    ON user_recovery_codes (user_id)
    WHERE used_at IS NULL;

-- Short-lived password-verified login challenges awaiting the second factor.
-- Only a SHA-256 hash of the opaque token is stored; rows are single-use.
CREATE TABLE login_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    attempts SMALLINT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_login_challenges_expires_at ON login_challenges (expires_at);
CREATE INDEX idx_login_challenges_user_id ON login_challenges (user_id);
