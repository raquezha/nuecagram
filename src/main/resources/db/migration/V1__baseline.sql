CREATE TABLE installations (
    id UUID PRIMARY KEY,
    gitlab_base_url TEXT NOT NULL,
    gitlab_project_id BIGINT,
    telegram_chat_id BIGINT NOT NULL,
    telegram_topic_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX installations_gitlab_project_unique
    ON installations (gitlab_base_url, gitlab_project_id)
    WHERE gitlab_project_id IS NOT NULL;

CREATE TABLE webhook_secrets (
    id UUID PRIMARY KEY,
    installation_id UUID NOT NULL REFERENCES installations (id) ON DELETE CASCADE,
    secret_digest BYTEA NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX webhook_secrets_installation_active
    ON webhook_secrets (installation_id)
    WHERE revoked_at IS NULL;

CREATE TABLE management_links (
    id UUID PRIMARY KEY,
    installation_id UUID NOT NULL REFERENCES installations (id) ON DELETE CASCADE,
    token_digest BYTEA NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX management_links_expiry
    ON management_links (expires_at)
    WHERE consumed_at IS NULL;

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    installation_id UUID REFERENCES installations (id) ON DELETE SET NULL,
    actor_type TEXT NOT NULL,
    actor_id TEXT,
    action TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX audit_events_installation_created
    ON audit_events (installation_id, created_at DESC);

CREATE TABLE event_summaries (
    id UUID PRIMARY KEY,
    installation_id UUID NOT NULL REFERENCES installations (id) ON DELETE CASCADE,
    external_event_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    status TEXT,
    pipeline_id BIGINT,
    job_id BIGINT,
    event_url TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (installation_id, event_type, external_event_id)
);

CREATE INDEX event_summaries_installation_received
    ON event_summaries (installation_id, received_at DESC);

CREATE TABLE mute_states (
    installation_id UUID PRIMARY KEY REFERENCES installations (id) ON DELETE CASCADE,
    muted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
