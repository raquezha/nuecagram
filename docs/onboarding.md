# Onboarding

Nuecagram features a **DM-first** management experience. Group administrators run `/setup` in the destination group or topic to receive an inline button that opens the Web App setup wizard, then use private DM commands and inline callback menus to manage GitLab notification webhooks. Text-based slash commands remain supported for management and recovery tools.

## Telegram setup

1. Create a bot with BotFather and set `TELEGRAM_BOT_TOKEN` privately in `.env`.
2. The app configures Telegram command autocomplete on startup. `/start` remains usable but is omitted from autocomplete.
3. Configure Telegram to deliver updates to `${NUECAGRAM_PUBLIC_URL}/telegram` with `TELEGRAM_WEBHOOK_SECRET` as `X-Telegram-Bot-Api-Secret-Token`.
4. Add the bot to the destination Telegram group and make it an administrator.
5. The human administrator sends `/start` to the bot in a private chat to bootstrap DM delivery.

## Primary Path: Group Setup, then DM Management

1. In your destination Telegram group chat or forum topic, run `/setup`.
2. The bot replies with an inline button for that group or topic.
3. Tap the button to open the existing Web App wizard.
4. Enter your GitLab base URL and project ID in the wizard.
5. Copy the webhook URL and secret token from the in-app reveal screen, then configure your GitLab webhook.
6. Use DM commands such as `/manage`, `/status`, `/test`, `/rotate`, `/mute`, `/unmute`, and `/digest` to manage the installation.
7. Use the inline menu in DM when you want callback navigation instead of typing commands.

## Setup Flow Notes

Run `/setup` only inside the destination Telegram group or forum topic. The command captures the Telegram group ID, the initiating admin user ID, and the topic ID when present. GitLab URL and project ID are entered later in the Web App wizard.

You still need private `/start` first so the Web App session can complete DM bootstrap checks.

## Configure the GitLab webhook

You have 2 choices for configuring webhooks in GitLab:

### Choice 1: Group-Level Webhook (Recommended for multiple projects)
Configure once at the GitLab Group level so all current and future projects in the group send events automatically:
- Go to **Group Settings > Webhooks** in GitLab.
- **URL**: `${NUECAGRAM_PUBLIC_URL}/webhook`
- **Secret token**: the generated secret token from the Web App reveal screen.
- **Trigger events**: Push, Tag, Pipeline, Merge Request, Issue, Note, Release, Job.

### Choice 2: Project-Level Webhook
Configure for individual projects one by one:
- Go to **Project Settings > Webhooks** in GitLab.
- **URL**: `${NUECAGRAM_PUBLIC_URL}/webhook`
- **Secret token**: the generated secret token from the Web App reveal screen.
- **Trigger events**: enable pipeline, push, tag, merge request, issue, note, wiki, deployment, and release events as needed.

Do not configure custom Nuecagram headers. Routing comes from the verified installation secret stored by Nuecagram.

## Manage an installation

Telegram group administrators run `/setup` in the target group/topic, then manage installations from private DM commands.

### Command Reference Matrix

| Command | Location | Access Level | Required Parameters | Success Response | Common Errors / Warnings |
|---------|----------|--------------|---------------------|------------------|--------------------------|
| `/start` | Private DM | All Users | None | DM: BotFather-style command overview with OPEN menu guidance | Group: `Start a private chat with the bot first.` |
| `/help` | Group or DM | All Users | None | Group: Short guidance + DM button<br>DM: Categorized inline menu | None |
| `/setup` | Group / Topic | Group Admins | None | Group: Web App launcher button | No DM start: `Use /start in a private chat...`<br>Non-admin: `Only Telegram group administrators...` |
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
  3. Use the **OPEN** menu button for the Web App Dashboard, or re-run `/setup` in your group/topic or management commands in DM.

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

### 5. Usage Guidance
* **Cause**: Some DM management commands still require an installation ID.
* **Resolution**:
  - For `/setup`: run `/setup` with no arguments inside the target group or topic.
  - For `/test`, `/manage`, `/rotate`: include your 8-character installation ID (e.g. `/test a1b2c3d4`).

