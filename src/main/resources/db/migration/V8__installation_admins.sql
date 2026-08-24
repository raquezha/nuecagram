CREATE TABLE installation_admins (
    installation_id UUID NOT NULL REFERENCES installations (id) ON DELETE CASCADE,
    telegram_user_id BIGINT NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (installation_id, telegram_user_id)
);

CREATE INDEX installation_admins_user_confirmed
    ON installation_admins (telegram_user_id, confirmed_at DESC);
