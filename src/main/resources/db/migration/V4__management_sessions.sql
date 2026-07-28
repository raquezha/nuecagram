CREATE TABLE management_sessions (
    id UUID PRIMARY KEY,
    installation_id UUID NOT NULL REFERENCES installations (id) ON DELETE CASCADE,
    token_digest BYTEA NOT NULL UNIQUE,
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX management_sessions_expiry
    ON management_sessions (expires_at);
