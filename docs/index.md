# Nuecagram

Nuecagram hosts multiple GitLab project notification installs behind one Telegram bot.

## Start here

1. Deploy the app with PostgreSQL using `compose.yaml` and a private `.env` based on `env.example`.
2. Put Nuecagram behind a path-preserving reverse proxy that matches `NUECAGRAM_PUBLIC_URL`.
3. Add the bot to your target Telegram group/topic and make it an administrator.
4. Open `@NuecagramBot` in Telegram and tap the **OPEN** menu button to launch the Web App portal.
5. Tap **+ Add repository** to connect a new GitLab project and copy your webhook token.

## Architecture

- [Architecture Overview](architecture/index.md)
  - [Core Principles](architecture/principles.md)
  - [Feature-First Package Layout](architecture/feature-first.md)
  - [Dependency & Import Rules](architecture/dependency-rules.md)
  - [Automated Dependency Maintenance](architecture/dependency-maintenance.md)
  - [Architectural Decisions](architecture/decisions.md)
  - [Archived Telegram management UX notes](telegram-webapp-design-spec.md)
  - [Archived Telegram management mockup](mockup/telegram-webapp-preview.html)

## Guides

- [Developer Guides Index](guides/index.md)
  - [Adding a New Event Type](guides/add-event-type.md)
  - [Adding an External Adapter](guides/add-adapter.md)
  - [Telegram Forum Topic Routing](guides/telegram-topics.md)
  - [Automated Release Pipeline](operations.md)
- [Onboarding](onboarding.md)
- [Operations](operations.md)
- [Webhook scripts](webhook-scripts.md)
