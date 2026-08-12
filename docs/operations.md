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
handle /nuecagram* {
    reverse_proxy 127.0.0.1:18080
}
```

Allow public traffic only to SSH, HTTP, and HTTPS as required. Production Compose binds the app to `127.0.0.1:18080`, so clients must use the reverse proxy.

## Telegram topic thread preservation

Nuecagram preserves Telegram Forum Topic thread IDs (`messageThreadId`). Slash commands typed inside a specific topic reply directly within that topic, and GitLab webhook installations bound to a topic post notifications exclusively into that topic.

Suppress access logging for token-bearing paths, especially one-time management URLs. If full suppression is not possible, remove query strings and path parameters before writing logs.

## Secret handling

- Do not commit environment files, database dumps, Telegram tokens, GitLab personal access tokens, webhook tokens, management links, SSH private keys, or plain-text admin passwords.
- Do not paste management links into shared chats or tickets.
- Rotate a webhook secret with `/rotate <installation-id>` when exposure is suspected.
- Use a dedicated SSH key and unprivileged deploy user for automation; never use a personal key.

## Production server setup

The repository does not create or configure a server automatically. Before enabling automated deployment, provision a server with:

- Docker Engine and the Docker Compose plugin
- a TLS reverse proxy
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

Production Compose reads all app and Compose settings from `/opt/nuecagram/.env`. Use `NUECAGRAM_BIND=127.0.0.1:18080:8080` behind the reverse proxy, then point Caddy to `127.0.0.1:18080`. `NUECAGRAM_IMAGE` may be a version tag or digest; GitHub Actions sets it during deployment.

Allow the deploy user to run only the root-owned, input-validating deployment entrypoint. Create the rule with `visudo`:

```text
deploy ALL=(root) NOPASSWD: /usr/local/bin/nuecagram-deploy *
```

The entrypoint accepts only `deploy` or `rollback` and only compatible `raquezha/nuecagram` version tags or digest references. All server paths and the readiness URL are fixed inside the root-owned script.

## GitHub production environment

The workflow does not create or protect a GitHub environment. A repository administrator must create and protect it before the first deployment:

1. Open **Settings > Environments > New environment**.
2. Name it exactly `production`.
3. Enable required reviewers and select trusted maintainers.
4. Restrict deployment branches to `main`.
5. Add environment secrets:
   - `PRODUCTION_SSH_PRIVATE_KEY`
   - `PRODUCTION_SSH_KNOWN_HOSTS`
6. Add environment variables:
   - `PRODUCTION_SSH_HOST`
   - `PRODUCTION_SSH_USER`
   - `PRODUCTION_SSH_PORT` if SSH does not use port 22

Use a dedicated private key in `PRODUCTION_SSH_PRIVATE_KEY`; install only its public key for the deploy user on the server.

`PRODUCTION_SSH_KNOWN_HOSTS` must contain the server's pinned SSH host key. Compare its fingerprint with the key shown through the hosting provider's trusted console before storing it. Do not disable strict host-key checking.

The workflow also refuses to run from any branch except `main`. Environment approval and the main-branch check are separate protections and both should remain enabled.

## Deploy an immutable release

Published release notes include an image reference such as:

```text
raquezha/nuecagram:v0.11.0
```

To deploy it:

1. Open **Actions > Deploy to Production > Run workflow**.
2. Select the `main` branch.
3. Choose `deploy`.
4. Paste the full image reference into `image_digest`.
5. Approve the protected environment deployment when prompted.

The server pulls that exact digest, starts the app, and waits for the container health check backed by the database readiness endpoint. If readiness fails, it attempts to restore the image that was running before the deployment.

## Rollback

Run the same workflow with `action` set to `rollback` and leave `image_digest` empty. The server deploys the previously recorded digest and waits for readiness. To select a specific compatible image instead, provide its full digest.

Flyway down-migrations are not provided. Do not run an older image unless its schema compatibility is known.

Before relying on production automation, perform a deploy and rollback drill on a non-production server configured the same way.

## Backup and restore

Find the production PostgreSQL container and back it up with native tools:

```bash
postgres_container=$(docker ps -q --filter label=com.docker.compose.project=nuecagram --filter label=com.docker.compose.service=postgres)
docker exec "$postgres_container" sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > nuecagram.sql
```

Test the backup by restoring it into a separate empty database, never over the live database:

```bash
postgres_container=$(docker ps -q --filter label=com.docker.compose.project=nuecagram --filter label=com.docker.compose.service=postgres)
docker exec "$postgres_container" sh -c 'createdb -U "$POSTGRES_USER" nuecagram_restore'
docker exec -i "$postgres_container" sh -c 'psql -U "$POSTGRES_USER" nuecagram_restore' < nuecagram.sql
```

Delete the test database after validation. For disaster recovery, restore only into a freshly initialized replacement database from the same compatibility window.
