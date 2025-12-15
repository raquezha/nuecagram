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
1. GitLab sends webhook POST to `/webhook`
2. `WebhookRequestHandler` validates headers and parses event
3. `WebhookMessageFormatter` formats event into Telegram message
4. `TelegramService` sends message to configured chat
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

## Code Style (Kotlinter/ktlint enforced)
- Wildcard imports are allowed (ktlint rule disabled in `.editorconfig`)
- Remove trailing whitespace; ensure files end with newline
- Generated code in `generated/` is excluded from linting

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
- `NUECAGRAM_SECRET_TOKEN`: Secret token for webhook validation

## Deployment
Deployment is tag-triggered via GitHub Actions (`.github/workflows/docker-deploy.yml`):
```bash
git tag v1.2.3
git push origin v1.2.3
```
This builds Docker image, pushes to Docker Hub, and creates GitHub Release.

## Tech Stack
- **Language:** Kotlin 1.9.24
- **Framework:** Ktor (server + client)
- **DI:** Koin with annotations
- **Serialization:** kotlinx.serialization, Gson, Jackson
- **Telegram:** vendeli telegram-bot library
- **GitLab:** gitlab4j-api for event parsing
- **Testing:** JUnit4, MockK, Google Truth
- **Linting:** Kotlinter (ktlint)
