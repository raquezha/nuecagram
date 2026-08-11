# Architectural Decisions

This document records the key architectural choices behind Nuecagram's design.

## 1. Feature-First Packaging

- **Status**: Accepted
- **Context**: Layered packaging (`controllers/`, `services/`, `repositories/`) scatters feature logic across folders, causing high cross-package coupling.
- **Decision**: Organize code by feature domain (`webhook/`, `telegram/`, `db/`, `di/`, `plugins/`).
- **Consequences**: High cohesion per feature, easy refactoring, clear code ownership.

## 2. Meaningful Ports and Adapter Interfaces

- **Status**: Accepted
- **Context**: Direct coupling to third-party SDKs (Telegram Bot API, Exposed ORM) makes domain logic hard to test and upgrade.
- **Decision**: Define clean Kotlin interfaces (`TelegramService`, `InstallationRepository`) at application boundaries.
- **Consequences**: Easy unit/integration testing via mock implementations; third-party upgrades are isolated to `*Impl` classes.

## 3. Automated Architecture Enforcement

- **Status**: Accepted
- **Context**: Package import rules and code style degrade over time without machine enforcement.
- **Decision**: Enforce code formatting, linting, static analysis, and package rules in pre-commit/CI gates (`kotlinter`, `detekt`, ArchUnit/rule checks).
- **Consequences**: Style and boundary violations fail builds locally before merge.

## 4. Behavior-Driven Integration Testing

- **Status**: Accepted
- **Context**: Pure unit tests miss request-formatting bugs; end-to-end tests requiring real APIs are slow and fragile.
- **Decision**: Test full request-to-notification flows in-memory via Ktor `testApplication` grouped by GitLab event type (`PipelineEventWebhookTest`, etc.).
- **Consequences**: Fast, high-confidence tests without external network dependencies.

## 5. Strict Database Adapter Isolation

- **Status**: Accepted
- **Context**: Leaking ORM queries (Exposed DSL) into HTTP routing or background workers couples business logic to table schemas.
- **Decision**: Isolate all database access behind `InstallationRepository`, returning immutable Kotlin data classes. Schema changes managed via Flyway.
- **Consequences**: Database refactoring requires editing only `db/` classes; zero Exposed dependencies in domain logic.

## 6. Automated Dependency Maintenance Policy

- **Status**: Accepted
- **Context**: Outdated dependencies create security risks, but unvetted auto-updates break builds.
- **Decision**: Automate updates via Renovate (`renovate.json5`) with dependency grouping (Kotlin/KSP, Ktor, Koin, Exposed, Flyway), security scanning (Trivy + Dependabot Alerts), and mandatory CI gate passing. Automerge is disabled.
- **Consequences**: Modern, secure dependencies across Gradle, GitHub Actions, Docker, and Gradle Wrapper with zero manual tracking overhead. Policy details documented in `docs/architecture/dependency-maintenance.md`.
