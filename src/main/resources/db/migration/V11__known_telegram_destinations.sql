CREATE TABLE known_telegram_destinations (
    id TEXT PRIMARY KEY,
    telegram_chat_id BIGINT NOT NULL,
    telegram_topic_id BIGINT,
    chat_title TEXT,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX known_telegram_destinations_chat
    ON known_telegram_destinations (telegram_chat_id);
