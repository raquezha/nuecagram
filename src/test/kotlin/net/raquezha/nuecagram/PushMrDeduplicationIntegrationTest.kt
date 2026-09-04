package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

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
        assertThat(pushMsg?.text).contains("nuecalytics")
        assertThat(pushMsg?.text).contains("(!42)")
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
    fun testMrCloseClearsActiveMrState() = testApplication {
        configureTestApplication()
        val commitSha = "abc2222"

        postMrOpenEvent(commitSha)
        awaitActiveMr("nuecalytics", 42L)

        postMrCloseEvent()
        awaitClearedActiveMr("nuecalytics")
    }

    private suspend fun ApplicationTestBuilder.postMrOpenEvent(commitSha: String) {
        val payload = """
{
  "object_kind": "merge_request",
  "event_type": "merge_request",
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
