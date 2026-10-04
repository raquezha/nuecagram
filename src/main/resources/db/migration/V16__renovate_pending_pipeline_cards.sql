CREATE TABLE renovate_pipeline_cards (
    installation_id UUID NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    branch VARCHAR(512) NOT NULL,
    commit_sha VARCHAR(255) NOT NULL,
    pipeline_id BIGINT NOT NULL,
    chat_id TEXT NOT NULL,
    topic_id TEXT,
    card_text TEXT NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    message_id TEXT,
    claim_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (installation_id, project_id, branch, commit_sha)
);

CREATE INDEX renovate_pipeline_cards_pending ON renovate_pipeline_cards (due_at)
    WHERE message_id IS NULL;
