package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.server.testing.testApplication
import net.raquezha.nuecagram.di.testAppModule
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin

class PipelineEventWebhookTest : BaseEventTestHelper() {
    @Test
    fun testWebhookPipelineEvents() =
        testApplication {
            configureTestApplication()

            // Test running pipeline
            val runningResponse = postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_RUNNING)
            assertThat(runningResponse).isEqualTo("Webhook received successfully")

            // Test failed pipeline
            val failedResponse = postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_FAILED)
            assertThat(failedResponse).isEqualTo("Webhook received successfully")

            // Test success pipeline
            val successResponse = postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_SUCCESS)
            assertThat(successResponse).isEqualTo("Webhook received successfully")
        }

    @Test
    fun testMrPipelineSuccessPingsReviewers() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            kotlinx.coroutines.runBlocking {
                installationRepository.upsertMrParticipants(
                    installationId = installation.id,
                    projectId = 105L,
                    mrIid = 2923L,
                    authorUsername = "alice",
                    reviewerUsernames = listOf("bob", "charlie"),
                )
            }

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_SUCCESS)

            val completionReply = kotlinx.coroutines.runBlocking {
                var found: net.raquezha.nuecagram.telegram.Message? = null
                for (i in 1..100) {
                    found = mockTelegramService.sentMessages().find { it.text.contains("@bob") }
                    if (found != null) break
                    kotlinx.coroutines.delay(50)
                }
                found
            }

            assertThat(completionReply).isNotNull()
            assertThat(completionReply?.text).contains("@bob @charlie")
            assertThat(completionReply?.text).contains("!2923")
            assertThat(completionReply?.text?.lowercase()).contains("review")
            assertThat(completionReply?.disableNotification).isFalse()

            // Repeated webhook payload for same terminal pipeline does not duplicate ping
            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_SUCCESS)
            val allBobPings = mockTelegramService.sentMessages().count { it.text.contains("@bob") }
            assertThat(allBobPings).isEqualTo(1)
        }

    @Test
    fun testDetachedMrPipelineRefPingsReviewers() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            kotlinx.coroutines.runBlocking {
                installationRepository.upsertMrParticipants(
                    installationId = installation.id,
                    projectId = 105L,
                    mrIid = 2923L,
                    authorUsername = "alice",
                    reviewerUsernames = listOf("bob", "charlie"),
                )
            }

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_DETACHED_MR_SUCCESS)

            val completionReply = kotlinx.coroutines.runBlocking {
                var found: net.raquezha.nuecagram.telegram.Message? = null
                for (i in 1..100) {
                    found = mockTelegramService.sentMessages().find { it.text.contains("@bob") }
                    if (found != null) break
                    kotlinx.coroutines.delay(50)
                }
                found
            }

            assertThat(completionReply).isNotNull()
            assertThat(completionReply?.text).contains("@bob @charlie")
            assertThat(completionReply?.text).contains("!2923")
            assertThat(completionReply?.text?.lowercase()).contains("review")
        }

    @Test
    fun testBotTokenUserIsNotTaggedInPipelineReplies() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val botPayload = SAMPLE_PAYLOAD_SUCCESS
                .replace("\"username\": \"admin\"", "\"username\": \"group_44_bot_token\"")
                .replace("\"name\": \"Administrator\"", "\"name\": \"CI_VERSION_WRITEBACK2\"")

            postWebhook(EVENT_PIPELINE, botPayload)

            // Allow async processing
            kotlinx.coroutines.delay(150)

            // The main pipeline card is sent, but no reply tagging the bot is sent
            val sent = mockTelegramService.sentMessages()
            assertThat(sent.none { it.text.contains("group_44_bot") }).isTrue()
        }

    @Test
    fun testBotTriggeredPipelineFallsBackToCommitAuthorEmailHandle() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val authorJson = "{\"name\": \"Razyl Vidal\", \"email\": \"raquezha@example.com\"}"
            val botPayloadWithCommitAuthor = SAMPLE_PAYLOAD_SUCCESS
                .replace("\"username\": \"admin\"", "\"username\": \"group_44_bot_token\"")
                .replace("\"name\": \"Administrator\"", "\"name\": \"CI_VERSION_WRITEBACK2\"")
                .replace("\"author\": null", "\"author\": $authorJson")

            postWebhook(EVENT_PIPELINE, botPayloadWithCommitAuthor)

            val completionReply = kotlinx.coroutines.runBlocking {
                var found: net.raquezha.nuecagram.telegram.Message? = null
                for (i in 1..100) {
                    found = mockTelegramService.sentMessages().find { it.text.contains("@raquezha") }
                    if (found != null) break
                    kotlinx.coroutines.delay(50)
                }
                found
            }

            assertThat(completionReply).isNotNull()
            assertThat(completionReply?.text).contains("@raquezha")
            assertThat(completionReply?.text).doesNotContain("group_44_bot")
        }

    @Test
    fun testMrPipelineFailurePingsCreator() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            kotlinx.coroutines.runBlocking {
                installationRepository.upsertMrParticipants(
                    installationId = installation.id,
                    projectId = 105L,
                    mrIid = 2924L,
                    authorUsername = "alice",
                    reviewerUsernames = listOf("bob"),
                )
            }

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_FAILED)

            val completionReply = kotlinx.coroutines.runBlocking {
                var found: net.raquezha.nuecagram.telegram.Message? = null
                for (i in 1..100) {
                    found = mockTelegramService.sentMessages().find { it.text.contains("@alice") }
                    if (found != null) break
                    kotlinx.coroutines.delay(50)
                }
                found
            }

            assertThat(completionReply).isNotNull()
            assertThat(completionReply?.text).contains("@alice")
            assertThat(completionReply?.text).doesNotContain("@bob")
        }

    @Test
    fun testMrPipelineManualWaitingPingsReviewersOnceAndStillPingsFinalStatus() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            kotlinx.coroutines.runBlocking {
                installationRepository.upsertMrParticipants(
                    installationId = installation.id,
                    projectId = 105L,
                    mrIid = 2925L,
                    authorUsername = "alice",
                    reviewerUsernames = listOf("bob", "charlie"),
                )
            }

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_MANUAL_WAITING)
            val afterManual = waitForMessages(mockTelegramService, 2)
            assertThat(afterManual.last().text).contains("@bob @charlie pipeline passed; waiting for manual action.")

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_MANUAL_WAITING)
            val afterRepeatedManual = waitForMessages(mockTelegramService, 3)
            assertThat(
                afterRepeatedManual.count { it.text.contains("pipeline passed; waiting for manual action") },
            ).isEqualTo(1)

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_MANUAL_SUCCESS)
            val afterSuccess = waitForMessages(mockTelegramService, 5)
            assertThat(afterSuccess.count { it.text.contains("@bob @charlie") }).isEqualTo(2)
        }

    @Test
    fun testPipelineRetryEditsMessageInPlaceAndDoesNotDuplicateSuccessPing() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            // First run: pipeline passes
            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_SUCCESS)
            val initialMessages = waitForMessages(mockTelegramService, 2)
            assertThat(initialMessages.size).isEqualTo(2)
            val initialCard = initialMessages[0]
            val initialReply = initialMessages[1]
            assertThat(initialReply.replyToMessageId).isEqualTo(1L)
            assertThat(initialReply.text).contains("@raquezha")

            // Second run: retried job finishes and pipeline succeeds again (same pipelineId 53481)
            val retriedPayload = SAMPLE_PAYLOAD_SUCCESS.replace("\"duration\": 178", "\"duration\": 210")
            postWebhook(EVENT_PIPELINE, retriedPayload)
            val updatedMessages = waitForMessages(mockTelegramService, 3)

            // Edited card in-place with existing messageId 1
            assertThat(updatedMessages.size).isEqualTo(3)
            val editedCard = updatedMessages[2]
            assertThat(editedCard.messageId).isEqualTo("1")

            // No duplicate reply ping was sent
            val replies = updatedMessages.filter { it.replyToMessageId != null }
            assertThat(replies.size).isEqualTo(1)
        }

    @Test
    fun testPipelineRecoveryFromFailedToSuccessSendsFixedPing() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            // First run: pipeline fails
            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_FAILED)
            val failedMessages = waitForMessages(mockTelegramService, 2)
            assertThat(failedMessages.size).isEqualTo(2)
            assertThat(failedMessages[1].text).contains("@raquezha")

            // Second run: retrying the failed job succeeds (same pipelineId 53480)
            val recoveredPayload = SAMPLE_PAYLOAD_SUCCESS.replace("\"id\": 53481", "\"id\": 53480")
            postWebhook(EVENT_PIPELINE, recoveredPayload)
            val recoveredMessages = waitForMessages(mockTelegramService, 4)

            assertThat(recoveredMessages.size).isEqualTo(4)
            // Edited in-place
            val editedCard = recoveredMessages[2]
            assertThat(editedCard.messageId).isEqualTo("1")

            // Recovery reply sent with "Pipeline fixed!"
            val recoveryReply = recoveredMessages[3]
            assertThat(recoveryReply.replyToMessageId).isEqualTo(1L)
            assertThat(recoveryReply.text).contains("@raquezha")
            assertThat(recoveryReply.text).contains("Pipeline fixed!")
        }

    @Test
    fun testMultiplePipelinesDoNotSilenceEachOtherAndRetryDoesNotDuplicate() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            // Pipeline 1 passes
            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_SUCCESS)
            val messages1 = waitForMessages(mockTelegramService, 2)
            assertThat(messages1.size).isEqualTo(2)

            // Sibling/distinct Pipeline 2 on same commit also passes and is NOT silenced
            val siblingPayload = SAMPLE_PAYLOAD_SUCCESS
                .replace("\"id\": 53481", "\"id\": 53482")
                .replace("\"source\": \"push\"", "\"source\": \"merge_request_event\"")
            postWebhook(EVENT_PIPELINE, siblingPayload)
            val messages2 = waitForMessages(mockTelegramService, 4)
            assertThat(messages2.size).isEqualTo(4)

            // Retrying pipeline 1 edits pipeline 1 in-place and does NOT send another reply
            val retriedPayload = SAMPLE_PAYLOAD_SUCCESS.replace("\"duration\": 178", "\"duration\": 210")
            postWebhook(EVENT_PIPELINE, retriedPayload)
            val messages3 = waitForMessages(mockTelegramService, 5)
            assertThat(messages3.size).isEqualTo(5)
            val editedCard = messages3[4]
            assertThat(editedCard.messageId).isEqualTo("1")

            // Total replies remain 2 (one for pipeline 1, one for pipeline 2)
            val totalReplies = messages3.filter { it.replyToMessageId != null }
            assertThat(totalReplies.size).isEqualTo(2)
        }

    @Test
    fun testPipelineCardHeaderContainsMrLinkWhenActiveMrExists() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            kotlinx.coroutines.runBlocking {
                installationRepository.upsertActiveMr(
                    installationId = installation.id,
                    projectId = 105L,
                    sourceBranch = "main",
                    mrIid = 42L,
                )
            }

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_SUCCESS)
            val messages = waitForMessages(mockTelegramService, 2)
            val card = messages[0]
            val expectedMrLink =
                "<b>main</b> (<a href=\"https://gitlab.com/android-team/customer-app/-/merge_requests/42\">!42</a>)"
            assertThat(card.text).contains(expectedMrLink)
        }

    private fun waitForMessages(
        mockTelegramService: net.raquezha.nuecagram.telegram.MockTelegramService,
        count: Int,
    ) = kotlinx.coroutines.runBlocking {
        repeat(100) {
            val messages = mockTelegramService.sentMessages()
            if (messages.size >= count) return@runBlocking messages
            kotlinx.coroutines.delay(50)
        }
        mockTelegramService.sentMessages()
    }

    companion object {
        val SAMPLE_PAYLOAD_MR_SUCCESS =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 8888,
    "iid": 2923,
    "source": "merge_request_event",
    "status": "success",
    "ref": "feature-branch",
    "stages": ["test"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": "2024-06-19 02:25:18 UTC",
    "duration": 300,
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/8888"
  },
  "merge_request": {
    "id": 999,
    "iid": 2923,
    "title": "Add feature",
    "source_branch": "feature-branch",
    "target_branch": "main",
    "state": "opened",
    "url": "https://gitlab.com/android-team/customer-app/-/merge_requests/2923"
  },
  "user": {
    "id": 38,
    "name": "Alice Author",
    "username": "alice"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "web_url": "https://gitlab.com/android-team/customer-app"
  }
}
""".trimIndent()

        val SAMPLE_PAYLOAD_DETACHED_MR_SUCCESS =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 8887,
    "iid": 2923,
    "source": "merge_request_event",
    "status": "success",
    "ref": "refs/merge-requests/2923/head",
    "stages": ["test"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": "2024-06-19 02:25:18 UTC",
    "duration": 300,
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/8887"
  },
  "user": {
    "id": 38,
    "name": "Alice Author",
    "username": "alice"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "web_url": "https://gitlab.com/android-team/customer-app"
  }
}
""".trimIndent()

        val SAMPLE_PAYLOAD_MR_FAILED =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 8889,
    "iid": 2924,
    "source": "merge_request_event",
    "status": "failed",
    "ref": "feature-branch",
    "stages": ["test"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": "2024-06-19 02:25:18 UTC",
    "duration": 300,
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/8889"
  },
  "merge_request": {
    "id": 1000,
    "iid": 2924,
    "title": "Add feature",
    "source_branch": "feature-branch",
    "target_branch": "main",
    "state": "opened",
    "url": "https://gitlab.com/android-team/customer-app/-/merge_requests/2924"
  },
  "user": {
    "id": 38,
    "name": "Alice Author",
    "username": "alice"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "web_url": "https://gitlab.com/android-team/customer-app"
  }
}
""".trimIndent()

        val SAMPLE_PAYLOAD_MR_MANUAL_WAITING =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 8890,
    "iid": 2925,
    "source": "merge_request_event",
    "status": "manual",
    "detailed_status": "waiting for manual action",
    "ref": "feature-branch",
    "stages": ["test", "deploy"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": null,
    "duration": null,
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/8890"
  },
  "merge_request": {
    "id": 1001,
    "iid": 2925,
    "title": "Add feature",
    "source_branch": "feature-branch",
    "target_branch": "main",
    "state": "opened",
    "url": "https://gitlab.com/android-team/customer-app/-/merge_requests/2925"
  },
  "user": {
    "id": 38,
    "name": "Alice Author",
    "username": "alice"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "web_url": "https://gitlab.com/android-team/customer-app"
  },
  "builds": [
    {
      "id": 1,
      "stage": "test",
      "name": "test",
      "status": "success",
      "when": "on_success",
      "manual": false,
      "allow_failure": false
    },
    {
      "id": 2,
      "stage": "deploy",
      "name": "deploy:review",
      "status": "manual",
      "when": "manual",
      "manual": true,
      "allow_failure": false
    }
  ]
}
""".trimIndent()

        val SAMPLE_PAYLOAD_MR_MANUAL_SUCCESS = SAMPLE_PAYLOAD_MR_MANUAL_WAITING.replace(
            "\"status\": \"manual\",\n    \"detailed_status\": \"waiting for manual action\"",
            "\"status\": \"success\",\n    \"detailed_status\": \"passed\"",
        )
        @BeforeClass
        @JvmStatic
        fun setUpClass() {

            if (GlobalContext.getOrNull() == null) {
                startKoin {
                    modules(testAppModule())
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            stopKoin()
        }

        val SAMPLE_PAYLOAD_RUNNING =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 53479,
    "iid": 2923,
    "name": null,
    "ref": "main",
    "tag": false,
    "sha": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "before_sha": "0000000000000000000000000000000000000000",
    "source": "push",
    "status": "running",
    "detailed_status": "running",
    "stages": ["prepare", "test", "deploy"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": null,
    "duration": null,
    "queued_duration": 1,
    "variables": [],
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/53479"
  },
  "merge_request": null,
  "user": {
    "id": 38,
    "name": "raquezha",
    "username": "raquezha",
    "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
    "email": "[REDACTED]"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "description": "This is awesome",
    "web_url": "https://gitlab.com/android-team/customer-app",
    "avatar_url": null,
    "git_ssh_url": "git@gitlab.com:android-team/customer-app.git",
    "git_http_url": "https://gitlab.com/android-team/customer-app.git",
    "namespace": "customer-app",
    "visibility_level": 0,
    "path_with_namespace": "android-team/customer-app",
    "default_branch": "main",
    "ci_config_path": null
  },
  "commit": {
    "id": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "message": "Add new feature",
    "title": "Add new feature",
    "timestamp": "2024-05-20T04:51:32+00:00",
    "url": "https://gitlab.com/android-team/customer-app/-/commit/e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "author": {
      "name": "raquezha",
      "email": "[REDACTED]"
    }
  },
  "builds": [
    {
      "id": 481131,
      "stage": "prepare",
      "name": "prepare",
      "status": "success",
      "created_at": "2024-06-19 03:05:35 UTC",
      "started_at": "2024-06-19 03:05:36 UTC",
      "finished_at": "2024-06-19 03:05:51 UTC",
      "duration": 14.930185,
      "queued_duration": 1.460193,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480895,
      "stage": "test",
      "name": "ktlint",
      "status": "running",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": "2024-06-19 03:05:52 UTC",
      "finished_at": null,
      "duration": null,
      "queued_duration": 0.4089,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480896,
      "stage": "test",
      "name": "detekt",
      "status": "pending",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": null,
      "finished_at": null,
      "duration": null,
      "queued_duration": null,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480897,
      "stage": "deploy",
      "name": "deploy",
      "status": "pending",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": null,
      "finished_at": null,
      "duration": null,
      "queued_duration": null,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    }
  ]
}
            """.trimIndent()

        val SAMPLE_PAYLOAD_FAILED =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 53480,
    "iid": 2924,
    "name": null,
    "ref": "main",
    "tag": false,
    "sha": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "before_sha": "0000000000000000000000000000000000000000",
    "source": "push",
    "status": "failed",
    "detailed_status": "failed",
    "stages": ["prepare", "test", "deploy"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": "2024-06-19 03:06:41 UTC",
    "duration": 64,
    "queued_duration": 1,
    "variables": [],
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/53480"
  },
  "merge_request": null,
  "user": {
    "id": 38,
    "name": "raquezha",
    "username": "raquezha",
    "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
    "email": "[REDACTED]"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "description": "This is awesome",
    "web_url": "https://gitlab.com/android-team/customer-app",
    "avatar_url": null,
    "git_ssh_url": "git@gitlab.com:android-team/customer-app.git",
    "git_http_url": "https://gitlab.com/android-team/customer-app.git",
    "namespace": "customer-app",
    "visibility_level": 0,
    "path_with_namespace": "android-team/customer-app",
    "default_branch": "main",
    "ci_config_path": null
  },
  "commit": {
    "id": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "message": "Add new feature",
    "title": "Add new feature",
    "timestamp": "2024-05-20T04:51:32+00:00",
    "url": "https://gitlab.com/android-team/customer-app/-/commit/e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "author": {
      "name": "raquezha",
      "email": "[REDACTED]"
    }
  },
  "builds": [
    {
      "id": 481131,
      "stage": "prepare",
      "name": "prepare",
      "status": "success",
      "created_at": "2024-06-19 03:05:35 UTC",
      "started_at": "2024-06-19 03:05:36 UTC",
      "finished_at": "2024-06-19 03:05:51 UTC",
      "duration": 14.930185,
      "queued_duration": 1.460193,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480895,
      "stage": "test",
      "name": "ktlint",
      "status": "failed",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": "2024-06-19 03:05:52 UTC",
      "finished_at": "2024-06-19 03:06:41 UTC",
      "duration": 49.304208,
      "queued_duration": 0.4089,
      "failure_reason": "script_failure",
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480896,
      "stage": "test",
      "name": "detekt",
      "status": "skipped",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": null,
      "finished_at": null,
      "duration": null,
      "queued_duration": null,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480897,
      "stage": "deploy",
      "name": "deploy",
      "status": "skipped",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": null,
      "finished_at": null,
      "duration": null,
      "queued_duration": null,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    }
  ]
}
            """.trimIndent()

        val SAMPLE_PAYLOAD_SUCCESS =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 53481,
    "iid": 2925,
    "name": null,
    "ref": "main",
    "tag": false,
    "sha": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "before_sha": "0000000000000000000000000000000000000000",
    "source": "push",
    "status": "success",
    "detailed_status": "passed",
    "stages": ["prepare", "test", "deploy"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": "2024-06-19 03:10:00 UTC",
    "duration": 178,
    "queued_duration": 1,
    "variables": [],
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/53481"
  },
  "merge_request": null,
  "user": {
    "id": 38,
    "name": "raquezha",
    "username": "raquezha",
    "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
    "email": "[REDACTED]"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "description": "This is awesome",
    "web_url": "https://gitlab.com/android-team/customer-app",
    "avatar_url": null,
    "git_ssh_url": "git@gitlab.com:android-team/customer-app.git",
    "git_http_url": "https://gitlab.com/android-team/customer-app.git",
    "namespace": "customer-app",
    "visibility_level": 0,
    "path_with_namespace": "android-team/customer-app",
    "default_branch": "main",
    "ci_config_path": null
  },
  "commit": {
    "id": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "message": "Add new feature",
    "title": "Add new feature",
    "timestamp": "2024-05-20T04:51:32+00:00",
    "url": "https://gitlab.com/android-team/customer-app/-/commit/e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "author": {
      "name": "raquezha",
      "email": "[REDACTED]"
    }
  },
  "builds": [
    {
      "id": 481131,
      "stage": "prepare",
      "name": "prepare",
      "status": "success",
      "created_at": "2024-06-19 03:05:35 UTC",
      "started_at": "2024-06-19 03:05:36 UTC",
      "finished_at": "2024-06-19 03:05:51 UTC",
      "duration": 14.930185,
      "queued_duration": 1.460193,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480895,
      "stage": "test",
      "name": "ktlint",
      "status": "success",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": "2024-06-19 03:05:52 UTC",
      "finished_at": "2024-06-19 03:06:41 UTC",
      "duration": 49.304208,
      "queued_duration": 0.4089,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480896,
      "stage": "test",
      "name": "detekt",
      "status": "success",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": "2024-06-19 03:06:42 UTC",
      "finished_at": "2024-06-19 03:07:30 UTC",
      "duration": 48.123456,
      "queued_duration": 0.5,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480897,
      "stage": "deploy",
      "name": "deploy",
      "status": "success",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": "2024-06-19 03:07:31 UTC",
      "finished_at": "2024-06-19 03:10:00 UTC",
      "duration": 149.654321,
      "queued_duration": 0.3,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    }
  ]
}
            """.trimIndent()
    }
}
