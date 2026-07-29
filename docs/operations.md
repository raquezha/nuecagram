# Operations

## Environment

Use `env.example` as the public template and keep the real `.env` private. Required runtime variables are:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_WEBHOOK_SECRET`
- `PLATFORM_ADMIN_PASSWORD_HASH`
- `NUECAGRAM_PUBLIC_URL`
- `DATABASE_URL`
- `DATABASE_USER`
- `DATABASE_PASSWORD`

`PORT` is optional and defaults to the packaged application port.

## Compose deployment

`compose.yaml` starts the app and PostgreSQL with a persistent `postgres-data` volume. The app health check probes the DB-backed readiness endpoint:

```text
/nuecagram/health/ready
```

If `NUECAGRAM_PUBLIC_URL` uses another path, update the compose health path to match it.

## Reverse proxy

Forward the complete public path to the app without stripping it:

```text
https://example.com/nuecagram -> http://127.0.0.1:8080/nuecagram
```

Suppress access logging for token-bearing paths, especially one-time management URLs. If full suppression is not possible, remove query strings and path parameters before logs are written.

## Secret handling

- Do not commit `.env`, database dumps, Telegram tokens, GitLab personal access tokens, webhook tokens, management links, or password hashes.
- Do not paste management links into shared chats or tickets.
- Rotate a webhook secret with `/rotate <installation-id>` when exposure is suspected.

## Backup and restore

Back up PostgreSQL with native tools:

```bash
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > nuecagram.sql
```

Restore into a fresh database with:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"' < nuecagram.sql
```

Test restores on non-production data before depending on them.

## Rollback

Roll back by running the previous compatible immutable app image together with a database backup from the same compatibility window. Flyway down-migrations are not provided; do not assume an older image can run safely after newer migrations have changed the schema.
