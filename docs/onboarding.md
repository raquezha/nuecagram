# Onboarding

Nuecagram features a **Telegram Web App-first** management experience. Group administrators can launch the interactive Web App directly inside Telegram to set up, monitor, mute, test, and manage GitLab notification webhooks visually without manually typing slash commands. Text-based slash commands remain fully supported as text fallbacks and recovery tools.

## Telegram setup

1. Create a bot with BotFather and set `TELEGRAM_BOT_TOKEN` privately in `.env`.
2. Enable Web App in BotFather (`/mybots` -> Bot Settings -> Configure Mini App -> URL: `${NUECAGRAM_PUBLIC_URL}/webapp`).
3. Configure Telegram to deliver updates to `${NUECAGRAM_PUBLIC_URL}/telegram` with `TELEGRAM_WEBHOOK_SECRET` as `X-Telegram-Bot-Api-Secret-Token`.
4. Add the bot to the destination Telegram group and make it an administrator.
5. The human administrator sends `/start` to the bot in a private chat to bootstrap DM delivery.

## Primary Path: Web App Setup Wizard

1. In your destination Telegram group chat or forum topic, tap the **Open Nuecagram** inline button on any bot reply or open `/webapp`.
2. The Web App automatically resolves your current Telegram context (Group Chat vs. Forum Topic #id).
3. Tap **+ Add** in the dashboard header to launch the Setup Wizard (visible when group context is active).
4. Enter your **GitLab Base URL** (e.g. `https://gitlab.com`) and numeric **Project ID**.
5. Tap **Create Installation**. The Web App generates a unique webhook endpoint URL and single-view `X-Gitlab-Token` secret.
6. Copy the secret token immediately and configure your GitLab webhook. For security, raw secrets are displayed **only once** in the UI.

## Fallback Path: Command-First Onboarding

If the Telegram Web App is unavailable in your client, run:

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

Telegram group administrators run `/setup` in the target group/topic, then manage installations from private DM commands.

### Command Reference Matrix

| Command | Location | Access Level | Required Parameters | Success Response | Common Errors / Warnings |
|---------|----------|--------------|---------------------|------------------|--------------------------|
| `/start` | Private DM | All Users | None | DM: `Private onboarding is ready.` | Group: `Start a private chat with the bot first.` |
| `/help` | Group or DM | All Users | None | Help guide with command syntax | None |
| `/setup` | Group / Topic | Group Admins | `<gitlab-base-url> <project-id>` | Group: `Private setup details sent.`<br>DM: Credential, Webhook URL, Management URL | Missing args: Usage & example<br>No DM start: `Use /start in a private chat...`<br>Non-admin: `Only Telegram group administrators...` |
| `/manage` | Private DM | Group Admins | Optional `<installation-id>` | DM: Installation picker or single-use management URL | Group: DM redirect button<br>Missing ID with picker unavailable: no installations found<br>Unauthorized: `Only Telegram group administrators...` |
| `/test` | Private DM | Group Admins | `<installation-id>` | Stored group/topic receives test notification | Group: DM redirect button<br>Missing ID: Usage & example<br>Unauthorized: `Only Telegram group administrators...` |
| `/status` | Private DM | Group Admins | `<installation-id>` | DM: Status, GitLab URL, Project ID, Mute state | Group: DM redirect button<br>Missing ID: Usage & example<br>Unauthorized: `Only Telegram group administrators...` |
| `/rotate` | Private DM | Group Admins | `<installation-id>` | DM: New credential & Management URL | Group: DM redirect button<br>Missing ID: Usage & example<br>Unauthorized: `Only Telegram group administrators...` |
| `/mute` | Private DM | Group Admins | `<installation-id>` | DM: `Installation muted.` | Group: DM redirect button<br>Missing ID: Usage & example<br>Unauthorized: `Only Telegram group administrators...` |
| `/unmute` | Private DM | Group Admins | `<installation-id>` | DM: `Installation unmuted.` | Group: DM redirect button<br>Missing ID: Usage & example<br>Unauthorized: `Only Telegram group administrators...` |
| `/digest` | Private DM | Group Admins | `<installation-id>` | DM: Installation summary text | Group: DM redirect button<br>Missing ID: Usage & example<br>Unauthorized: `Only Telegram group administrators...` |

---

## Command Troubleshooting & Error Resolution

If you run a command and receive an error message in Telegram, follow the resolution steps below:

### 1. `Use /start in a private chat before using admin commands.`
* **Cause**: You have not started a private DM session with `@NuecagramBot`. For security, secret tokens and management URLs are delivered only to private DMs.
* **Resolution**:
  1. Click [@NuecagramBot](https://t.me/NuecagramBot) to open a private message window.
  2. Click **Start** or send `/start`.
  3. Re-run `/setup` in your group/topic or management commands in DM.

### 2. `Only Telegram group administrators can use this command.`
* **Cause**: Nuecagram verifies your admin privileges via Telegram API (`getChatMember`). Only group Creators and Administrators can run setup or management commands.
* **Resolution**:
  1. Open **Group Settings > Administrators** in Telegram.
  2. Promote your Telegram user account to **Administrator**.
  3. Re-run the command in the group chat.

### 3. `Installation not found in this chat.`
* **Cause**: The provided `installation-id` does not exist or belongs to a different Telegram group chat.
* **Resolution**:
  1. Verify the 8-character ID prefix or full UUID from your initial setup DM message.
  2. Confirm you are running the command in the exact group/topic where `/setup` was performed.

### 4. `Run this command in the installation group.`
* **Cause**: You ran a group-bound administrative command like `/setup` inside a private DM with the bot.
* **Resolution**:
  1. Go to your destination Telegram group or forum topic.
  2. Run the command inside the group chat.

### 5. Usage Guidance (e.g., `Usage: /setup <gitlab-base-url> <project-id>`)
* **Cause**: The command was sent with missing or incorrectly formatted parameters.
* **Resolution**:
  - For `/setup`: include your GitLab base URL and numeric project ID (e.g. `/setup https://gitlab.com 12345678`).
  - For `/test`, `/manage`, `/rotate`: include your 8-character installation ID (e.g. `/test a1b2c3d4`).

