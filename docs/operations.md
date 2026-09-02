# Operations

## Environment

Copy `env.example` to a private `.env` file and replace every placeholder. Required application variables are:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_WEBHOOK_SECRET`
- `PLATFORM_ADMIN_PASSWORD`
- `NUECAGRAM_PUBLIC_URL`
- `DATABASE_URL`
- `DATABASE_USER`
- `DATABASE_PASSWORD`

`PORT` is optional and defaults to the packaged application port. `NUECAGRAM_HEALTH_PATH` must match the public URL path followed by `/health/ready`; for example, `/nuecagram/health/ready`. PostgreSQL also requires `POSTGRES_DB`, `POSTGRES_USER`, and `POSTGRES_PASSWORD` when it runs through Compose.

Never commit the real environment file.

## Local Compose deployment

`compose.yaml` starts the app and PostgreSQL with a persistent `postgres-data` volume:

```bash
cp env.example .env
# edit .env
docker compose up -d
docker compose ps
```

The app health check uses the DB-backed readiness endpoint:

```text
/nuecagram/health/ready
```

## Reverse proxy and firewall

Terminate TLS at a reverse proxy and preserve the complete public path:

```text
https://example.com/nuecagram -> http://127.0.0.1:18080/nuecagram
```

Example Caddy route:

```caddyfile
example.com {
    handle /nuecagram* {
        reverse_proxy 127.0.0.1:18080
    }
}
```

Allow public traffic only to SSH, HTTP, and HTTPS as required. Production Compose binds the app to `127.0.0.1:18080`, so clients must use the reverse proxy.

## Telegram Bot Token Rotation

To rotate the `TELEGRAM_BOT_TOKEN`:
1. Request a new bot token from Telegram's `@BotFather` using `/revoke` or by creating a new token for your bot.
2. Update `TELEGRAM_BOT_TOKEN` in `/opt/nuecagram/.env` (or local `.env`) with the new token string.
3. Restart the Nuecagram container or service:
   ```bash
   docker compose restart app
   ```
4. On startup, Nuecagram automatically:
   - Validates the new bot token via Telegram Bot API (`getMe`).
   - Re-registers the Telegram webhook URL (`/telegram/webhook`).
   - Re-registers the chat menu button (`OPEN` -> `/webapp`).
5. Check container logs to verify successful token validation and sync:
   ```bash
   docker compose logs app | grep "Telegram Bot token validated"
   ```

## Secret handling

- Do not commit environment files, database dumps, Telegram tokens, GitLab personal access tokens, webhook tokens, management links, SSH private keys, or plain-text admin passwords.
- Do not paste management links into shared chats or tickets.
- Rotate a webhook secret with `/rotate <installation-id>` when exposure is suspected.
- Use a dedicated SSH key and unprivileged deploy user for automation; never use a personal key.

## Production server setup

The repository does not create or configure a server automatically. Before enabling automated deployment, provision a server with:

- Docker Engine and the Docker Compose plugin
- a TLS reverse proxy (e.g. Caddy or NGINX)
- UFW or an equivalent firewall
- persistent storage for PostgreSQL
- scheduled, tested database backups
- a dedicated unprivileged `deploy` user with its own SSH key

Do not add the deploy user to the `docker` group, because Docker access is effectively root access.

Install the root-owned deployment files at the paths expected by the workflow:

```bash
sudo install -d -o root -g root -m 755 /opt/nuecagram
sudo install -d -o root -g root -m 700 /var/lib/nuecagram
sudo install -o root -g root -m 755 scripts/nuecagram-deploy.sh /usr/local/bin/nuecagram-deploy
sudo install -o root -g root -m 644 compose.yaml /opt/nuecagram/compose.yaml
sudo install -o root -g root -m 600 .env /opt/nuecagram/.env
```

Production Compose reads all app and Compose settings from `/opt/nuecagram/.env`. Use `NUECAGRAM_BIND=127.0.0.1:18080:8080` behind the reverse proxy, then point Caddy to `127.0.0.1:18080`.

Allow the deploy user to run only the root-owned, input-validating deployment entrypoint. Create the rule with `visudo`:

```text
deploy ALL=(root) NOPASSWD: /usr/local/bin/nuecagram-deploy *
```

The entrypoint accepts only `deploy` or `rollback` and only valid `raquezha/nuecagram` image references (SHA tags, version tags, or digests). All server paths and the readiness URL are fixed inside the root-owned script.

## Continuous deployment flow

Every push to `main`:
1. Runs CI quality gates: lint, test, build, dependency scan.
2. Builds the Docker image and tags it with `sha-<commit>`.
3. Resolves the pushed image digest and requests production deployment.
4. Triggers the protected `production` environment approval gate on GitHub.
5. Upon approval, SSHs to the server, runs `/usr/local/bin/nuecagram-deploy`, and waits for `/nuecagram/health/ready`.
6. Atomically updates `NUECAGRAM_IMAGE` in `/opt/nuecagram/.env` upon healthy deployment.

## Rollback

To trigger a rollback:
1. Open **Actions > Build & Deploy to Production > Run workflow**.
2. Select `rollback` and leave `image_digest` as `previous`.
3. Approve the production deployment gate.
4. The server deploys the previously recorded healthy digest and verifies readiness.
