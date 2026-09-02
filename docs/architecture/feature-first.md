# Feature-First Package Layout

Nuecagram's codebase under `src/main/kotlin/net/raquezha/nuecagram/` is structured by feature domain.

```text
src/main/kotlin/net/raquezha/nuecagram/
├── Application.kt              # Entry point, Ktor embedded server & module setup
├── Config.kt                   # Immutable app configuration & URL normalization logic
├── ConfigWithSecrets.kt        # Config data class containing secret environment variables
├── db/                         # Database persistence adapter
│   ├── Tables.kt               # Exposed table definitions & Flyway schema mapping
│   ├── DatabaseFactory.kt      # HikariCP pool setup & Flyway migration runner
│   ├── InstallationRepository.kt# Installation CRUD, secret hashing & token verification
│   └── CredentialCodec.kt     # Hashing and encryption helper utilities
├── di/                         # Dependency Injection wiring
│   └── Module.kt               # Koin module definitions & bean registrations
├── plugins/                    # Ktor HTTP routing & plugin setup
│   ├── Routing.kt              # Main HTTP routing pipeline & background coroutine tasks
│   ├── TelegramRouting.kt      # Telegram bot update Webhook endpoint
│   ├── ManagementRouting.kt    # Admin management Web UI & token endpoints
│   ├── PlatformAdminRouting.kt # Superadmin platform API endpoints
│   └── Serialization.kt        # kotlinx.serialization JSON content negotiation
├── telegram/                   # Telegram bot integration adapter
│   ├── TelegramService.kt      # Outbound port interface for Telegram API
│   ├── TelegramServiceImpl.kt  # Implementation using HTTP client / vendeli bot
│   ├── TelegramUpdateHandler.kt# Handling incoming Telegram bot updates (/help, /start, etc.)
│   └── Message.kt              # Value objects for Telegram messages and formatting
└── webhook/                    # GitLab Webhook handling & message formatting
    ├── WebhookRequestHandler.kt# Event processing channel consumer & dispatching logic
    ├── WebHookService.kt       # Inbound HTTP handler, rate limiting & pipeline tracking state
    ├── WebhookMessageFormatter.kt# HTML formatter transforming GitLab events into Telegram HTML
    ├── EventData.kt            # GitLab event wrappers & data structures
    ├── ChatDetails.kt          # Destination chat and thread/topic details
    ├── JobInfo.kt              # Pipeline job metadata tracking
    ├── NuecagramHeaders.kt     # Custom HTTP headers for GitLab webhooks
    └── SkipEventException.kt   # Exception thrown to drop unhandled or muted events
```

## Component Interactions

```text
[ GitLab ] ──(HTTP POST)──> [ Routing.kt / Webhook ]
                                    │
                                    ▼
                         [ WebHookService.kt ] ──(Verify Secret)──> [ InstallationRepository.kt ]
                                    │
                              (Enqueue Event)
                                    │
                                    ▼
                     [ WebhookRequestHandler.kt ]
                                    │
                         (Format HTML Message)
                                    │
                                    ▼
                    [ WebhookMessageFormatter.kt ]
                                    │
                           (Dispatch Message)
                                    │
                                    ▼
                        [ TelegramServiceImpl.kt ] ──(HTTP POST)──> [ Telegram API ]
```
