package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.Test

class RenovateIntegratedEventSequenceRegressionTest : BaseEventTestHelper() {

    @Test
    fun testReplayRenovateSampleSequenceMaintainsExactThreeCardOutputAndSuppressesBotMentions() =
        testApplication {
            configureTestApplication()
            val projectId = installation.gitlabProjectId!!

            replayScheduledMaintenance(projectId)
            replayRenovateBranch12(projectId)
            replayRenovateBranch13(projectId)
            assertExactThreeRenovateBubblesAndFailureLinks()
            assertHumanNotificationRegression()
        }

    private suspend fun ApplicationTestBuilder.replayScheduledMaintenance(projectId: Long) {
        postWebhook(EVENT_PIPELINE, maintenancePayload(projectId))
        delay(100)
        val messages = sentMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages.none { it.replyToMessageId != null }).isTrue()
        assertThat(messages.none { it.text.contains("@raquezha") }).isTrue()
    }

    private suspend fun ApplicationTestBuilder.replayRenovateBranch12(projectId: Long) {
        postWebhook(EVENT_PUSH, pushPayload(projectId, "renovate/dep-a", "sha1212", "RENOVATE"))
        delay(100)
        assertThat(sentMessages()).hasSize(1)

        postWebhook(EVENT_MERGE, mrPayload(projectId, 12, "renovate/dep-a", "sha1212", "RENOVATE"))
        delay(100)
        assertThat(sentMessages()).hasSize(2)

        val branchFail = pipelineBranchPayload(projectId, 101172, "renovate/dep-a", "sha1212", "validate", "failed")
        postWebhook(EVENT_PIPELINE, branchFail)
        delay(100)

        val branchRunning = pipelineBranchPayload(projectId, 101172, "renovate/dep-a", "sha1212", "validate", "running")
        postWebhook(EVENT_PIPELINE, branchRunning)
        delay(100)

        postWebhook(EVENT_PIPELINE, branchFail)
        delay(100)
        postWebhook(
            EVENT_PIPELINE,
            pipelineMrPayload(projectId, 101174, 12, "renovate/dep-a", "sha1212", "policy:changeset", "failed"),
        )
        for (attempt in 1..50) {
            if (sentMessages().any { it.text.contains("101174") }) break
            delay(50)
        }
        val finalCard = sentMessages().last { it.messageId == "2" }.text
        assertThat(finalCard).contains("/pipelines/101172")
        assertThat(finalCard).contains("/pipelines/101174")
        assertThat(finalCard).contains("validate")
        assertThat(finalCard).contains("policy:changeset")
    }

    private suspend fun ApplicationTestBuilder.replayRenovateBranch13(projectId: Long) {
        postWebhook(EVENT_PUSH, pushPayload(projectId, "renovate/dep-b", "sha1313", "RENOVATE2"))
        delay(100)

        postWebhook(EVENT_MERGE, mrPayload(projectId, 13, "renovate/dep-b", "sha1313", "RENOVATE2"))
        delay(100)

        val mrFail = pipelineMrPayload(projectId, 101173, 13, "renovate/dep-b", "sha1313", "policy:changeset", "failed")
        postWebhook(EVENT_PIPELINE, mrFail)
        for (attempt in 1..50) {
            if (sentMessages().any { it.text.contains("101173") }) break
            delay(50)
        }
    }

    private fun assertExactThreeRenovateBubblesAndFailureLinks() {
        val bubbles = sentMessages().filter { it.messageId == null }
        assertThat(bubbles).hasSize(3)

        val allMessages = sentMessages()
        val replies = allMessages.filter { it.replyToMessageId != null }
        assertThat(replies).isEmpty()
        assertThat(allMessages.none { it.text.contains("@RENOVATE") }).isTrue()
        assertThat(allMessages.none { it.text.contains("@RENOVATE2") }).isTrue()
        assertThat(allMessages.none { it.text.contains("@raquezha") }).isTrue()

        assertThat(allMessages.any { it.text.contains("101172") || it.text.contains("validate") }).isTrue()
        assertThat(allMessages.any { it.text.contains("101173") || it.text.contains("policy:changeset") }).isTrue()
    }

    private suspend fun ApplicationTestBuilder.assertHumanNotificationRegression() {
        val humanPayload = PipelineEventWebhookTest.SAMPLE_PAYLOAD_FAILED
            .replace("\"id\": 53480", "\"id\": 99999")
            .replace("\"username\": \"raquezha\"", "\"username\": \"alice\"")
        postWebhook(EVENT_PIPELINE, humanPayload)
        delay(100)

        val reply = sentMessages().lastOrNull { it.replyToMessageId != null }
        assertThat(reply).isNotNull()
        assertThat(reply!!.text).contains("@alice")
    }

    private companion object {
        private fun maintenancePayload(projectId: Long) = """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 101171, "ref": "main", "sha": "c0ffee1", "source": "schedule", "status": "success",
    "detailed_status": "passed", "stages": ["maintain"], "duration": 300,
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/101171"
  },
  "user": { "id": 38, "name": "raquezha", "username": "raquezha" },
  "project": { "id": $projectId, "name": "customer-app", "web_url": "https://gitlab.com/android-team/customer-app" },
  "builds": [ { "id": 1104100, "stage": "maintain", "name": "maintain:renovate", "status": "success" } ]
}
        """.trimIndent()

        private fun pushPayload(projectId: Long, ref: String, sha: String, author: String) = """
{
  "object_kind": "push", "event_name": "push", "before": "00000000", "after": "$sha",
  "ref": "refs/heads/$ref", "user_name": "$author", "user_username": "project_599_bot",
  "project_id": $projectId,
  "project": { "id": $projectId, "name": "customer-app", "web_url": "https://gitlab.com/android-team/customer-app" },
  "commits": [ { "id": "$sha", "title": "Update deps", "author": { "name": "$author" } } ],
  "total_commits_count": 1
}
        """.trimIndent()

        private fun mrPayload(projectId: Long, iid: Long, branch: String, sha: String, author: String) = """
{
  "object_kind": "merge_request", "event_type": "merge_request",
  "user": { "id": 1, "name": "$author", "username": "project_599_bot" },
  "project": { "id": $projectId, "name": "customer-app", "web_url": "https://gitlab.com/android-team/customer-app" },
  "object_attributes": {
    "id": ${100 + iid}, "iid": $iid, "title": "Update deps $iid", "source_branch": "$branch",
    "target_branch": "main", "action": "open", "last_commit": { "id": "$sha", "message": "Update deps" },
    "url": "https://gitlab.com/android-team/customer-app/-/merge_requests/$iid"
  }
}
        """.trimIndent()

        private fun pipelineBranchPayload(
            projectId: Long,
            id: Long,
            ref: String,
            sha: String,
            job: String,
            status: String,
        ) = """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": $id, "ref": "$ref", "sha": "$sha", "source": "push", "status": "$status",
    "detailed_status": "$status", "stages": ["test"], "duration": 300,
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/$id"
  },
  "user": { "id": 1, "name": "RENOVATE", "username": "project_599_bot" },
  "project": { "id": $projectId, "name": "customer-app", "web_url": "https://gitlab.com/android-team/customer-app" },
  "builds": [ { "id": 1104226, "stage": "test", "name": "$job", "status": "$status" } ]
}
        """.trimIndent()

        private fun pipelineMrPayload(
            projectId: Long,
            id: Long,
            iid: Long,
            ref: String,
            sha: String,
            job: String,
            status: String,
        ) = """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": $id, "iid": $iid, "ref": "$ref", "sha": "$sha", "source": "merge_request_event",
    "status": "$status", "detailed_status": "$status", "stages": ["policy"], "duration": 300,
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/$id"
  },
  "merge_request": {
    "id": ${100 + iid}, "iid": $iid, "title": "Update deps $iid", "source_branch": "$ref",
    "target_branch": "main", "url": "https://gitlab.com/android-team/customer-app/-/merge_requests/$iid"
  },
  "user": { "id": 1, "name": "RENOVATE2", "username": "project_599_bot" },
  "project": { "id": $projectId, "name": "customer-app", "web_url": "https://gitlab.com/android-team/customer-app" },
  "builds": [ { "id": 1104300, "stage": "policy", "name": "$job", "status": "$status" } ]
}
        """.trimIndent()
    }
}
