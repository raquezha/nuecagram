# Dependency & Import Rules

To maintain high cohesion and prevent architecture erosion, Nuecagram enforces strict dependency direction rules between package components.

## Package Dependency Matrix

| Source Package | Allowed Imports | Prohibited Imports | Reason |
|----------------|-----------------|--------------------|--------|
| `webhook/` | `db/`, `telegram/`, `di/` (via inject) | `plugins/` | Core logic must remain HTTP-framework agnostic. |
| `telegram/` | `db/` (for installation lookups) | `webhook/` | Outbound messaging must not know about GitLab webhook formatting. |
| `db/` | `org.jetbrains.exposed.*`, `java.sql.*` | `webhook/`, `telegram/`, `plugins/` | Database layer must have zero dependencies on web logic or message formatting. |
| `plugins/` | `webhook/`, `telegram/`, `db/`, `di/` | None (outer shell) | Ktor routes act as the composition root and orchestrate services. |
| `di/` | All packages | None (composition root) | Koin modules construct and wire all dependencies across packages. |

## Key Architectural Boundaries

1. **No exposed Exposed types in domain models**:
   - `InstallationRepository` returns domain data objects (`InstallationRecord`, `InstallationContext`, `VerifiedSecret`), never Exposed `ResultRow` or `Entity` objects.
2. **Koin injection boundaries**:
   - Class constructors accept explicit dependency interfaces (`TelegramService`, `KLogger`).
   - Ktor `ApplicationCall` handlers resolve services via Koin (`inject<WebHookService>()`), avoiding global singletons.
3. **Database transaction isolation**:
   - All Exposed transactions are encapsulated inside `InstallationRepository` suspended functions using `withContext(Dispatchers.IO)`.
