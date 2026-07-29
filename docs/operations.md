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

## Production deployment

S9 adds a protected GitHub Actions workflow at `.github/workflows/deploy-production.yml` plus the narrow remote entrypoint `scripts/nuecagram-deploy.sh`.

### Human prerequisites

Before the workflow can deploy safely, a human must provision and verify all of the following on the Droplet:

- Docker Engine and Docker Compose plugin.
- A checkout of this repo on the server, for example `/opt/nuecagram`, containing `compose.production.yaml`.
- A private runtime env file, for example `/etc/nuecagram/app.env`.
- Persistent PostgreSQL storage and tested backups.
- TLS termination, reverse proxying, and UFW rules.
- A pinned SSH host key copied into the GitHub Environment secret `PRODUCTION_SSH_KNOWN_HOSTS`.
- A deploy key copied into the GitHub Environment secret `PRODUCTION_SSH_PRIVATE_KEY`.
- GitHub Environment `production` variables for `PRODUCTION_SSH_HOST`, `PRODUCTION_SSH_USER`, and any path overrides.
- A root-owned copy of `scripts/nuecagram-deploy.sh` installed as `/usr/local/bin/nuecagram-deploy` and executable by the deploy user through a narrow sudo rule.

Example sudoers entry:

```text
deploy ALL=(root) NOPASSWD: /usr/local/bin/nuecagram-deploy
```

### Production compose file

Use `compose.production.yaml` on the server for immutable image deploys. It expects `NUECAGRAM_IMAGE` to be set by the deploy entrypoint and reuses the same PostgreSQL/data layout as local compose.

### Deploy workflow behavior

The protected workflow only runs by manual dispatch into the `production` environment. It:

- requires either a release tag or explicit image reference for deploys
- validates the published GitHub Release for tagged deploys
- connects only with the pinned host key from `PRODUCTION_SSH_KNOWN_HOSTS`
- calls `sudo /usr/local/bin/nuecagram-deploy`
- waits for the DB-backed readiness URL before succeeding
- keeps the previous app image reference in the configured state file for rollback

### Rollback

Roll back by dispatching the production workflow with `action=rollback`. The remote entrypoint uses the previously recorded immutable image unless an explicit `image_ref` override is supplied.

Flyway down-migrations are not provided; do not assume an older image can run safely after newer migrations have changed the schema.

### Required human drill

Before relying on production deployment, a human must run one non-production deploy and one rollback drill against a server with the same provisioning model, then verify app readiness and PostgreSQL restore behavior.
