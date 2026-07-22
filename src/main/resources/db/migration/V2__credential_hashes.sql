ALTER TABLE webhook_secrets
    ADD COLUMN secret_hash TEXT;

ALTER TABLE management_links
    ADD COLUMN token_hash TEXT;
