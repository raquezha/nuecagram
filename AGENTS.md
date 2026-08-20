# Agent Guidelines

## Important
All code will be reviewed by another AI agent. Shortcuts, simplifications, placeholders, and fallbacks are not allowed—they waste time and will require rework. Write complete, production-ready code the first time.

For long answers, always include a **TLDR;** at the top.

## Build & Test Commands
- **Build:** `./gradlew build`
- **Clean build:** `./gradlew clean build`
- **Run tests:** `./gradlew test`
- **Single test class:** `./gradlew test --tests "net.raquezha.nuecagram.ApplicationTest"`
- **Single test method:** `./gradlew test --tests "net.raquezha.nuecagram.ApplicationTest.testRoot"`
- **Lint (ktlint):** `./gradlew lintKotlinMain lintKotlinTest`
- **Format (ktlint):** `./gradlew formatKotlinMain formatKotlinTest`
- **Static analysis (detekt):** `./gradlew detekt`
- **Generate detekt baseline:** `./gradlew detektBaseline`
- **Run app:** `./gradlew run`

## Commit/Push Gate (Required)
- Before every commit or push, run the full local gate: `./gradlew lintKotlinMain lintKotlinTest detekt test build`.
- Also run the CI-equivalent clean test job: `./gradlew clean test`.
- If you changed tests, routing, shared fixtures, static/global state, or system-property/env-based config, run `./gradlew clean test` twice before push to catch order-dependent flakes.
- If any part of that gate fails, do not commit, do not push, and fix the failure first.
- If a change touches one focused test area, you may run that focused test first for speed, but the full gate still must pass before commit/push.
- Treat CI as confirmation, not first discovery. Catch lint, test, and build failures locally before sending them upstream.
- If AI contributed to a commit, the commit message must include an exact `Assisted-by` trailer. Get the exact value with `bash ~/RQZ/personal/nothing/packages/workflows/norpiv/scripts/get-pi-model.sh` and use that exact output in the trailer, for example `Assisted-by: openai-codex:gpt-5.4 [read,bash,edit,write]`.
- Before push, verify the actual committed trailer matches the current helper output; if not, amend the commit before pushing so `/verify` does not fail on trailer drift.

## Branching & Release Management Protocol
- **Zero Direct Push to `main`**: All code changes (features, bugfixes, docs, or version bumps) MUST be created on dedicated branches (`feat/...`, `fix/...`, `chore/...`) and merged via a Pull Request. Direct pushes to `main` are strictly prohibited.
- **Automatic Issue Linking**: Every PR description MUST include explicit closing keywords (`Closes #<id>` or `Fixes #<id>`) to ensure GitHub automatically closes the corresponding issue upon merge.
- **Proactive Release & Version Bump Suggestions**:
  - After completing feature/fix slices, the agent MUST check if a version bump is needed (`patch` or `minor`).
  - The agent MUST proactively suggest or create a release PR (`chore(release): bump version to x.y.z`) updating `version.txt` and `src/main/resources/version.txt`.
  - Merging the release PR triggers the automated production deployment pipeline with the updated version string.

## Pull Request Standards (Open Source Specification)

### 1. PR Title Format
PR titles must follow the **Conventional Commits** specification (`<type>(<scope>): <description>` in lowercase):
- `feat`: New feature or capability (e.g., `feat(telegram): preserve topic thread ID on command replies`)
- `fix`: Bug fix (e.g., `fix(ci): decode base64 SSH key in deploy workflow`)
- `docs`: Documentation updates (e.g., `docs(operations): update deployment guide`)
- `ci`: CI/CD workflow updates (e.g., `ci(github): bump setup-java to v5`)
- `test`: Test suite additions or fixes (e.g., `test(mr): resolve async race condition`)
- `refactor`: Code refactoring with no functional change

### 2. PR Body Contract
PR descriptions must be clean, concise, and reviewer-focused. **NEVER paste raw CLI terminal output, Gradle logs, or build dumps into PR descriptions.**

Use this exact structure:

```md
## Summary
- <1 to 3 bullets describing what changed and why>

## Scope
- <files or components modified>

## Verification
- [x] `./gradlew lintKotlinMain lintKotlinTest`
- [x] `./gradlew detekt`
- [x] `./gradlew test`
- [x] `./gradlew build`

## Risk / Rollback
- Risk: <Low / Medium / High with reason>
- Rollback: <revert commit or restore previous state>
```

## Project Structure

```
src/main/kotlin/net/raquezha/nuecagram/
├── Application.kt              # Ktor application entry point
├── Config.kt                   # Configuration data classes
├── ConfigWithSecrets.kt        # Configuration with sensitive data
├── di/                         # Dependency Injection (Koin)
│   ├── Module.kt               # Koin module definitions
│   ├── SystemEnv.kt            # Environment variable interface
│   └── SystemEnvImpl.kt        # Environment variable implementation
├── plugins/                    # Ktor plugins
│   ├── Routing.kt              # HTTP route definitions
│   ├── Serialization.kt        # JSON serialization config
│   └── TelegramWebhook.kt      # Telegram bot webhook handler
├── telegram/                   # Telegram integration
│   ├── Message.kt              # Message data classes
│   ├── TelegramService.kt      # Telegram service interface
│   ├── TelegramServiceImpl.kt  # Telegram API implementation
│   ├── MockTelegramService.kt  # Mock for testing
│   ├── TokenProvider.kt        # Bot token provider interface
│   └── TokenProviderImpl.kt    # Bot token provider implementation
└── webhook/                    # GitLab webhook processing
    ├── ChatDetails.kt          # Telegram chat details
    ├── EventData.kt            # GitLab event data classes
    ├── NuecagramHeaders.kt     # Custom HTTP headers
    ├── RandomCommentMessage.kt # Random message generator
    ├── SkipEventException.kt   # Exception for skipping events
    ├── WebhookMessageFormatter.kt  # Format events to Telegram messages
    ├── WebhookRequestHandler.kt    # Handle incoming webhooks
    ├── WebHookService.kt       # Webhook service interface
    └── WebHookServiceImpl.kt   # Webhook service implementation
```

## Test Structure

```
src/test/kotlin/net/raquezha/nuecagram/
├── ApplicationTest.kt          # Basic application tests
├── BaseEventTestHelper.kt      # Shared test utilities and constants
├── DeploymentEventWebhookTest.kt
├── IssueEventWebhookTest.kt
├── JobEventWebhookTest.kt
├── MergeRequestWebhookTest.kt
├── NoteEventWebhookTest.kt
├── PipelineEventWebhookTest.kt
├── PushEventWebhookTest.kt
├── ReleaseEventWebhookTest.kt
├── TagEventWebhookTest.kt
└── WikiPageEventWebhookTest.kt
```

## Architecture

### Request Flow
1. GitLab sends webhook POST to the configured base path, for example `/nuecagram/webhook`
2. `WebhookRequestHandler` validates `X-Gitlab-Token` against the installation store and parses the event
3. `WebhookMessageFormatter` formats event into Telegram message
4. `TelegramService` sends message to the stored installation destination
5. For pipeline events, messages are consolidated (create/update pattern)

### Key Components
- **WebhookRequestHandler**: Routes events to appropriate handlers based on `X-Gitlab-Event` header
- **WebhookMessageFormatter**: Converts GitLab events to formatted Telegram messages (HTML)
- **WebHookService**: Manages pipeline message ID tracking for consolidation
- **TelegramService**: Wraps Telegram Bot API (send/edit messages)

### Pipeline Message Consolidation
Pipeline and job events are consolidated into a single updating message per pipeline:
- First event creates a new message, stores `pipelineId -> messageId`
- Subsequent events update the existing message
- Shows job tree with status icons and timing

## Code Style (Kotlinter/ktlint & detekt enforced)
- Wildcard imports are allowed (ktlint rule disabled in `.editorconfig`)
- Remove trailing whitespace; ensure files end with newline
- Generated code in `generated/` is excluded from linting

## SOLID Principles & Clean Kotlin Guidelines
- **SOLID Architecture (Enforced)**:
  - **Single Responsibility (SRP)**: Keep handler functions, routes, and services focused strictly on one responsibility. Separate request parsing, context resolution, and validation from execution/dispatch.
  - **Open-Closed (OCP)**: Use sealed interfaces and polymorphic handlers rather than modifying core routing when adding capabilities.
  - **Interface Segregation (ISP) & Dependency Inversion (DIP)**: Define focused interfaces (`TelegramService`, `InstallationRepository`) and inject dependencies via Koin.
- **Idiomatic Kotlin Expressions**:
  - **Always prefer Kotlin `when` expressions**, `takeIf`, `let`, `runCatching`, and functional constructs over imperative nested `if-else` branching and scattered return guards.
  - **Use single-expression function bodies** (`= when { ... }`) where appropriate for transformation, mapping, and decision functions.
- **Detekt Guidelines**:
  - Maintain low cyclomatic and cognitive complexity. Avoid code smells like `CyclomaticComplexMethod`, `LongMethod`, or `TooManyFunctions`.
  - Keep function length short, focused, and idiomatic.

## Naming Conventions
- **Packages:** lowercase dot-separated (`net.raquezha.nuecagram`)
- **Classes:** PascalCase; interfaces have no prefix, impls use `*Impl` suffix
- **Test classes:** End with `Test` (e.g., `ApplicationTest`)
- **Constants:** SCREAMING_SNAKE_CASE in companion objects

## Error Handling
- Use custom exceptions (e.g., `SkipEventException`) for flow control
- Wrap async operations in try-catch; log errors via `KLogger`

## Environment Variables
- `TELEGRAM_BOT_TOKEN`: Telegram bot token from BotFather
- `TELEGRAM_WEBHOOK_SECRET`: Telegram webhook header secret
- `PLATFORM_ADMIN_PASSWORD`: platform admin password
- `NUECAGRAM_PUBLIC_URL`: public application root, including any path prefix
- `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`: PostgreSQL connection

## Deployment
Use the single `compose.yaml` with a private `.env` copied from `env.example` for local and production deployment. Production stores that file at `/opt/nuecagram/.env` and uses the protected workflow documented in `docs/operations.md`.

## Tech Stack
- **Language:** Kotlin 1.9.24
- **Framework:** Ktor (server + client)
- **DI:** Koin with annotations
- **Serialization:** kotlinx.serialization, Gson, Jackson
- **Telegram:** vendeli telegram-bot library
- **GitLab:** gitlab4j-api for event parsing
- **Testing:** JUnit4, MockK, Google Truth
- **Linting:** Kotlinter (ktlint)
