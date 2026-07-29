ALTER TABLE management_sessions
    ADD COLUMN csrf_digest BYTEA,
    ADD COLUMN csrf_hash TEXT;

CREATE TABLE platform_admin_sessions (
    id UUID PRIMARY KEY,
    token_digest BYTEA NOT NULL UNIQUE,
    token_hash TEXT NOT NULL,
    csrf_digest BYTEA NOT NULL,
    csrf_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX platform_admin_sessions_expiry
    ON platform_admin_sessions (expires_at);
