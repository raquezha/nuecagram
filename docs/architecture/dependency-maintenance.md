# Automated Dependency Maintenance Policy

Nuecagram uses **Renovate** to manage dependency updates across all surface layers of the repository.

## Policy Overview

1. **Tooling**: Renovate is the sole bot for routine dependency-update PRs (`renovate.json5`).
2. **Security Signals**: GitHub Dependency Graph and Dependabot Alerts provide security vulnerability detection. Dependabot version-update PRs are disabled to prevent duplicate bot activity.
3. **Schedule**: Maintenance PRs run on a weekly schedule (`before 4am on Monday` in `Asia/Manila` timezone).
4. **Automerge**: Disabled (`automerge: false`). All dependency PRs require human review and full CI pass.
5. **Major Updates**: Major version updates remain separate and non-grouped for explicit review.

## Dependency Surface Coverage

- **Gradle Version Catalog**: `gradle/libs.versions.toml`
- **Gradle Wrapper**: `gradle/wrapper/gradle-wrapper.properties`
- **GitHub Actions Workflows**: `.github/workflows/*.yml`
- **Dockerfile Base Images**: `Dockerfile`
- **Docker Compose**: `compose.yaml` (Local container services)

## Coordinated Dependency Grouping

To prevent version skew across coupled libraries, Renovate groups updates into the following families:

| Group Name | Match Pattern / Packages | Reason |
|------------|-------------------------|--------|
| `kotlin` | `org.jetbrains.kotlin.*`, `com.google.devtools.ksp` | Kotlin language, compiler plugins, and KSP must move together. |
| `ktor` | `io.ktor:*` | Ktor server, client, and plugin dependencies share unified versioning. |
| `koin` | `io.insert-koin:*` | Koin core, Ktor integration, slf4j logger, and annotations share compatibility. |
| `exposed` | `org.jetbrains.exposed:*` | Exposed ORM core, JDBC, java-time, and JSON modules must match versions. |
| `flyway` | `org.flywaydb:*` | Flyway core and database driver extensions must stay in sync. |

*Note: `testBalloon` embeds the targeted Kotlin version in its artifact name (e.g. `1.0.1-K2.4.0`) and is updated individually with manual verification.*

## Quality Gate Requirement

Every Renovate PR must pass the exact same local and CI gate required for manual code changes:

```bash
./gradlew lintKotlinMain lintKotlinTest detekt test build
```
