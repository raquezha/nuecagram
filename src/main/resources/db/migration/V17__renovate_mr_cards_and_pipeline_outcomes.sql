CREATE TABLE renovate_mr_cards (
    installation_id UUID NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    branch TEXT NOT NULL,
    commit_sha TEXT NOT NULL,
    mr_iid BIGINT NOT NULL,
    card_text TEXT NOT NULL,
    message_id TEXT,
    PRIMARY KEY (installation_id, project_id, branch)
);

CREATE TABLE renovate_pipeline_outcomes (
    installation_id UUID NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    branch TEXT NOT NULL,
    commit_sha TEXT NOT NULL,
    pipeline_id BIGINT NOT NULL,
    card_text TEXT NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (installation_id, project_id, pipeline_id)
);
