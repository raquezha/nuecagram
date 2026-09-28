DROP INDEX installations_gitlab_project_unique;

CREATE UNIQUE INDEX installations_gitlab_project_destination_unique
    ON installations (
        gitlab_base_url,
        gitlab_project_id,
        telegram_chat_id,
        COALESCE(telegram_topic_id, 0)
    )
    WHERE gitlab_project_id IS NOT NULL AND deleted_at IS NULL;
