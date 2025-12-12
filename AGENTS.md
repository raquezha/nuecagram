# Agent Guidelines

## Important
All code will be reviewed by another AI agent. Shortcuts, simplifications, placeholders, and fallbacks are not allowed—they waste time and will require rework. Write complete, production-ready code the first time.

For long answers, always include a **TLDR;** at the top.

## Build & Test Commands
- **Build:** `./gradlew build`
- **Run tests:** `./gradlew test`
- **Single test class:** `./gradlew test --tests "net.raquezha.nuecagram.ApplicationTest"`
- **Single test method:** `./gradlew test --tests "net.raquezha.nuecagram.ApplicationTest.testRoot"`
- **Lint:** `./gradlew lintKotlinMain lintKotlinTest`
- **Format:** `./gradlew formatKotlinMain formatKotlinTest`
- **Run app:** `./gradlew run`

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

## Tech Stack: Kotlin, Ktor, Koin (DI), kotlinx.serialization, JUnit4, MockK
