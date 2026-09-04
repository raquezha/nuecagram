CREATE TABLE active_merge_requests (
    installation_id UUID NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    source_branch VARCHAR(255) NOT NULL,
    mr_iid BIGINT NOT NULL,
    last_commit_sha VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (installation_id, project_id, source_branch)
);

CREATE TABLE recent_branch_pushes (
    installation_id UUID NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    branch VARCHAR(255) NOT NULL,
    latest_push_sha VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (installation_id, project_id, branch)
);
