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
- Treat `initDataUnsafe`, URL query parameters, `start_param`, and client-supplied chat/topic IDs as untrusted hints.
- Bind the authenticated Telegram user to every server session and re-check group administrator status for group-scoped actions.
- Do not expose the bot token, raw database credentials, webhook secret, or management token to browser logs or third-party scripts.
- Use HTTPS, `Cache-Control: no-store`, restrictive CSP, `Referrer-Policy: no-referrer`, and secure, short-lived cookies.
- Log action type and actor identity, never raw credentials or `initData`.

### 7.8 Accessibility and Mobile Requirements

- Minimum 44px touch targets for primary controls.
- Visible keyboard focus and sufficient contrast in light and dark Telegram themes.
- Labels must remain visible; do not use placeholder text as the only label.
- Error text must be adjacent to the invalid field and announced accessibly.
- Support narrow screens, dynamic viewport height, keyboard opening, safe-area insets, and long GitLab URLs without horizontal scrolling.
- Keep the primary action reachable without requiring a precise swipe gesture.

## 8. Implementation Slices Validation (#102 - #105)

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
