CREATE TABLE active_merge_requests (
    installation_id UUID NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    source_branch VARCHAR(512) NOT NULL,
    mr_iid BIGINT NOT NULL,
    target_project_id BIGINT,
    last_commit_sha VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (installation_id, project_id, source_branch)
);

CREATE INDEX idx_active_merge_requests_updated_at ON active_merge_requests (updated_at);
CREATE INDEX idx_active_merge_requests_target ON active_merge_requests (installation_id, target_project_id, source_branch);

CREATE TABLE recent_branch_pushes (
    installation_id UUID NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    branch VARCHAR(512) NOT NULL,
    latest_push_sha VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (installation_id, project_id, branch)
);

CREATE INDEX idx_recent_branch_pushes_updated_at ON recent_branch_pushes (updated_at);

CREATE TABLE processed_webhook_events (
    event_uuid VARCHAR(255) PRIMARY KEY,
    installation_id UUID REFERENCES installations(id) ON DELETE CASCADE,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_processed_webhook_events_installation_id ON processed_webhook_events (installation_id);
CREATE INDEX idx_processed_webhook_events_processed_at ON processed_webhook_events (processed_at);
