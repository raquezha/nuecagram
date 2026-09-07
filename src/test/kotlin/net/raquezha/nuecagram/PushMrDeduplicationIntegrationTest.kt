package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

@Suppress("TooManyFunctions")
class PushMrDeduplicationIntegrationTest : BaseEventTestHelper() {

    @Test
    fun testPushMessageIncludesMrBadgeWhenMrIsOpen() = testApplication {
        configureTestApplication()
        val commitSha = "abc2222"

        postMrOpenEvent(commitSha)
        awaitActiveMr("nuecalytics", 42L)

        postPushEvent(commitSha)
        awaitLatestPushSha("nuecalytics", commitSha)

        val pushMsg = awaitSentMessage { it.text.contains("Push to") }
        assertThat(pushMsg).isNotNull()
        assertThat(pushMsg?.text).contains("Push to <b>nuecalytics</b>")
        assertThat(pushMsg?.text).contains("!42")
        assertThat(pushMsg?.text).contains("https://gitlab.com/android-team/dispatcher-app/-/merge_requests/42")
    }

    @Test
    fun testRedundantMrUpdateIsSkippedOnMatchingPushSha() = testApplication {
        configureTestApplication()
        val commitSha = "abc2222"

        postMrOpenEvent(commitSha)
        awaitActiveMr("nuecalytics", 42L)

        postPushEvent(commitSha)
        awaitLatestPushSha("nuecalytics", commitSha)

        postRedundantMrUpdateEvent(commitSha)

        val sent = mockTelegramService().sentMessages()
        val mrUpdateMsg = sent.find { it.text.contains("updated") && it.text.contains("!42") }
        assertThat(mrUpdateMsg).isNull()
    }

    @Test
    fun testStructuralMrPropertyUpdateSendsNotification() = testApplication {
        configureTestApplication()
        val commitSha = "abc2222"

        postMrOpenEvent(commitSha)
        awaitActiveMr("nuecalytics", 42L)

        postPushEvent(commitSha)
        awaitLatestPushSha("nuecalytics", commitSha)

        postStructuralMrTitleUpdateEvent(commitSha)

        val mrUpdateMsg = awaitSentMessage { it.text.contains("updated") && it.text.contains("!42") }
        assertThat(mrUpdateMsg).isNotNull()
    }

    @Test
    fun testMrCloseClearsActiveMrState() = testApplication {
        configureTestApplication()
        val commitSha = "abc2222"

        postMrOpenEvent(commitSha)
        awaitActiveMr("nuecalytics", 42L)

        postMrCloseEvent()
        awaitClearedActiveMr("nuecalytics")
    }

    @Test
    fun testBranchDeletionClearsActiveMrState() = testApplication {
        configureTestApplication()
        val commitSha = "abc2222"

        postMrOpenEvent(commitSha)
        awaitActiveMr("nuecalytics", 42L)

        postBranchDeletePushEvent()
        awaitClearedActiveMr("nuecalytics")
    }

    @Test
    fun testDuplicateWebhookDeliveryWithSameUuidIsSkipped() = testApplication {
        configureTestApplication()
        val commitSha = "abc9999"
        val sameUuid = "retry-uuid-12345"

        val firstRes = postWebhookWithFixedUuid(sameUuid, commitSha)
        assertThat(firstRes).isEqualTo("Webhook received successfully")

        val duplicateRes = postWebhookWithFixedUuid(sameUuid, commitSha)
        assertThat(duplicateRes).isEqualTo("Event skipped: not relevant")
    }

    private suspend fun ApplicationTestBuilder.postMrOpenEvent(commitSha: String) {
        val payload = """
{
  "object_kind": "merge_request", "event_type": "merge_request",
  "user": { "id": 1, "name": "Alice Author", "username": "alice" },
  "project": { "id": 282, "name": "dispatcher-app", "web_url": "https://gitlab.com/android-team/dispatcher-app" },
  "object_attributes": {
    "id": 99, "iid": 42, "title": "Feature branch",
    "source_branch": "nuecalytics", "target_branch": "main", "action": "open",
    "last_commit": { "id": "$commitSha", "message": "Enable crashlytics collection" }
  }
}
        """.trimIndent()
        val res = postWebhook("Merge Request Hook", payload)
        assertThat(res).isEqualTo("Webhook received successfully")
    }

    private suspend fun ApplicationTestBuilder.postPushEvent(commitSha: String) {
        val payload = """
{
  "object_kind": "push", "event_name": "push",
  "before": "abc1111", "after": "$commitSha",
  "ref": "refs/heads/nuecalytics", "user_name": "Razyl Vidal", "project_id": 282,
  "project": { "id": 282, "name": "dispatcher-app", "web_url": "https://gitlab.com/android-team/dispatcher-app" },
  "commits": [
    { "id": "$commitSha", "title": "Enable crashlytics collection", "url": "https://gitlab.com/android-team/dispatcher-app/-/commit/$commitSha" }
  ],
  "total_commits_count": 1
}
        """.trimIndent()
        val res = postWebhook("Push Hook", payload)
        assertThat(res).isEqualTo("Webhook received successfully")
    }

    private suspend fun ApplicationTestBuilder.postBranchDeletePushEvent() {
        val deleteSha = "0".repeat(40)
        val payload = """
{
  "object_kind": "push", "event_name": "push",
  "before": "abc1111", "after": "$deleteSha",
  "ref": "refs/heads/nuecalytics", "user_name": "Razyl Vidal", "project_id": 282,
  "project": { "id": 282, "name": "dispatcher-app", "web_url": "https://gitlab.com/android-team/dispatcher-app" },
  "commits": [], "total_commits_count": 0
}
        """.trimIndent()
        val res = postWebhook("Push Hook", payload)
        assertThat(res).isEqualTo("Webhook received successfully")
    }

    private suspend fun ApplicationTestBuilder.postRedundantMrUpdateEvent(commitSha: String) {
        val payload = """
{
  "object_kind": "merge_request", "event_type": "merge_request",
  "user": { "id": 1, "name": "Alice Author", "username": "alice" },
  "project": { "id": 282, "name": "dispatcher-app", "web_url": "https://gitlab.com/android-team/dispatcher-app" },
  "object_attributes": {
    "id": 99, "iid": 42, "title": "Feature branch",
    "source_branch": "nuecalytics", "target_branch": "main", "action": "update",
    "last_commit": { "id": "$commitSha", "message": "Enable crashlytics collection" }
  },
  "changes": { "updated_at": { "previous": "2024-05-21T16:24:28Z", "current": "2024-05-21T16:25:00Z" } }
}
        """.trimIndent()
        val res = postWebhook("Merge Request Hook", payload)
        assertThat(res).isEqualTo("Webhook received successfully")
    }

    private suspend fun ApplicationTestBuilder.postStructuralMrTitleUpdateEvent(commitSha: String) {
        val payload = """
{
  "object_kind": "merge_request", "event_type": "merge_request",
  "user": { "id": 1, "name": "Alice Author", "username": "alice" },
  "project": { "id": 282, "name": "dispatcher-app", "web_url": "https://gitlab.com/android-team/dispatcher-app" },
  "object_attributes": {
    "id": 99, "iid": 42, "title": "New Title",
    "source_branch": "nuecalytics", "target_branch": "main", "action": "update",
    "last_commit": { "id": "$commitSha", "message": "Enable crashlytics collection" }
  },
  "changes": { "title": { "previous": "Old Title", "current": "New Title" } }
}
        """.trimIndent()
        val res = postWebhook("Merge Request Hook", payload)
        assertThat(res).isEqualTo("Webhook received successfully")
    }

    private suspend fun ApplicationTestBuilder.postMrCloseEvent() {
        val payload = """
{
  "object_kind": "merge_request", "event_type": "merge_request",
  "user": { "id": 1, "name": "Alice Author", "username": "alice" },
  "project": { "id": 282, "name": "dispatcher-app", "web_url": "https://gitlab.com/android-team/dispatcher-app" },
  "object_attributes": {
    "id": 99, "iid": 42, "title": "Feature branch",
    "source_branch": "nuecalytics", "target_branch": "main", "action": "close"
  }
}
        """.trimIndent()
        val res = postWebhook("Merge Request Hook", payload)
        assertThat(res).isEqualTo("Webhook received successfully")
    }

    private suspend fun ApplicationTestBuilder.postWebhookWithFixedUuid(
        uuid: String,
        commitSha: String,
    ): String {
        val payload = """
{
  "object_kind": "push", "event_name": "push",
  "before": "abc1111", "after": "$commitSha",
  "ref": "refs/heads/nuecalytics", "user_name": "Razyl Vidal", "project_id": 282,
  "project": { "id": 282, "name": "dispatcher-app", "web_url": "https://gitlab.com/android-team/dispatcher-app" },
  "commits": [
    { "id": "$commitSha", "title": "Enable crashlytics collection", "url": "https://gitlab.com/android-team/dispatcher-app/-/commit/$commitSha" }
  ],
  "total_commits_count": 1
}
        """.trimIndent()
        val res = postWebhookResponse("Push Hook", payload, extraHeaders = {
            header("X-Gitlab-Webhook-UUID", uuid)
        })
        return res.bodyAsText()
    }

    private fun awaitActiveMr(branch: String, expectedIid: Long) = runBlocking {
        var activeMr = installationRepository.getActiveMrForBranch(installation.id, 282L, branch)
        for (i in 1..100) {
            if (activeMr != null) break
            delay(50)
            activeMr = installationRepository.getActiveMrForBranch(installation.id, 282L, branch)
        }
        assertThat(activeMr).isNotNull()
        assertThat(activeMr?.mrIid).isEqualTo(expectedIid)
    }

    private fun awaitLatestPushSha(branch: String, expectedSha: String) = runBlocking {
        var pushSha = installationRepository.getLatestPushSha(installation.id, 282L, branch)
        for (i in 1..100) {
            if (pushSha != null) break
            delay(50)
            pushSha = installationRepository.getLatestPushSha(installation.id, 282L, branch)
        }
        assertThat(pushSha).isEqualTo(expectedSha)
    }

    private fun awaitSentMessage(
        predicate: (net.raquezha.nuecagram.telegram.Message) -> Boolean,
    ): net.raquezha.nuecagram.telegram.Message? = runBlocking {
        var found: net.raquezha.nuecagram.telegram.Message? = null
        for (i in 1..100) {
            found = mockTelegramService().sentMessages().find(predicate)
            if (found != null) break
            delay(50)
        }
        found
    }

    private fun awaitClearedActiveMr(branch: String) = runBlocking {
        var activeMr = installationRepository.getActiveMrForBranch(installation.id, 282L, branch)
        for (i in 1..100) {
            if (activeMr == null) break
            delay(50)
            activeMr = installationRepository.getActiveMrForBranch(installation.id, 282L, branch)
        }
        assertThat(activeMr).isNull()
    }
}
