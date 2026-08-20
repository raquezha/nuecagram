# Telegram Web App-First Management Design Specification

- **Status**: Draft / Spec
- **Target Feature**: #100 (Telegram Web App-first UX transition)
- **Design Ticket**: #101
- **Implementation Slices**: #102, #103, #104, #105
- **Interactive UI Mockup**: [`docs/mockup/telegram-webapp-preview.html`](mockup/telegram-webapp-preview.html)

---

## Executive Summary

Nuecagram is transitioning from a text/command-only Telegram management experience to a **Telegram Web App-first experience**. Group administrators can open a native, context-aware Telegram Web App directly inside Telegram (via inline buttons, bot menu buttons, or chat commands) to configure, monitor, mute, test, and manage GitLab-to-Telegram notification webhooks without leaving the Telegram application.

This specification defines the UX hierarchy, launch context resolution, Telegram `initData` authentication contract, security rules for sensitive data delivery, backend API endpoints, fallback slash command matrix, and task decomposition for implementation.

---

## 1. Look & Feel and Design System

The Web App UI inherits Nuecagram's existing warm editorial aesthetic (`Space Grotesk` headings, `Reddit Mono` code typography, warm cream `#eee4d5` background, `#2c251e` container styling, and `#229ed9` Telegram accent tokens) while conforming to Telegram Web App viewport guidelines (`window.Telegram.WebApp`).

### Screen Map & UI Hierarchy

```text
[ Telegram Launcher (Inline Button / Menu / Slash Command) ]
                            │
                            ▼
           [ Backend: initData HMAC & Admin Auth ]
                            │
            ┌───────────────┴───────────────┐
            ▼                               ▼
 [ Admin Authorized ]             [ Unauthorized / Error ]
            │                               │
            ├─ DM Context                   ├─ Non-admin user message
            ├─ Group Main Chat Context      ├─ Private DM bootstrap required
            └─ Forum Topic Context          └─ Session expired / Invalid hash
                            │
                            ▼
          ┌───────────────────────────────────┐
          │  Web App Navigation Header        │
          │  (Context Badge & User Avatar)   │
          └─────────────────┬─────────────────┘
                            │
         ┌──────────────────┼──────────────────┐
         ▼                  ▼                  ▼
┌──────────────────┐┌──────────────────┐┌──────────────────┐
│ Installation     ││ Setup Wizard     ││ Settings &       │
│ Dashboard        ││ Screen           ││ Account Overview │
└────────┬─────────┘└────────┬─────────┘└──────────────────┘
         │                   │
         ▼                   ▼
┌──────────────────┐┌──────────────────┐
│ Installation     ││ Credential       │
│ Detail & Actions ││ Display (Secure) │
└──────────────────┘└──────────────────┘
```

### ASCII Wireframe Designs

#### Screen 1: Telegram Web App Shell & Installation Dashboard
```text
┌─────────────────────────────────────────────────────────┐
│ ✕  Nuecagram Bot                    bot management  ••• │ ← Telegram Header Bar
├─────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────┐ │
│ │ 🛈 CONTEXT: FORUM TOPIC (#42 Deployments)            │ │ ← Context Resolution Banner
│ │ Target: Backend Infrastructure Group                │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ INSTALLATIONS (2)                                 + ADD │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ a1b2c3d4                             [🟢 Active]    │ │ ← Installation Card 1
│ │ GitLab: https://gitlab.com (Project #12345678)      │ │
│ │ Destination: Topic #42 (Deployments)                │ │
│ │ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐  │ │
│ │ │ ✉ Test       │ │ 🔇 Mute      │ │ 🔑 Rotate    │  │ │ ← Card Actions
│ │ └──────────────┘ └──────────────┘ └──────────────┘  │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ e5f6g7h8                             [🔴 Muted]     │ │ ← Installation Card 2
│ │ GitLab: https://gitlab.com (Project #87654321)      │ │
│ │ Destination: Group Main Chat                        │ │
│ │ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐  │ │
│ │ │ ✉ Test       │ │ 🔊 Unmute    │ │ 🔑 Rotate    │  │ │
│ │ └──────────────┘ └──────────────┘ └──────────────┘  │ │
│ └─────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────┤
│ [                     + NEW INSTALLATION              ] │ ← Fixed Telegram Main Button
└─────────────────────────────────────────────────────────┘
```

#### Screen 2: Guided Setup Wizard Screen
```text
┌─────────────────────────────────────────────────────────┐
│ ✕  Nuecagram Bot                        bot management  │
├─────────────────────────────────────────────────────────┤
│ CONNECT NEW GITLAB REPOSITORY             [ Cancel ]    │
│                                                         │
│ 1. GitLab Base URL                                      │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ https://gitlab.com                                  │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ 2. GitLab Project ID                                    │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ 12345678                                            │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ 3. Target Telegram Destination                          │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ Topic #42 (Deployments)  [🔒 Locked to Context]     │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ 🛈 Info: Nuecagram will generate a unique webhook secret │
│ token for your repository settings.                     │
├─────────────────────────────────────────────────────────┤
│ [                 CREATE INSTALLATION                 ] │ ← Fixed Telegram Main Button
└─────────────────────────────────────────────────────────┘
```

#### Screen 3: Single-View Credential Reveal & Secret Security Box
```text
┌─────────────────────────────────────────────────────────┐
│ ✕  Nuecagram Bot                        bot management  │
├─────────────────────────────────────────────────────────┤
│ CREDENTIAL ISSUED                         [ Done ]      │
│                                                         │
│ ⚠️ STORE THIS SECRET TOKEN NOW!                         │
│ This secret token is shown ONLY ONCE for security.     │
│                                                         │
│ WEBHOOK SECRET TOKEN                                    │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ nc_sec_9f83a17c2b4e6d5a1098                         │ │ ← High-Contrast Monospace Box
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ GITLAB WEBHOOK URL                                      │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ https://nuecagram.example.com/webhook               │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ 📋 COPY SECRET TOKEN TO CLIPBOARD                   │ │
│ └─────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────┤
│ [                RETURN TO DASHBOARD                  ] │
└─────────────────────────────────────────────────────────┘
```

### Key Screen Models

1. **Installation Dashboard (Main Screen)**
   - **Header**: Active Telegram Chat / Topic name, current user admin badge, context indicator.
   - **Installation List**: Cards displaying installation short ID (`8 chars`), GitLab project URL/ID, target destination (Group vs `Topic #id`), status badge (`Active` green, `Muted` crimson).
   - **Filter/Search**: Toolbar to filter by status or search by project ID / URL.
   - **Primary Action**: `+ New Installation` button launching Setup Wizard.

2. **Installation Detail & Actions Screen**
   - **Summary Card**: Full installation ID, GitLab Base URL, Project ID, Telegram Chat/Topic destination, creation timestamp.
   - **Control Panel**:
     - **Toggle Mute**: One-click mute/unmute notification dispatches.
     - **Send Test Notification**: Triggers test delivery to the target Telegram chat/topic with immediate visual status feedback.
     - **Rotate Credential**: Requires double-step dialog confirmation. Replaces existing secret with a freshly generated token and presents it once.

3. **Guided Setup Wizard Screen**
   - **Step 1: GitLab Details**: Inputs for `GitLab Base URL` (default `https://gitlab.com`) and `Project ID` or `Group Webhook`.
   - **Step 2: Destination Confirmation**: Pre-selected current Telegram chat/topic with toggle for topic routing.
   - **Step 3: Webhook Generation & Secret Display**: Displays generated webhook endpoint URL and single-view `X-Gitlab-Token` with one-click copy buttons and instructions for GitLab configuration.

4. **Unauthorized & Recovery Screens**
   - **Non-Admin Error**: "Only Telegram group administrators can manage installations."
   - **DM Bootstrap Required**: "Please start a private chat with @NuecagramBot before managing sensitive credentials."
   - **Expired Session**: "Session timed out. Please re-open from Telegram."

---

## 2. Launch Entrypoints & Context Resolution

Web App launch behavior adapts dynamically depending on the entrypoint and launch chat context:

| Launch Location | Launch Mechanism | Context Resolution (`telegramChatId` / `telegramTopicId`) | Default Web App View |
|---|---|---|---|
| **Group Chat (Main)** | Chat Menu Button, Inline Button on `/manage`, Slash Command reply | `telegramChatId` = current group ID<br>`telegramTopicId` = `null` | Multi-installation list for current group chat. |
| **Forum Topic** | Inline Button inside topic, Topic Menu Button | `telegramChatId` = current group ID<br>`telegramTopicId` = current topic thread ID | Filtered installation list for current topic; option to switch to all group installations. |
| **Private DM** | Private Chat Menu Button, Slash Command `/start` reply | `telegramChatId` = private user ID | All installations across all groups where the user is an authorized admin. |

### Entrypoint Callback Strategy

1. **Bot Menu Button (`MenuButtonWebApp`)**: Configured globally or per-chat via Telegram Bot API `setChatMenuButton`. Points to `${NUECAGRAM_PUBLIC_URL}/webapp`.
2. **Inline Keyboard Buttons**: Slash commands (`/manage`, `/setup`, `/status`) attach an `InlineKeyboardButton` with `web_app = WebAppInfo(url = "${NUECAGRAM_PUBLIC_URL}/webapp?startapp=<context>")`.
3. **Deep Linking (`startapp` parameter)**: Encodes launch intent (e.g. `startapp=inst_<id>` or `startapp=chat_<id>_topic_<thread_id>`).

---

## 3. Telegram Web App Authentication & Session Model

Telegram Web Apps send authentication payload `window.Telegram.WebApp.initData` to the backend on launch. The backend verifies authentic ownership without asking the user for passwords.

```text
[ Telegram Client (Web App Container) ]
       │
       │  POST /api/webapp/auth { initData: "query_id=...&user=...&hash=..." }
       ▼
[ Nuecagram Ktor Backend ]
       │
       ├─ 1. Parse initData query string into key-value pairs
       ├─ 2. Extract and remove `hash`
       ├─ 3. Sort remaining keys alphabetically and format as `key=value\n`
       ├─ 4. Compute secret_key = HMAC-SHA256("WebAppData", TELEGRAM_BOT_TOKEN)
       ├─ 5. Compute calculated_hash = HMAC-SHA256(secret_key, data_check_string)
       ├─ 6. Constant-time compare calculated_hash with received `hash`
       ├─ 7. Verify `auth_date` is within max age threshold (e.g. 24 hours)
       ├─ 8. Query Telegram API `getChatMember(chat_id, user_id)` to verify admin status
       │
       ▼
[ Validated Session ]
       │  Issue HttpOnly, SameSite=Strict `nuecagram_webapp_session` Cookie + CSRF Token
       ▼
[ Response: HTTP 200 OK + User Admin Context ]
```

### Auth & Session Rules

1. **HMAC Validation Algorithm**: Strict implementation of Telegram official Web App data validation algorithm using HMAC-SHA256.
2. **Replay Protection**: Reject `auth_date` older than 86,400 seconds (24 hours).
3. **Admin Verification**: Require Telegram chat membership status in `["creator", "administrator"]` for group-bound requests.
4. **Dual Auth & Session Cookie**:
   - Cookie Name: `nuecagram_webapp_session` (also returned in POST `/auth` response payload as `sessionToken`).
   - Authentication Precedence: `nuecagram_webapp_session` Cookie -> `X-Session-Token` HTTP Header -> `Authorization: Bearer <token>` HTTP Header.
   - Security: `HttpOnly; Path=<basePath>` (`SameSite=None; Secure` when request is HTTPS, `SameSite=Lax` on HTTP).
   - TTL: 8 hours.
5. **Context Re-authentication Fallback**: When `startParam` lacks a launch nonce on re-authentication, `resolveLaunchContext` restores group/topic context from the existing valid session token (cookie or header) matching the authenticated Telegram user ID.
6. **CSRF Protection**: All mutating HTTP requests (`POST`, `PUT`, `DELETE`) require header `X-CSRF-Token` matching the session's stored CSRF digest.

---

## 4. Security & Sensitive Operations Contract

Nuecagram handles sensitive webhook secrets and management URLs under strict security guidelines:

1. **Zero Secret Leaks in Group Chats**: No raw secret token or management link is ever posted to group chats or topic threads.
2. **Private DM Bootstrap Rule**: Creating new installations or rotating credentials requires the user to have initiated a private chat with the bot (`/start` in DM). If no private chat exists, the Web App displays a prompt guiding the user to start DM onboarding.
3. **One-Time Credential Display**: Newly issued or rotated webhook secrets are displayed in the Web App interface exactly once upon creation. Backend stores only `secret_digest` (SHA-256) and Argon2 `secret_hash`.
4. **Audit Logging**: Every action performed through the Web App logs an immutable row to PostgreSQL `audit_events`:
   - `actor_type`: `"webapp_session"`
   - `actor_id`: `<telegram_user_id>`
   - `action`: `"webapp_setup"`, `"webapp_rotate"`, `"webapp_mute"`, `"webapp_unmute"`, `"webapp_test"`

---

## 5. Backend Endpoint & API Contracts

All endpoints are hosted under Ktor application routing taking `configuredBasePath()` into account:

### Web App Static & Auth Endpoints

- **`GET {basePath}/webapp`**
  - Serves Web App HTML container rendering `#app` shell with script reference to Telegram Web App JS SDK (`https://telegram.org/js/telegram-web-app.js`).
- **`POST {basePath}/api/webapp/auth`**
  - Payload: `{ "initData": "<raw_init_data_string>", "startParam": "nonce_..." }`
  - Response (200): `{ "success": true, "user": { "id": 98765, "firstName": "Alice" }, "csrf": "<csrf_token>", "sessionToken": "<session_token_string>", "telegramChatId": -100123, "telegramTopicId": 42 }`

### Management REST API Endpoints (Web App Session Authenticated)

- **`GET {basePath}/api/webapp/installations`**
  - Query Params: `chatId`, `topicId`
  - Response (200): JSON array of `InstallationAdminContext` items accessible by current user.
- **`GET {basePath}/api/webapp/installations/{id}`**
  - Response (200): Full installation details, status, GitLab project metadata, target destination.
- **`POST {basePath}/api/webapp/installations`**
  - Payload: `{ "gitlabBaseUrl": "https://gitlab.com", "gitlabProjectId": 12345, "telegramChatId": -100123, "telegramTopicId": 42 }`
  - Response (201): `{ "installation": {...}, "credential": "<raw_secret_token>" }`
- **`POST {basePath}/api/webapp/installations/{id}/mute`**
  - Payload: `{ "muted": true }`
  - Response (200): `{ "id": "...", "muted": true }`
- **`POST {basePath}/api/webapp/installations/{id}/rotate`**
  - Response (200): `{ "id": "...", "credential": "<new_raw_secret_token>" }`
- **`POST {basePath}/api/webapp/installations/{id}/test`**
  - Response (200): `{ "success": true, "message": "Test delivery dispatched." }`

---

## 6. Fallback Slash Commands & Rollout Matrix

To ensure zero regression for existing deployments, slash commands remain fully supported as text fallbacks:

| Command | Web App Primary Action | Slash Command Fallback Behavior |
|---|---|---|
| `/start` | Registers DM chat; sends Web App button | Text onboarding message + `Open Dashboard` Web App button |
| `/setup` | Opens Guided Setup Wizard in Web App | Text command parsing + DM secret delivery fallback |
| `/manage` | Opens Web App Installation Dashboard | DM single-use web link + Inline Web App button |
| `/status` | Displays Installation Details in Web App | Text reply in group chat |
| `/test` | Triggers test delivery from Web App | Text reply + test notification dispatch |
| `/mute` / `/unmute` | One-click toggle in Web App | Text status reply in group chat |
| `/rotate` | Dialog confirmation + display in Web App | DM credential rotation fallback |

---

## 7. Product Behavior Specification

The HTML preview is an illustrative product mockup, not a reproduction of Telegram's native chrome. In production, Telegram owns the top bar, safe areas, theme colors, and bottom button. Nuecagram renders only the Mini App content and configures Telegram's WebApp controls through the official SDK.

### 7.1 Happy Path: Existing Installation

1. An administrator opens Nuecagram from the bot menu or a Web App launch button.
2. The Mini App calls `Telegram.WebApp.ready()` and sends the raw `Telegram.WebApp.initData` to `POST /api/webapp/auth`.
3. The backend validates `hash`, `auth_date`, and the Telegram user. The client never sends `initDataUnsafe` as proof of identity.
4. The backend resolves the launch context and returns the authorized user, context, installations, and a short-lived session.
5. The dashboard shows the current group/topic when context is available; otherwise it shows the user's accessible installations.
6. The administrator opens an installation detail view.
7. The administrator selects **Test**, **Mute/Unmute**, or **Rotate**.
8. Destructive or delivery-affecting actions show an explicit confirmation dialog.
9. The backend re-checks session authorization, installation ownership/context, CSRF, and current state before changing anything.
10. The UI shows a pending state, then success or recoverable failure. Every completed action creates an audit event.
11. The user can navigate back with Telegram's `BackButton`, close the Mini App, or return to the dashboard.

### 7.2 Happy Path: New Installation

1. The administrator opens the dashboard and sees **No installations yet** or taps **Add installation**.
2. The wizard displays the resolved Telegram destination. A topic-launched setup keeps the topic locked; a DM-launched setup requires an explicit destination picker.
3. The administrator enters and validates the GitLab base URL and project ID.
4. The client displays a review step: GitLab target, Telegram chat/topic, and notification scope.
5. On confirmation, the backend authorizes the user again, creates the installation, issues the webhook secret, and records `webapp_setup`.
6. The credential screen displays the raw secret once, with webhook URL, copy buttons, GitLab instructions, and a warning that it cannot be recovered.
7. The user copies the secret or chooses **Send setup details to my private chat**. The backend never places the secret in a group message, URL, browser history, logs, or analytics payload.
8. The user confirms **I saved the secret**. The UI returns to the installation detail screen and shows only masked credential metadata.
9. A test delivery is offered after setup, with clear success/failure feedback.

### 7.3 Launch and Context Rules

| Context | Initial view | Allowed scope | Missing-context behavior |
|---|---|---|---|
| Group main chat | Group installations | Installations bound to that chat | Show empty state with **Add installation** |
| Forum topic | Topic installation(s) | Exact `telegramChatId + telegramTopicId` | Offer **View all group installations** only after explicit action |
| Private DM | Installation picker | Installations for which the user is an authorized administrator | Require destination selection for setup |
| No valid launch context | Account/installation picker | Only server-authorized installations | Never infer a group or topic from client-provided values |

A Telegram Mini App launch does not guarantee that the originating group topic is present in `initData`. Group/topic context must therefore come from a server-issued, short-lived, single-use launch state or an explicitly supported Telegram launch field, and must be treated as a hint until verified against the authenticated user and installation record.

### 7.4 Required Screen States

Every screen needs these states, not just the successful response:

- **Loading**: skeleton or Telegram MainButton progress state; controls disabled.
- **Empty**: no accessible installations, no installations in current topic, or no selectable destination.
- **Unauthorized**: invalid Telegram signature, expired `auth_date`, non-admin, revoked session, or wrong installation context.
- **DM bootstrap required**: sensitive setup/rotation cannot continue until the user starts the bot privately.
- **Validation error**: invalid HTTPS GitLab URL, missing/invalid project ID, unsupported target, or duplicate installation.
- **Conflict**: installation changed or was muted/rotated elsewhere; refresh before retrying.
- **Network/server error**: preserve form data where safe, explain retry, never claim an action succeeded without a confirmed response.
- **Success**: visible result plus next action; do not rely only on a toast.
- **Credential revealed**: one-time display with copy, private-DM delivery, save confirmation, and no raw secret after navigation.
- **Expired session**: clear local state and require a fresh Telegram launch/authentication.

### 7.5 Action Confirmation Rules

| Action | Confirmation | Success result | Failure recovery |
|---|---|---|---|
| Test delivery | No confirmation, but show target | Delivery accepted and target shown | Retry button; no duplicate claim |
| Mute | Confirm impact and current state | Status becomes Muted | Refresh installation |
| Unmute | Confirm notifications resume | Status becomes Active | Refresh installation |
| Rotate secret | Strong confirmation warning old token stops working | Show new token once | Keep old state if issuance fails |
| Create installation | Review GitLab and Telegram destination | Show one-time credential screen | Do not create a partial installation |
| Leave with unsaved form | Telegram popup confirmation | Return to previous screen | Stay on form |

### 7.6 Telegram Mini App Runtime Requirements

Use the official Telegram Web Apps SDK and support:

- `Telegram.WebApp.ready()` after the initial shell is usable.
- `Telegram.WebApp.expand()` where supported; layout must still work collapsed.
- `Telegram.WebApp.BackButton` for detail, wizard, and confirmation navigation.
- `Telegram.WebApp.MainButton` only for the current primary action; hide it on read-only screens.
- `Telegram.WebApp.SecondaryButton` only where a secondary action is genuinely needed.
- `Telegram.WebApp.showPopup()` for confirmations and `showAlert()` for blocking errors where appropriate.
- `themeChanged`, `viewportChanged`, `safeAreaChanged`, and `contentSafeAreaChanged` so content remains usable on dark mode, keyboards, notches, and orientation changes.
- `Telegram.WebApp.disableVerticalSwipes` only if the screen has a justified gesture conflict; do not disable normal Telegram navigation by default.
- `Telegram.WebApp.close()` only after explicit completion or cancellation.
- `Telegram.WebApp.onEvent('activated'/'deactivated')` to refresh stale installation state on re-entry.

The page must not require JavaScript-disabled support inside Telegram, but all destructive operations remain protected server-side. Use `themeParams` and Telegram CSS variables instead of assuming the user's theme is light.

### 7.7 Authentication and Trust Rules

- Send the exact raw `initData` string to the backend; do not reconstruct it from parsed values.
- Validate the official HMAC-SHA-256 algorithm using the bot token and constant `WebAppData`.
- Compare hashes in constant time.
- Validate `auth_date` against a short configured freshness window and reject replayed launch states.
- Support dual authentication transport (`nuecagram_webapp_session` Cookie, `X-Session-Token` header, or `Authorization: Bearer <token>` header) to accommodate webviews where third-party cookies are partitioned or restricted.
- Restore context from active session token when `startParam` lacks a launch nonce on re-authentication.
- Bind the authenticated Telegram user to every server session and re-check group administrator status for group-scoped actions.
- Do not expose the bot token, raw database credentials, webhook secret, or management token to browser logs or third-party scripts.
- Use HTTPS, `Cache-Control: no-store`, restrictive CSP, `Referrer-Policy: no-referrer`, and secure, short-lived cookies.
- Log action type and actor identity, never raw credentials or `initData`.

### 7.9 BotFather Provisioning & Bot API Capability Matrix

To register the Telegram Mini App globally with BotFather:

1. **Configure Mini App URL**:
   - Open `@BotFather` -> `/mybots` -> Select Bot -> **Bot Settings** -> **Configure Mini App** -> **Enable Mini App**.
   - Set URL: `${NUECAGRAM_PUBLIC_URL}/webapp`.
2. **Configure Menu Button**:
   - Set Bot Menu Button via BotFather or dynamically via Bot API: `setChatMenuButton`.
   - Payload: `{"menu_button": {"type": "web_app", "text": "Manage Bot", "web_app": {"url": "${NUECAGRAM_PUBLIC_URL}/webapp"}}}`.
3. **Inline Keyboard Buttons in Slash Commands**:
   - When responding to `/start`, `/setup`, `/manage`, `/status`, attached inline keyboards use `InlineKeyboardButton` with `web_app = WebAppInfo(url = "${NUECAGRAM_PUBLIC_URL}/webapp?startapp=...")`.

### 7.10 Kotlin Serialization & Bot API Data Model Extensions

To support Web App launch buttons and payload parsing in `TelegramUpdateHandler`, extend `net.raquezha.nuecagram.telegram`:

```kotlin
@Serializable
data class WebAppInfo(
    val url: String,
)

@Serializable
data class InlineKeyboardButton(
    val text: String,
    @SerialName("web_app")
    val webApp: WebAppInfo? = null,
    @SerialName("callback_data")
    val callbackData: String? = null,
)

@Serializable
data class InlineKeyboardMarkup(
    @SerialName("inline_keyboard")
    val inlineKeyboard: List<List<InlineKeyboardButton>>,
)

@Serializable
data class WebAppData(
    val data: String,
    @SerialName("button_text")
    val buttonText: String,
)
```

### 7.11 Launch Nonce & Secure Context Resolution Architecture

Because Telegram `initData` does not guarantee a `chat` object in all launch modes (e.g. menu button launch from a group chat), Nuecagram implements a **Server-Issued Launch Nonce Model**:

```text
[ Telegram Group / Topic Chat ]
            │
            ├─ 1. User runs /manage or /setup
            ▼
[ Backend: TelegramUpdateHandler ]
            │
            ├─ 2. Generates 10-min Single-Use Launch Nonce (UUID + HMAC)
            ├─ 3. Stores in `telegram_launch_nonces` table:
            │     { nonce_digest, telegramChatId, telegramTopicId, actorUserId, expiresAt }
            │
            ▼
[ Sent Telegram Response Message ]
            │  Inline Keyboard Button:
            │  web_app = WebAppInfo("${NUECAGRAM_PUBLIC_URL}/webapp?startapp=nonce_<raw_nonce>")
            ▼
[ User Clicks Web App Button ]
            │  Client sends raw `initData` + `startapp` parameter to `POST /api/webapp/auth`
            ▼
[ Backend Web App Auth Handler ]
            │
            ├─ 4. Validates initData HMAC hash
            ├─ 5. Consumes launch nonce from DB (single-use check)
            ├─ 6. Binds validated user to `telegramChatId` and `telegramTopicId`
            └─ 7. Issues `nuecagram_webapp_session` cookie + CSRF token
```

### 7.12 Telegram Admin Authorization & Access Control Rules

- **Group-Bound Access**: Backend queries `telegramService.chatMemberStatus(telegramChatId, telegramUserId)`. Only `creator` or `administrator` statuses pass.
- **Admin Status Cache**: To prevent hitting Telegram API rate limits during active management sessions, admin status is cached in memory for 300 seconds (5 minutes) per `(telegramChatId, telegramUserId)`.
- **DM Multi-Group Access**: In private DM context, backend lists all installations where `telegramChatId` matches a group where the user is an authorized admin.

### 7.13 Session Storage, Replay Protection, & Database Schema

Extend `src/main/kotlin/net/raquezha/nuecagram/db/Tables.kt`:

1. **`telegram_launch_nonces` Table**:
   - `id`: UUID (Primary Key)
   - `nonce_digest`: ByteArray (SHA-256)
   - `telegram_chat_id`: Long
   - `telegram_topic_id`: Long (Nullable)
   - `telegram_user_id`: Long
   - `expires_at`: Instant (10 minutes)
   - `consumed_at`: Instant (Nullable)

2. **`telegram_webapp_nonces` Table (Replay Protection)**:
   - `hash_digest`: ByteArray (Primary Key)
   - `created_at`: Instant

3. **`webapp_sessions` Table**:
   - `id`: UUID (Primary Key)
   - `telegram_user_id`: Long
   - `telegram_chat_id`: Long (Nullable)
   - `telegram_topic_id`: Long (Nullable)
   - `token_digest`: ByteArray
   - `token_hash`: String
   - `csrf_digest`: ByteArray
   - `csrf_hash`: String
   - `expires_at`: Instant (8 hours)

### 7.14 Security Headers & Content-Security-Policy (CSP)

Web App route `$basePath/webapp` responds with the following hardened headers:

```http
Content-Security-Policy: default-src 'self'; script-src 'self' https://telegram.org; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data: https:; frame-ancestors 'self' https://web.telegram.org https://*.telegram.org https://telegram.org;
Cache-Control: no-store, no-cache, must-revalidate
Pragma: no-cache
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Strict-Transport-Security: max-age=31536000; includeSubDomains
```

### 7.15 Bot API Rate Limiting & Resilience Policy

- **Group Dispatch Limit**: Max 20 messages per minute per chat.
- **Private DM Dispatch Limit**: Max 30 messages per second.
- **HTTP 429 Retry Strategy**: On Telegram `429 Too Many Requests`, parse `retry_after` header/body, delay coroutine execution, and retry up to 3 times before returning a structured error.
- **HTTP 403 / Bot Removal**: If `getChatMember` or message send returns `403 Forbidden` (bot kicked or DM blocked), flag installation state as `Degraded` in database and append audit log.

---

## 8. Implementation Sub-Tasks Decomposition (#102 - #105)

The implementation of Telegram Web App-first UX is decomposed into four sequential, independently verifiable sub-task issues (#102, #103, #104, #105):

```text
#101 Design Spec (This Issue)
        │
        ▼
#102 Auth & Session Foundation ────► #103 Installation Dashboard
                                              │
                                              ▼
#105 Integration & Rollout ◄─────── #104 Setup Wizard & Secrets
```

---

### Task #102: Build Telegram Web App launch, auth, and session foundation

- **Parent Feature**: #100
- **Scope**: Backend authentication, HMAC validation, launch nonce storage, and Web App HTML container.
- **Sub-Slices**:
  - **Sub-task 102.1**: Add `TelegramWebAppData` parser & HMAC-SHA256 validator in `net.raquezha.nuecagram.telegram`.
  - **Sub-task 102.2**: Add `telegram_launch_nonces` and `webapp_sessions` Exposed tables in `net.raquezha.nuecagram.db.Tables.kt` and repository helpers.
  - **Sub-task 102.3**: Implement `POST /api/webapp/auth` endpoint issuing `nuecagram_webapp_session` cookie + CSRF token.
  - **Sub-task 102.4**: Implement `GET /webapp` HTML shell route with hardened CSP security headers (`script-src https://telegram.org`).
- **Acceptance Criteria**:
  - [ ] Valid `initData` string passes HMAC-SHA256 signature verification.
  - [ ] Invalid or tampered `initData` returns `HTTP 401 Unauthorized`.
  - [ ] Expired `auth_date` (> 24 hours) is rejected.
  - [ ] Single-use launch nonces are consumed atomically and cannot be replayed.
  - [ ] `GET /webapp` returns HTML page referencing `telegram-web-app.js` with correct CSP headers.
- **Verification Commands**:
  - `./gradlew test --tests "net.raquezha.nuecagram.telegram.TelegramWebAppAuthTest"`

---

### Task #103: Build context-aware Telegram Web App installation dashboard and actions

- **Parent Feature**: #100
- **Dependencies**: #102
- **Scope**: Web App Dashboard UI, context filtering (Group vs Topic vs DM), mute toggle, and test notification dispatch.
- **Sub-Slices**:
  - **Sub-task 103.1**: Implement REST endpoint `GET /api/webapp/installations` with `telegramChatId` and `telegramTopicId` context resolution.
  - **Sub-task 103.2**: Implement action endpoints `POST /api/webapp/installations/{id}/mute` and `POST /api/webapp/installations/{id}/test`.
  - **Sub-task 103.3**: Build mobile HTML/CSS Dashboard interface displaying installation cards, status badges, mute/unmute buttons, and test delivery buttons.
  - **Sub-task 103.4**: Wire Telegram Web App SDK `MainButton` and `BackButton` for navigation and action triggers.
- **Acceptance Criteria**:
  - [ ] Launching inside a topic filters installations strictly to `(telegramChatId, telegramTopicId)`.
  - [ ] Launching in main chat lists all installations for `telegramChatId`.
  - [ ] Mute toggle updates database state and returns updated `Muted` badge.
  - [ ] Test button dispatches test notification to target chat/topic and records `webapp_test` audit event.
  - [ ] Non-admin users are rejected with `HTTP 403 Forbidden`.
- **Verification Commands**:
  - `./gradlew test --tests "net.raquezha.nuecagram.webapp.WebDashboardTest"`

---

### Task #104: Build guided setup wizard and secure secret delivery for Telegram Web App

- **Parent Feature**: #100
- **Dependencies**: #102, #103
- **Scope**: Guided setup wizard UI, repository creation, credential rotation, and single-view secret box.
- **Sub-Slices**:
  - **Sub-task 104.1**: Build Setup Wizard HTML/CSS step-by-step form (GitLab Base URL, Project ID, Target Destination).
  - **Sub-task 104.2**: Implement endpoint `POST /api/webapp/installations` for creating installations and issuing initial webhook secrets.
  - **Sub-task 104.3**: Implement endpoint `POST /api/webapp/installations/{id}/rotate` for revoking old credentials and generating new ones.
  - **Sub-task 104.4**: Build single-view secret reveal component with copy helpers, DM bootstrap validation, and audit event logging (`webapp_setup`, `webapp_rotate`).
- **Acceptance Criteria**:
  - [ ] Valid GitLab URL and Project ID creates a new `InstallationRecord` bound to launch chat/topic context.
  - [ ] Issued secret is presented in raw form exactly once; subsequent views display masked metadata.
  - [ ] Secret rotation revokes previous secret token and issues fresh credential.
  - [ ] Attempting setup without prior private DM `/start` prompts user to open private DM chat.
  - [ ] Audit logs store `actor_type = "webapp_session"`.
- **Verification Commands**:
  - `./gradlew test --tests "net.raquezha.nuecagram.webapp.WebSetupWizardTest"`

---

### Task #105: Polish rollout, fallback commands, and test coverage for Telegram Web App-first UX

- **Parent Feature**: #100
- **Dependencies**: #102, #103, #104
- **Scope**: Slash command inline buttons, documentation polish, end-to-end integration test suite, and release gate verification.
- **Sub-Slices**:
  - **Sub-task 105.1**: Update `TelegramUpdateHandler.kt` to attach Inline Web App Launch buttons (`InlineKeyboardButton(text, web_app = WebAppInfo(...))`) to `/start`, `/setup`, and `/manage` replies.
  - **Sub-task 105.2**: Verify and document slash command fallback matrix in `docs/onboarding.md` and `README.md`.
  - **Sub-task 105.3**: Build comprehensive Ktor `testApplication` integration test suite verifying Web App routes alongside GitLab webhook ingest.
  - **Sub-task 105.4**: Run full local gate: `./gradlew lintKotlinMain lintKotlinTest detekt test build` and `./gradlew clean test`.
- **Acceptance Criteria**:
  - [ ] Replies to `/manage`, `/setup`, and `/start` include `InlineKeyboardButton` launching Web App.
  - [ ] Slash commands remain 100% operational as text fallbacks when Web App is unavailable.
  - [ ] All unit, integration, and detekt lint checks pass cleanly.
  - [ ] Operations and onboarding documentation updated for release.
- **Verification Commands**:
  - `./gradlew lintKotlinMain lintKotlinTest detekt test build`
  - `./gradlew clean test`



### Issue #104: Guided setup wizard and secure secret delivery for Telegram Web App
- **Deliverables**:
  - Setup Wizard UI flow in Web App for connecting new GitLab projects.
  - Endpoint `POST /api/webapp/installations` for creation and `POST /api/webapp/installations/{id}/rotate` for secret rotation.
  - One-time credential view component with copy helpers and DM bootstrap validation.
  - Integration tests for installation creation, secret issuing, and rotation audit events.

### Issue #105: Polish rollout, fallback, and test coverage for Telegram Web App-first UX
- **Deliverables**:
  - Updated `TelegramUpdateHandler` attaching Inline Web App buttons to `/start`, `/setup`, and `/manage` replies.
  - Updated `docs/onboarding.md` and `README.md` reflecting Web App-first management.
  - Complete integration test suite verifying slash command fallbacks alongside Web App endpoints.
  - Pre-commit & CI gate verification (`lintKotlinMain`, `detekt`, `test`, `build`).
