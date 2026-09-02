# Onboarding

Nuecagram features a **DM-first** management experience. Group administrators tap the **OPEN** menu button in `@NuecagramBot` to launch the Web App setup wizard, then use private DM commands and inline callback menus to manage GitLab notification webhooks. Text-based slash commands remain supported for management and recovery tools.

## Telegram setup

1. Create a bot with BotFather and set `TELEGRAM_BOT_TOKEN` privately in `.env`.
2. The app configures Telegram command autocomplete on startup. `/start` remains usable but is omitted from autocomplete.
3. Configure Telegram to deliver updates to `${NUECAGRAM_PUBLIC_URL}/telegram` with `TELEGRAM_WEBHOOK_SECRET` as `X-Telegram-Bot-Api-Secret-Token`.
4. Add the bot to the destination Telegram group and make it an administrator.
5. Open `@NuecagramBot` in Telegram and tap **OPEN** to launch the management portal.

## Primary Path: Web App Setup & DM Management

1. Open `@NuecagramBot` in Telegram and tap **OPEN** to open the Web App portal.
2. Tap **+ Add repository**.
3. Select your target Telegram destination group or topic and enter your GitLab Base URL and Project ID.
4. Copy the webhook URL and secret token from the in-app reveal screen, then configure your GitLab webhook.
5. Use DM commands such as `/manage`, `/status`, `/test`, `/rotate`, `/mute`, `/unmute`, and `/digest` to manage the installation.
6. Use the inline menu in DM when you want callback navigation instead of typing commands.

## Setup Flow Notes

Group administrators open `@NuecagramBot` in DM and tap **OPEN** to launch the Web App. Target Telegram groups and topics are selected from the in-app destination dropdown.

You need to send private `/start` to the bot first so the Web App session can complete DM bootstrap checks.

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

Telegram group administrators manage installations exclusively from private DM commands and the Web App portal.

### Command Reference Matrix

| Command | Location | Access Level | Required Parameters | Success Response | Common Errors / Warnings |
|---------|----------|--------------|---------------------|------------------|--------------------------|
| `/start` | Private DM | All Users | None | DM: BotFather-style command overview with OPEN menu guidance | Group: `Start a private chat with the bot first.` |
| `/help` | Group or DM | All Users | None | Group: Short guidance + DM button<br>DM: Categorized inline menu | None |
| `/manage` | Private DM | Group Admins | Optional `<installation-id>` | DM: Installation picker or single-use management URL | Group: DM redirect button<br>Missing ID with picker unavailable: no installations found<br>Unauthorized: `Only Telegram group administrators...` |
| `/test` | Private DM | Group Admins | None | DM: Repository picker, then stored group/topic receives test notification | Group: DM redirect button<br>Unauthorized: `Only Telegram group administrators...` |
| `/status` | Private DM | Group Admins | None | DM: Repository picker, then status, GitLab URL, Project ID, Mute state | Group: DM redirect button<br>Unauthorized: `Only Telegram group administrators...` |
| `/rotate` | Private DM | Group Admins | None | DM: Repository picker, then rotation confirmation | Group: DM redirect button<br>Unauthorized: `Only Telegram group administrators...` |
| `/mute` | Private DM | Group Admins | None | DM: Repository picker, then `Installation muted.` | Group: DM redirect button<br>Unauthorized: `Only Telegram group administrators...` |
| `/unmute` | Private DM | Group Admins | None | DM: Repository picker, then `Installation unmuted.` | Group: DM redirect button<br>Unauthorized: `Only Telegram group administrators...` |
| `/digest` | Private DM | Group Admins | None | DM: Repository picker, then installation summary text | Group: DM redirect button<br>Unauthorized: `Only Telegram group administrators...` |

---

## Command Troubleshooting & Error Resolution

If you run a command and receive an error message in Telegram, follow the resolution steps below:

### 1. `Use /start in a private chat before using admin commands.`
* **Cause**: You have not started a private DM session with `@NuecagramBot`. For security, secret tokens and management URLs are delivered only to private DMs.
* **Resolution**:
  1. Click [@NuecagramBot](https://t.me/NuecagramBot) to open a private message window.
  2. Click **Start** or send `/start`.
  3. Use the **OPEN** menu button for the Web App Dashboard, or management commands in DM.

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
  2. Confirm you are managing a valid connected installation.

### 4. `Continue in a private chat with @NuecagramBot`
* **Cause**: You ran a management command inside a group or topic chat.
* **Resolution**:
  1. Open a private chat with [@NuecagramBot](https://t.me/NuecagramBot).
  2. Run management commands in DM or tap **OPEN** to launch the Web App.

### 5. Usage Guidance
* **Cause**: Some typed fallback commands can still accept an installation ID.
* **Resolution**:
  - For `/status`, `/test`, `/rotate`, `/mute`, `/unmute`, and `/digest`: run the command in DM and choose a repository from the picker.

