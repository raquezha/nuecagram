# Onboarding

## Telegram setup

1. Create a bot with BotFather and set `TELEGRAM_BOT_TOKEN` privately in `.env`.
2. Configure Telegram to deliver updates to `${NUECAGRAM_PUBLIC_URL}/telegram` with `TELEGRAM_WEBHOOK_SECRET` as `X-Telegram-Bot-Api-Secret-Token`.
3. Add the bot to the destination Telegram group and make it an administrator.
4. The human who will manage the installation sends `/start` to the bot in a private chat.

## Create an installation

In the destination Telegram group, a group administrator runs:

```text
/setup https://gitlab.com <project-id> [topic-id]
```

Nuecagram sends all credential material only to the administrator's private chat. The group response contains no webhook secret and no management link.

## Configure the GitLab webhook

Use GitLab project **Settings > Webhooks**:

- URL: the webhook URL from the private setup message, usually `${NUECAGRAM_PUBLIC_URL}/webhook`.
- Secret token: the generated token from the private setup message. GitLab sends this as `X-Gitlab-Token`.
- SSL verification: enabled.
- Events: enable pipeline events first; add push, tag, merge request, issue, note, wiki, deployment, and release events as needed.

Do not configure custom Nuecagram headers. Routing comes from the verified installation secret stored by Nuecagram.

## Manage an installation

Telegram group administrators can run:

```text
/status <installation-id>
/digest <installation-id>
/test <installation-id>
/mute <installation-id>
/unmute <installation-id>
/manage <installation-id>
/rotate <installation-id>
```

Management links and rotated credentials are delivered only to the verified administrator's private chat.
