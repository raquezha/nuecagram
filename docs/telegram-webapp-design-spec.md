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
4. **Session Cookie**:
   - Cookie Name: `nuecagram_webapp_session`
   - Security: `HttpOnly; SameSite=Strict; Path=<basePath>/webapp` (and `Secure` on HTTPS).
   - TTL: 8 hours.
5. **CSRF Protection**: All mutating HTTP requests (`POST`, `PUT`, `DELETE`) require header `X-CSRF-Token` matching the session's stored CSRF digest.

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
  - Payload: `{ "initData": "<raw_init_data_string>", "chatId": 12345678, "topicId": 42 }`
  - Response (200): `{ "success": true, "user": { "id": 98765, "firstName": "Alice" }, "csrf": "<csrf_token>" }`

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

## 7. Implementation Slices Validation (#102 - #105)

The implementation of Telegram Web App-first UX is decomposed into four sequential, testable task issues:

### Issue #102: Telegram Web App launch, auth, and session foundation
- **Deliverables**:
  - `TelegramWebAppData` parser and HMAC-SHA256 validator class.
  - `$basePath/api/webapp/auth` endpoint and Web App session cookie handler.
  - Ktor routing shell for `$basePath/webapp` serving Telegram Web App HTML entrypoint.
  - Integration tests validating valid/invalid HMAC signatures and session creation.

### Issue #103: Context-aware Telegram Web App installation dashboard and actions
- **Deliverables**:
  - REST endpoints: `GET /api/webapp/installations`, `POST /api/webapp/installations/{id}/mute`, `POST /api/webapp/installations/{id}/test`.
  - Context resolution logic filtering installations by `telegramChatId` and `telegramTopicId`.
  - HTML/CSS Dashboard interface with installation cards, status badges, mute toggle, and test delivery button.
  - Integration tests for context-filtering and authorization boundaries.

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
