CREATE TABLE mr_participant_caches (
    installation_id UUID NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    mr_iid BIGINT NOT NULL,
    author_username VARCHAR(255),
    reviewer_usernames TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (installation_id, project_id, mr_iid)
);
