CREATE TABLE telegram_launch_nonces (
    id UUID PRIMARY KEY,
    nonce_digest BYTEA NOT NULL UNIQUE,
    telegram_chat_id BIGINT NOT NULL,
    telegram_topic_id BIGINT,
    telegram_user_id BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX telegram_launch_nonces_expiry
    ON telegram_launch_nonces (expires_at)
    WHERE consumed_at IS NULL;

CREATE TABLE webapp_sessions (
    id UUID PRIMARY KEY,
    telegram_user_id BIGINT NOT NULL,
    telegram_chat_id BIGINT,
    telegram_topic_id BIGINT,
    token_digest BYTEA NOT NULL UNIQUE,
    token_hash TEXT NOT NULL,
    csrf_digest BYTEA NOT NULL,
    csrf_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX webapp_sessions_expiry
    ON webapp_sessions (expires_at);
