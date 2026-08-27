# Nuecagram

[![GitHub Release](https://img.shields.io/github/v/release/raquezha/nuecagram?color=blue)](https://github.com/raquezha/nuecagram/releases)
[![Build Status](https://img.shields.io/github/actions/workflow/status/raquezha/nuecagram/docker-deploy.yml?branch=main&label=production)](https://github.com/raquezha/nuecagram/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Nuecagram is a self-hosted GitLab-to-Telegram notification service. One hosted instance can serve multiple GitLab projects through DB-backed installations, per-installation webhook secrets, and Telegram administrator onboarding.

## What it does

- Sends GitLab push, tag, merge request, issue, note, wiki, deployment, release, pipeline, and job notifications to Telegram.
- Provides a native **Telegram DM-first** management experience with group setup via Web App launcher, private command flows, one-time secret display, and mute/test controls inside Telegram.
- Consolidates pipeline and job activity into an updating Telegram message per installation and pipeline.
- Stores installation state in PostgreSQL; webhook secrets and management links are stored only as hashes.
- Maintains full text slash command fallback (`/setup`, `/manage`, `/status`, `/rotate`, `/mute`, `/unmute`, `/test`, `/digest`) for recovery and power users.
- Exposes liveness and DB-backed readiness under the configured public path, for example `/nuecagram/health/ready`.

## Quick start

1. Copy the placeholder environment file and fill private values locally:

   ```bash
   cp env.example .env
   ```

2. Start the app and PostgreSQL:

   ```bash
   docker compose up --build -d
   docker compose ps
   ```

3. Configure your reverse proxy to preserve the path in `NUECAGRAM_PUBLIC_URL` and forward it to the app, for example:

   ```text
   https://example.com/nuecagram -> http://127.0.0.1:8080/nuecagram
   ```

4. Add your Telegram bot to the target group, make it an administrator, then send the bot a private `/start`.

5. In the destination Telegram group, run `/setup`.

   For topic-enabled supergroups, run the command inside the topic that should receive notifications. Nuecagram opens the existing Web App wizard for that group or topic, where you enter the GitLab URL and project ID and receive the webhook URL and GitLab secret token in-app.

6. In GitLab, create a project webhook using the URL and secret token from the Web App reveal screen. GitLab sends the token as `X-Gitlab-Token`; do not add custom Nuecagram headers.

## Documentation

The full operations guide lives in [`docs/`](docs/index.md):

- [Onboarding](docs/onboarding.md)
- [Operations](docs/operations.md) - includes protected production deploy and rollback prerequisites
- [Webhook scripts](docs/webhook-scripts.md)

Build the documentation site with:

```bash
zensical build
```

## Development

```bash
./gradlew build
./gradlew test
./gradlew lintKotlinMain lintKotlinTest
./gradlew detekt
```

## License

MIT License - see [LICENSE](LICENSE) for details.
