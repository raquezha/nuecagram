# Onboarding

## Telegram setup

1. Create a bot with BotFather and set `TELEGRAM_BOT_TOKEN` privately in `.env`.
2. Configure Telegram to deliver updates to `${NUECAGRAM_PUBLIC_URL}/telegram` with `TELEGRAM_WEBHOOK_SECRET` as `X-Telegram-Bot-Api-Secret-Token`.
3. Add the bot to the destination Telegram group and make it an administrator.
4. The human who will manage the installation sends `/start` to the bot in a private chat.

## Create an installation

In the destination Telegram group, a group administrator runs:

```text
/setup https://gitlab.com <project-id>
```

For a normal group or main chat, Nuecagram stores the chat as the notification destination. For a topic-enabled supergroup, run the command inside the topic that should receive notifications; Nuecagram stores Telegram's message thread automatically.

Nuecagram sends all credential material only to the administrator's private chat. The group response contains no webhook secret and no management link.

## Configure the GitLab webhook

You have 2 choices for configuring webhooks in GitLab:

### Choice 1: Group-Level Webhook (Recommended for multiple projects)
Configure once at the GitLab Group level so all current and future projects in the group send events automatically:
- Go to **Group Settings > Webhooks** in GitLab.
- **URL**: `${NUECAGRAM_PUBLIC_URL}/webhook`
- **Secret token**: the generated secret token from the private `/setup` message.
- **Trigger events**: Push, Tag, Pipeline, Merge Request, Issue, Note, Release, Job.

### Choice 2: Project-Level Webhook
Configure for individual projects one by one:
- Go to **Project Settings > Webhooks** in GitLab.
- **URL**: `${NUECAGRAM_PUBLIC_URL}/webhook`
- **Secret token**: the generated secret token from the private `/setup` message.
- **Trigger events**: enable pipeline, push, tag, merge request, issue, note, wiki, deployment, and release events as needed.

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
