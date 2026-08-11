# Core Architecture Principles

Nuecagram adheres to four fundamental design principles that ensure system stability, clear ownership, and maintainability over time.

## 1. Feature-First Packaging

Code is organized by operational feature (`webhook`, `telegram`, `db`) rather than technical layer (`controllers`, `services`, `repositories`).

- High cohesion: all components necessary to handle webhooks live inside `net.raquezha.nuecagram.webhook`.
- Low coupling: features interact through explicit interfaces registered in Koin modules (`net.raquezha.nuecagram.di`).

## 2. Ports and Adapters Boundary

Infrastructure dependencies (Telegram Bot API, Exposed ORM, HikariCP pool, Flyway migrations) are kept strictly at the outer edge of the application.

- **Outbound Port**: `TelegramService` interface defines notification operations independently of the underlying HTTP client or Telegram library.
- **Persistence Boundary**: `InstallationRepository` isolates all Exposed DSL and SQL queries from application handlers.
- **Inbound Entry**: `WebhookRequestHandler` decouples HTTP request receiving from asynchronous event dispatching via internal channels.

## 3. Asynchronous Non-Blocking Processing

Webhook ingest must return HTTP 200 immediately to GitLab to prevent webhook timeout retries.

- Inbound requests are validated, authorized, and enqueued onto an in-memory buffered channel (`Channel<EventData>(capacity = 100)`).
- Background coroutine workers process events from the channel asynchronously.
- Failures in event formatting or Telegram dispatching do not cause GitLab HTTP request drops.

## 4. Single Source of Truth for State

All installation metadata, webhook secret hashes, and management tokens are stored durably in PostgreSQL using Flyway migrations.

- In-memory state is strictly temporary (rate-limiting windows, active pipeline message IDs for updating messages).
- Application nodes can be safely restarted without losing persistent installation records.
