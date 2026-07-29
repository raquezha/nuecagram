# Nuecagram

Nuecagram hosts multiple GitLab project notification installs behind one Telegram bot.

## Start here

1. Deploy the app with PostgreSQL using `compose.yaml` and a private `.env` based on `env.example`.
2. Put Nuecagram behind a path-preserving reverse proxy that matches `NUECAGRAM_PUBLIC_URL`.
3. Add the bot to a Telegram group, make it an administrator, and send the bot a private `/start`.
4. Run `/setup https://gitlab.com <project-id> [topic-id]` in the group as a Telegram administrator.
5. Use the private setup message to create the GitLab webhook with GitLab's native secret token.

## Guides

- [Onboarding](onboarding.md)
- [Operations](operations.md)
- [Webhook scripts](webhook-scripts.md)
