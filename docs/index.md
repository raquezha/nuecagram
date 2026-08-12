# Nuecagram

Nuecagram hosts multiple GitLab project notification installs behind one Telegram bot.

## Start here

1. Deploy the app with PostgreSQL using `compose.yaml` and a private `.env` based on `env.example`.
2. Put Nuecagram behind a path-preserving reverse proxy that matches `NUECAGRAM_PUBLIC_URL`.
3. Add the bot to a Telegram group, make it an administrator, and send the bot a private `/start`.
4. Run `/setup https://gitlab.com <project-id>` in the destination group or notification topic as a Telegram administrator.
5. Use the private setup message to create the GitLab webhook with GitLab's native secret token.

## Architecture

- [Architecture Overview](architecture/index.md)
  - [Core Principles](architecture/principles.md)
  - [Feature-First Package Layout](architecture/feature-first.md)
  - [Dependency & Import Rules](architecture/dependency-rules.md)
  - [Automated Dependency Maintenance](architecture/dependency-maintenance.md)
  - [Architectural Decisions](architecture/decisions.md)

## Guides

- [Developer Guides Index](guides/index.md)
  - [Adding a New Event Type](guides/add-event-type.md)
  - [Adding an External Adapter](guides/add-adapter.md)
  - [Telegram Forum Topic Routing](guides/telegram-topics.md)
  - [Automated Release Pipeline](operations.md)
- [Onboarding](onboarding.md)
- [Operations](operations.md)
- [Webhook scripts](webhook-scripts.md)
