ALTER TABLE installations
    ADD COLUMN repo_name TEXT;

UPDATE installations
SET repo_name = 'Project #' || gitlab_project_id
WHERE gitlab_project_id IS NOT NULL;

UPDATE installations
SET repo_name = CASE
    WHEN POSITION('://' IN TRIM(gitlab_base_url)) > 0 THEN
        NULLIF(TRIM(BOTH '/' FROM REGEXP_REPLACE(TRIM(gitlab_base_url), '^https?://[^/]+/?', '')), '')
    ELSE
        NULLIF(TRIM(BOTH '/' FROM TRIM(gitlab_base_url)), '')
END
WHERE repo_name IS NULL
  AND gitlab_base_url IS NOT NULL
  AND TRIM(gitlab_base_url) <> '';

UPDATE installations
SET repo_name = 'Unknown Repository'
WHERE repo_name IS NULL
   OR TRIM(repo_name) = '';

ALTER TABLE installations
    ALTER COLUMN repo_name SET NOT NULL,
    ALTER COLUMN repo_name SET DEFAULT 'Unknown Repository';

ALTER TABLE installations
    ADD COLUMN chat_name TEXT;
