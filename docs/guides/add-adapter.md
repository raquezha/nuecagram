# Guide: Adding an External Infrastructure Adapter

This guide explains how to introduce a new external adapter (e.g., a new notification service, metric exporter, or secondary database).

---

## Step 1: Define Port Interface

Create a clean Kotlin interface under the appropriate feature package (e.g., `net.raquezha.nuecagram.metrics.MetricsService`):

```kotlin
package net.raquezha.nuecagram.metrics

interface MetricsService {
    fun recordWebhookProcessed(eventType: String)
    fun recordTelegramDispatchTime(durationMs: Long)
}
```

*Rule: Interfaces must rely on standard Kotlin types or domain data classes; avoid leaking third-party SDK dependencies in signature declarations.*

---

## Step 2: Create Implementation Class

Create the concrete adapter class (`*Impl.kt`) implementing the port:

```kotlin
package net.raquezha.nuecagram.metrics

import io.github.oshai.kotlinlogging.KLogger

class PrometheusMetricsServiceImpl(
    private val logger: KLogger,
) : MetricsService {
    override fun recordWebhookProcessed(eventType: String) {
        // Implementation details...
    }

    override fun recordTelegramDispatchTime(durationMs: Long) {
        // Implementation details...
    }
}
```

---

## Step 3: Register in Koin DI Module

In `net.raquezha.nuecagram.di.Module.kt`, define a provider function and add it to `appModule()`:

```kotlin
val provideMetricsModule = module {
    single<MetricsService> {
        PrometheusMetricsServiceImpl(get())
    }
}

fun appModule() = listOf(
    // ... existing modules
    provideMetricsModule,
)
```

---

## Step 4: Create Test Double / Mock

For integration testing, create a mock or dummy implementation (e.g., `MockMetricsService`) under `src/test/kotlin/net/raquezha/nuecagram/`:

```kotlin
class MockMetricsService : MetricsService {
    val recordedEvents = mutableListOf<String>()

    override fun recordWebhookProcessed(eventType: String) {
        recordedEvents.add(eventType)
    }
    // ...
}
```

---

## Step 5: Verify Quality Gates

Execute full local validation:

```bash
./gradlew lintKotlinMain lintKotlinTest detekt test build
```
