# Guide: Adding a New GitLab Event Type

This guide explains how to add support for a new GitLab webhook event type (e.g., standard comments, custom alerts, or security scan events).

---

## Step 1: Register Supported Event Header

In `net.raquezha.nuecagram.webhook.WebHookService.kt`, add the new GitLab header event constant to `supportedEvents`:

```kotlin
private val supportedEvents = setOf(
    IssueEvent.X_GITLAB_EVENT,
    // ... existing events
    YourNewEvent.X_GITLAB_EVENT, // Add your event here
)
```

---

## Step 2: Handle Event in WebhookRequestHandler

In `net.raquezha.nuecagram.webhook.WebhookRequestHandler.kt`, extend the `processEvent` when-expression:

```kotlin
private suspend fun processEvent(data: EventData, ctx: EventProcessingContext) {
    when (val event = data.event) {
        is PipelineEvent -> handlePipelineEvent(...)
        is YourNewEvent -> handleYourNewEvent(data.installationId, event, data.chatDetails, ctx)
        else -> handleGenericEvent(data.event, data.chatDetails, ctx)
    }
}
```

Implement `handleYourNewEvent` using `ctx.formatter` and `ctx.telegramService`.

---

## Step 3: Add Formatter Method

In `net.raquezha.nuecagram.webhook.WebhookMessageFormatter.kt`, add a custom HTML message builder method:

```kotlin
fun formatYourNewEvent(event: YourNewEvent): String {
    val builder = StringBuilder()
    // Build HTML string using allowed Telegram tags: <b>, <i>, <a>, <code>, <pre>
    return builder.toString()
}
```

---

## Step 4: Write Integration Test

Create a new test class in `src/test/kotlin/net/raquezha/nuecagram/YourNewEventWebhookTest.kt`:

1. Extend `BaseEventTestHelper`.
2. Load sample JSON event payload.
3. Post event to `/webhook` endpoint in Ktor `testApplication`.
4. Verify HTTP 200 response and assert `mockTelegramService` received correctly formatted HTML message.

---

## Step 5: Verify Quality Gates

Run the local test and lint gate:

```bash
./gradlew lintKotlinMain lintKotlinTest detekt test build
```
