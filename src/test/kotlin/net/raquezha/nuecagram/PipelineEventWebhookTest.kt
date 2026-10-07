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
import org.gitlab4j.api.utils.JacksonJson
import org.gitlab4j.api.webhook.PipelineEvent
import org.gitlab4j.api.webhook.PushEvent

@Suppress("TooManyFunctions")
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
            assertThat(completionReply?.disableWebPagePreview).isTrue()
            assertThat(completionReply?.text).contains(
                "<a href=\"https://gitlab.com/android-team/customer-app/-/merge_requests/2923\">!2923</a>"
            )
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
            assertThat(completionReply?.disableWebPagePreview).isTrue()
            assertThat(completionReply?.text).contains(
                "<a href=\"https://gitlab.com/android-team/customer-app/-/merge_requests/2923\">!2923</a>",
            )
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
    fun testBotTriggeredPipelineDoesNotMentionCommitAuthor() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val payload = SAMPLE_PAYLOAD_SUCCESS
                .replace("\"username\": \"raquezha\"", "\"username\": \"project_599_bot_token\"")
                .replace("\"name\": \"raquezha\"", "\"name\": \"RENOVATE2\"")
            postWebhook(EVENT_PIPELINE, payload)

            waitForMessages(mockTelegramService, 1)
            kotlinx.coroutines.delay(150)
            assertThat(mockTelegramService.sentMessages().filter { it.replyToMessageId != null }).isEmpty()
        }

    @Test
    fun testBotTriggeredMrPipelineDoesNotMentionCachedHumanParticipants() =
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
                    reviewerUsernames = listOf("bob"),
                )
            }
            val payload = SAMPLE_PAYLOAD_MR_SUCCESS
                .replace("\"username\": \"alice\"", "\"username\": \"project_599_bot_token\"")
            assertThat(payload).contains("\"username\": \"project_599_bot_token\"")
            postWebhook(EVENT_PIPELINE, payload)

            waitForMessages(mockTelegramService, 1)
            kotlinx.coroutines.delay(150)
            assertThat(mockTelegramService.sentMessages().filter { it.replyToMessageId != null }).isEmpty()
        }

    @Test
    fun testScheduledRenovateMaintenanceDoesNotMentionScheduleOwner() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val payload = SAMPLE_PAYLOAD_SUCCESS
                .replace("\"source\": \"push\"", "\"source\": \"schedule\"")
                .replace("\"name\": \"prepare\"", "\"name\": \"maintain:renovate\"")
            postWebhook(EVENT_PIPELINE, payload)

            waitForMessages(mockTelegramService, 1)
            kotlinx.coroutines.delay(150)
            assertThat(mockTelegramService.sentMessages().filter { it.replyToMessageId != null }).isEmpty()
        }

    @Test
    fun testOtherScheduledPipelineDoesNotMentionHumanOnSuccess() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val payload = SAMPLE_PAYLOAD_SUCCESS.replace("\"source\": \"push\"", "\"source\": \"schedule\"")
            postWebhook(EVENT_PIPELINE, payload)

            waitForMessages(mockTelegramService, 1)
            kotlinx.coroutines.delay(150)
            assertThat(mockTelegramService.sentMessages().filter { it.replyToMessageId != null }).isEmpty()
        }

    @Test
    fun testOtherScheduledPipelineMentionsHumanOnFailure() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val payload = SAMPLE_PAYLOAD_FAILED.replace("\"source\": \"push\"", "\"source\": \"schedule\"")
            postWebhook(EVENT_PIPELINE, payload)

            val messages = waitForMessages(mockTelegramService, 2)
            assertThat(messages.any { it.text.contains("@raquezha") }).isTrue()
        }

    @Test
    fun testHumanNamedR3novateReceivesFailureReply() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val payload = SAMPLE_PAYLOAD_FAILED.replace("\"username\": \"raquezha\"", "\"username\": \"r3novate\"")
            postWebhook(EVENT_PIPELINE, payload)

            val messages = waitForMessages(mockTelegramService, 2)
            assertThat(messages.any { it.text.contains("@r3novate") }).isTrue()
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
            assertThat(afterManual.last().text).contains(
                "<a href=\"https://gitlab.com/android-team/customer-app/-/merge_requests/2925\">!2925</a>",
            )
            assertThat(afterManual.last().text).contains("Please review")

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

            kotlinx.coroutines.runBlocking {
                installationRepository.upsertMrParticipants(
                    installationId = installation.id,
                    projectId = 105L,
                    mrIid = 2923L,
                    authorUsername = "alice",
                    reviewerUsernames = listOf("bob"),
                )
            }

            // First run: pipeline passes
            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_SUCCESS)
            val initialMessages = waitForMessages(mockTelegramService, 2)
            assertThat(initialMessages.size).isEqualTo(2)
            val initialCard = initialMessages[0]
            val initialReply = initialMessages[1]
            assertThat(initialReply.replyToMessageId).isEqualTo(1L)
            assertThat(initialReply.text).contains("@bob")

            // Second run: retried job finishes and pipeline succeeds again (same pipelineId 8888)
            val retriedPayload = SAMPLE_PAYLOAD_MR_SUCCESS.replace("\"duration\": 300", "\"duration\": 350")
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

            kotlinx.coroutines.runBlocking {
                installationRepository.upsertMrParticipants(
                    installationId = installation.id,
                    projectId = 105L,
                    mrIid = 2923L,
                    authorUsername = "alice",
                    reviewerUsernames = listOf("bob"),
                )
            }

            // Pipeline 1 passes
            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_SUCCESS)
            val messages1 = waitForMessages(mockTelegramService, 2)
            assertThat(messages1.size).isEqualTo(2)

            // Sibling/distinct Pipeline 2 on same commit also passes and is NOT silenced
            val siblingPayload = SAMPLE_PAYLOAD_MR_SUCCESS
                .replace("\"id\": 8888", "\"id\": 8889")
            postWebhook(EVENT_PIPELINE, siblingPayload)
            val messages2 = waitForMessages(mockTelegramService, 4)
            assertThat(messages2.size).isEqualTo(4)

            // Retrying pipeline 1 edits pipeline 1 in-place and does NOT send another reply
            val retriedPayload = SAMPLE_PAYLOAD_MR_SUCCESS.replace("\"duration\": 300", "\"duration\": 350")
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
            val messages = waitForMessages(mockTelegramService, 1)
            val card = messages[0]
            val expectedMrLink =
                "<b>main</b> (<a href=\"https://gitlab.com/android-team/customer-app/-/merge_requests/42\">!42</a>)"
            assertThat(card.text).contains(expectedMrLink)
        }

    @Test
    fun testSoloPassingPipelineEmitsZeroReplyBubbles() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_SUCCESS)
            waitForMessages(mockTelegramService, 1)
            kotlinx.coroutines.delay(150)
            assertThat(mockTelegramService.sentMessages().filter { it.replyToMessageId != null }).isEmpty()
        }

    @Test
    fun testDraftMrPipelineSuccessSuppressesReviewerPings() =
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

            val draftPayload = SAMPLE_PAYLOAD_MR_SUCCESS
                .replace("\"title\": \"Add feature\"", "\"title\": \"Draft: Add feature\"")
            postWebhook(EVENT_PIPELINE, draftPayload)

            waitForMessages(mockTelegramService, 1)
            kotlinx.coroutines.delay(150)
            assertThat(mockTelegramService.sentMessages().filter { it.replyToMessageId != null }).isEmpty()
        }

    @Test
    fun testDraftMrPipelineFailureStillPingsAuthor() =
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

            val draftFailedPayload = SAMPLE_PAYLOAD_MR_FAILED
                .replace("\"title\": \"Add feature\"", "\"title\": \"Draft: Add feature\"")
            postWebhook(EVENT_PIPELINE, draftFailedPayload)

            val messages = waitForMessages(mockTelegramService, 2)
            assertThat(messages.any { it.text.contains("@alice") }).isTrue()
        }

    @Test
    fun testPipelineAdoptsExistingPushMessageByCommitSha() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val successPipeline = JacksonJson().unmarshal(PipelineEvent::class.java, SAMPLE_PAYLOAD_SUCCESS)
            val pipelineSha = requireNotNull(successPipeline.objectAttributes?.sha)
            val pushEvent = JacksonJson().unmarshal(PushEvent::class.java, PushEventWebhookTest.SAMPLE_PAYLOAD)
            val pushSha = requireNotNull(pushEvent.after)

            val pushPayload = PushEventWebhookTest.SAMPLE_PAYLOAD
                .replace(pushSha, pipelineSha)
                .replace("282", "105")
                .replace("refs/heads/nuecalytics", "refs/heads/main")

            postWebhook(EVENT_PUSH, pushPayload)
            val pushMessages = waitForMessages(mockTelegramService, 1)
            assertThat(pushMessages).hasSize(1)

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_SUCCESS)
            val allMessages = waitForMessages(mockTelegramService, 2)
            assertThat(allMessages).hasSize(2)
            assertThat(allMessages.last().messageId).isEqualTo("1")
            assertThat(allMessages.last().text).contains("📤 Push to")
            assertThat(allMessages.last().text).contains("Pipeline")
            assertThat(allMessages.last().text).contains("prepare")
            assertThat(allMessages.last().text).contains("Enable crashlytics collection")
        }

    @Test
    fun testRapidDoublePushEditsSameCardInPlace() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val pushEvent = JacksonJson().unmarshal(PushEvent::class.java, PushEventWebhookTest.SAMPLE_PAYLOAD)
            val firstSha = requireNotNull(pushEvent.after)
            val secondSha = firstSha.dropLast(4) + "abcd"

            val firstPush = PushEventWebhookTest.SAMPLE_PAYLOAD
                .replace("282", "105")
                .replace("refs/heads/nuecalytics", "refs/heads/feature/slice4")
            postWebhook(EVENT_PUSH, firstPush)
            waitForMessages(mockTelegramService, 1)

            val secondPush = firstPush
                .replace(firstSha, secondSha)
            postWebhook(EVENT_PUSH, secondPush)
            val messages = waitForMessages(mockTelegramService, 2)
            assertThat(messages).hasSize(2)
            assertThat(messages[0].messageId).isNull()
            assertThat(messages[1].messageId).isEqualTo("1")
            assertThat(messages[1].text).contains("📤 Push to")
        }

    @Test
    fun testSupersededCanceledPipelineIsIgnoredAfterTipAdvances() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val successPipeline = JacksonJson().unmarshal(PipelineEvent::class.java, SAMPLE_PAYLOAD_SUCCESS)
            val tipSha = requireNotNull(successPipeline.objectAttributes?.sha)
            val pushEvent = JacksonJson().unmarshal(PushEvent::class.java, PushEventWebhookTest.SAMPLE_PAYLOAD)
            val firstSha = requireNotNull(pushEvent.after)

            val firstPush = PushEventWebhookTest.SAMPLE_PAYLOAD
                .replace(firstSha, tipSha)
                .replace("282", "105")
                .replace("refs/heads/nuecalytics", "refs/heads/feature/slice4")
            postWebhook(EVENT_PUSH, firstPush)
            waitForMessages(mockTelegramService, 1)

            val nextSha = tipSha.dropLast(4) + "abcd"
            val secondPush = firstPush.replace(tipSha, nextSha)
            postWebhook(EVENT_PUSH, secondPush)
            waitForMessages(mockTelegramService, 2)

            val canceledOldTip = SAMPLE_PAYLOAD_SUCCESS
                .replace("\"status\": \"success\"", "\"status\": \"canceled\"")
                .replace("\"detailed_status\": \"passed\"", "\"detailed_status\": \"canceled\"")
                .replace("\"ref\": \"main\"", "\"ref\": \"feature/slice4\"")
            postWebhook(EVENT_PIPELINE, canceledOldTip)

            kotlinx.coroutines.delay(150)
            val messages = mockTelegramService.sentMessages()
            assertThat(messages).hasSize(2)
            assertThat(messages.none { it.text.contains("canceled") }).isTrue()
        }

    @Test
    fun testOlderPipelineCannotRevertNewerBranchCard() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val newerSuccess = SAMPLE_PAYLOAD_SUCCESS
                .replace("\"ref\": \"main\"", "\"ref\": \"feature/mono\"")
            postWebhook(EVENT_PIPELINE, newerSuccess)
            val afterNewer = waitForMessages(mockTelegramService, 1)
            assertThat(afterNewer).hasSize(1)
            assertThat(afterNewer[0].text).contains("passed")

            val olderSuccess = SAMPLE_PAYLOAD_SUCCESS
                .replace("\"id\": 53481", "\"id\": 53480")
                .replace("\"iid\": 2925", "\"iid\": 2924")
                .replace("\"ref\": \"main\"", "\"ref\": \"feature/mono\"")
                .replace("\"status\": \"success\"", "\"status\": \"failed\"")
                .replace("\"detailed_status\": \"passed\"", "\"detailed_status\": \"failed\"")
            postWebhook(EVENT_PIPELINE, olderSuccess)

            kotlinx.coroutines.delay(150)
            val messages = mockTelegramService.sentMessages()
            assertThat(messages).hasSize(1)
            assertThat(messages[0].text).contains("passed")
            assertThat(messages[0].text).doesNotContain("failed")
        }

    @Test
    fun testRunningBuildShowsRunnerNameAndStage() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val runningWithRunner = SAMPLE_PAYLOAD_RUNNING.replaceFirst(
                Regex("(\"name\": \"ktlint\",[\\s\\S]*?)\"runner\": null,"),
                "$1\"runner\": {" +
                    "\"id\": 202," +
                    "\"description\": \"Android Team Runner - Mac Shell 6\"," +
                    "\"runner_type\": \"group_type\"," +
                    "\"active\": true," +
                    "\"is_shared\": false," +
                    "\"tags\": [\"android\"]" +
                    "},",
            )

            postWebhook(EVENT_PIPELINE, runningWithRunner)
            val messages = waitForMessages(mockTelegramService, 1)
            assertThat(messages[0].text).contains("running on Android Team Runner - Mac Shell 6")
            assertThat(messages[0].text).contains("(test)")
        }

    @Test
    fun testMainPushCreatesDedicatedCardNotEditingFeatureCard() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val featurePush = PushEventWebhookTest.SAMPLE_PAYLOAD
                .replace("282", "105")
                .replace("refs/heads/nuecalytics", "refs/heads/feature/ship")
            postWebhook(EVENT_PUSH, featurePush)
            waitForMessages(mockTelegramService, 1)

            val mainPush = PushEventWebhookTest.SAMPLE_PAYLOAD
                .replace("282", "105")
                .replace("refs/heads/nuecalytics", "refs/heads/main")
            postWebhook(EVENT_PUSH, mainPush)
            val messages = waitForMessages(mockTelegramService, 2)
            assertThat(messages).hasSize(2)
            assertThat(messages[0].messageId).isNull()
            assertThat(messages[1].messageId).isNull()
            assertThat(messages[1].text).contains("main")
        }

    @Test
    fun testManualDeployOnFrozenCardPingsOnlyTriggerHuman() =
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
                    reviewerUsernames = listOf("bob"),
                )
            }

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_SUCCESS)
            val initialMessages = waitForMessages(mockTelegramService, 2)
            assertThat(initialMessages).hasSize(2)
            assertThat(initialMessages[1].text).contains("@bob")

            val manualDeployPayload = SAMPLE_PAYLOAD_MR_SUCCESS
                .replace("\"id\": 8888", "\"id\": 8889")
                .replace("\"username\": \"alice\"", "\"username\": \"ralpheufracio\"")
                .replace(
                    "\"stages\": [\"test\"],",
                    "\"stages\": [\"test\", \"deploy\"],",
                )
                .replace(
                    "\"object_attributes\": {",
                    "\"builds\": [" +
                        "{" +
                        "\"id\": 99991," +
                        "\"stage\": \"deploy\"," +
                        "\"name\": \"deploy:firebase:review\"," +
                        "\"status\": \"success\"," +
                        "\"manual\": true" +
                        "}]," +
                        "\"object_attributes\": {",
                )
            postWebhook(EVENT_PIPELINE, manualDeployPayload)
            val allMessages = waitForMessages(mockTelegramService, 4)
            assertThat(allMessages).hasSize(4)
            assertThat(allMessages[3].text).contains("↳ 🚀 deploy:firebase:review passed! Ready for you @ralpheufracio")
            assertThat(allMessages[3].text).doesNotContain("@bob")
        }

    @Test
    fun testManualDeployOnFrozenCardByBotEmitsZeroPings() =
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
                    reviewerUsernames = listOf("bob"),
                )
            }

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_SUCCESS)
            waitForMessages(mockTelegramService, 2)

            val botDeployPayload = SAMPLE_PAYLOAD_MR_SUCCESS
                .replace("\"id\": 8888", "\"id\": 8889")
                .replace("\"username\": \"alice\"", "\"username\": \"project_105_bot\"")
                .replace(
                    "\"stages\": [\"test\"],",
                    "\"stages\": [\"test\", \"deploy\"],",
                )
                .replace(
                    "\"object_attributes\": {",
                    "\"builds\": [" +
                        "{" +
                        "\"id\": 99991," +
                        "\"stage\": \"deploy\"," +
                        "\"name\": \"deploy:firebase:review\"," +
                        "\"status\": \"success\"," +
                        "\"manual\": true" +
                        "}]," +
                        "\"object_attributes\": {",
                )
            postWebhook(EVENT_PIPELINE, botDeployPayload)
            kotlinx.coroutines.delay(150)
            val replies = mockTelegramService.sentMessages().filter { it.replyToMessageId != null }
            assertThat(replies).hasSize(1)
        }

    @Test
    fun testMatrixJobsCollapsedWhenOverLimit() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val buildsJson = (1..15).joinToString(",") { i ->
                """{"id": $i, "stage": "test", "name": "matrix-test-$i", "status": "success"}"""
            }
            val matrixPayload = SAMPLE_PAYLOAD_SUCCESS.replace(
                "\"builds\": [",
                "\"builds\": [$buildsJson,",
            )
            postWebhook(EVENT_PIPELINE, matrixPayload)
            val messages = waitForMessages(mockTelegramService, 1)
            assertThat(messages[0].text).contains("passed jobs hidden...")
        }

    @Test
    fun testUnmappedUserFormattedAsPlainTextWithoutAtPrefix() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            val failedPayload = SAMPLE_PAYLOAD_FAILED
                .replace("\"id\": 53480", "\"id\": 53499")
                .replace("\"username\": \"raquezha\"", "\"username\": \"dev.alex\"")

            postWebhook(EVENT_PIPELINE, failedPayload)
            val messages = waitForMessages(mockTelegramService, 2)
            assertThat(messages).hasSize(2)
            assertThat(messages[1].text).contains("dev.alex")
            assertThat(messages[1].text).doesNotContain("@dev.alex")
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
