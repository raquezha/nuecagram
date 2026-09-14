package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.coEvery
import io.mockk.mockk
import java.util.UUID
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.models.ActiveMergeRequest
import net.raquezha.nuecagram.db.models.MrParticipants
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.webhook.*
import org.gitlab4j.api.utils.JacksonJson
import org.gitlab4j.api.webhook.PipelineEvent
import org.junit.Test

class PipelineRetryRegressionTest {
    private val installationId = UUID.randomUUID()
    private val repository = mockk<InstallationRepository>(relaxed = true)
    private val service = WebHookService(KotlinLogging.logger {}, repository)
    private val telegram = mockk<TelegramService>()
    private val delivered = mutableListOf<Message>()
    private var nextId = 0
    private var rejectReply = false

    private fun runEvents(vararg payloads: String) = kotlinx.coroutines.runBlocking {
        coEvery { telegram.sendMessage(any()) } coAnswers {
            val message = firstArg<Message>()
            when {
                message.replyToMessageId != null && rejectReply -> {
                    rejectReply = false
                    error("Simulated reply rejection")
                }
                else -> {
                    delivered += message
                    message.messageId ?: (++nextId).toString()
                }
            }
        }
        val handler = WebhookRequestHandler(
            randomMessageProvider = RandomMessageProvider(),
            webhookService = service,
            installationRepository = repository,
            telegramService = telegram,
            formatter = WebhookMessageFormatter(),
            logger = KotlinLogging.logger {},
        )
        payloads.forEach { payload ->
            handler.enqueue(
                EventData(
                    installationId = installationId,
                    event = JacksonJson().unmarshal(PipelineEvent::class.java, payload),
                    headerEvent = "Pipeline Hook",
                    chatDetails = ChatDetails("123", "456"),
                ),
            )
        }
        handler.close()
        // Drain synchronously: assertions cannot race a queued completion reply.
        handler.processQueue()
    }

    @Test
    fun failedReplyDoesNotLoseCardOrSuppressLaterEvent() {
        rejectReply = true
        runEvents(success, success, success)
        assertThat(delivered.map { it.messageId }).containsExactly(null, "1", null, "1").inOrder()
        assertThat(delivered.count { it.replyToMessageId != null }).isEqualTo(1)
        assertThat(service.getPipelineLastTerminalStatus(installationId, 53481)).isEqualTo("success")
    }

    @Test
    fun repeatedStatusIsSilentButFailureAndRecoveryCyclesNotify() {
        val failed = success.replace("\"status\": \"success\"", "\"status\": \"failed\"")
        runEvents(success, success, failed, failed, success, failed, success)
        val replies = delivered.filter { it.replyToMessageId != null }
        assertThat(replies).hasSize(5)
        assertThat(replies.count { it.text.contains("Pipeline fixed!") }).isEqualTo(2)
        assertThat(delivered.count { it.messageId == null && it.replyToMessageId == null }).isEqualTo(1)
        assertThat(replies.map { it.replyToMessageId }.distinct()).containsExactly(1L)
    }

    @Test
    fun differentPipelinesOnSameCommitBothNotify() {
        runEvents(success, success.replace("\"id\": 53481", "\"id\": 53482"), success)
        assertThat(delivered.count { it.replyToMessageId != null }).isEqualTo(2)
        assertThat(delivered.count { it.messageId == null && it.replyToMessageId == null }).isEqualTo(2)
    }

    @Test
    fun failedManualReplyCanBeDeliveredAgain() {
        rejectReply = true
        val manual = PipelineEventWebhookTest.SAMPLE_PAYLOAD_MR_MANUAL_WAITING
        runEvents(manual, manual, manual)
        assertThat(delivered.count { it.replyToMessageId != null }).isEqualTo(1)
        assertThat(delivered.count { it.messageId == null && it.replyToMessageId == null }).isEqualTo(1)
    }

    @Test
    fun activeMrBadgeIsLinkedWithoutAnEmbeddedMrPayload() {
        coEvery { repository.getActiveMrForBranch(installationId, 105L, "main") } returns
            ActiveMergeRequest(42L, "main", 105L, null)
        runEvents(success)
        assertThat(delivered.first().text).contains(
            "(<a href=\"https://gitlab.com/android-team/customer-app/-/merge_requests/42\">!42</a>)",
        )
    }

    @Test
    fun recoveryRetainsReviewerCallToAction() {
        coEvery { repository.getActiveMrForBranch(installationId, 105L, "feature-branch") } returns
            ActiveMergeRequest(2923L, "feature-branch", targetProjectId = 105L, lastCommitSha = null)
        coEvery { repository.getMrParticipants(installationId, 105L, 2923L) } returns
            MrParticipants("alice", listOf("bob"))
        val mrSuccess = PipelineEventWebhookTest.SAMPLE_PAYLOAD_MR_SUCCESS
        runEvents(mrSuccess.replace("\"status\": \"success\"", "\"status\": \"failed\""), mrSuccess)
        val recovery = delivered.last()
        assertThat(recovery.text).contains("@bob")
        assertThat(recovery.text).contains("!2923")
        assertThat(recovery.text.lowercase()).contains("review")
        assertThat(recovery.text).contains("Pipeline fixed!")
        assertThat(recovery.disableNotification).isFalse()
    }

    @Test
    fun forkMrBadgeUsesTargetMrUrlNotSourceProjectUrl() {
        val mr = PipelineEventWebhookTest.SAMPLE_PAYLOAD_MR_SUCCESS.replace(
            "https://gitlab.com/android-team/customer-app/-/merge_requests/2923",
            "https://gitlab.com/upstream/customer-app/-/merge_requests/2923",
        )
        runEvents(mr)
        assertThat(delivered.first().text).contains(
            "(<a href=\"https://gitlab.com/upstream/customer-app/-/merge_requests/2923\">!2923</a>)",
        )
    }

    @Test
    fun missingRecipientDoesNotMarkAnUnsentReplyAsDelivered() {
        val mr = PipelineEventWebhookTest.SAMPLE_PAYLOAD_MR_SUCCESS
        runEvents(mr.replace("\"username\": \"alice\"", "\"username\": \"\""), mr, mr)
        assertThat(delivered.count { it.replyToMessageId != null }).isEqualTo(1)
        assertThat(delivered.first { it.replyToMessageId != null }.text).contains("@alice")
    }

    @Test
    fun manualWaitingAndFinalCompletionEachNotifyOnce() {
        val manual = PipelineEventWebhookTest.SAMPLE_PAYLOAD_MR_MANUAL_WAITING
        val complete = PipelineEventWebhookTest.SAMPLE_PAYLOAD_MR_MANUAL_SUCCESS
        runEvents(manual, manual, complete, complete)
        assertThat(delivered.count { it.replyToMessageId != null }).isEqualTo(2)
        assertThat(delivered.count { it.messageId == null && it.replyToMessageId == null }).isEqualTo(1)
    }

    @Test
    fun jobNamesCannotBreakTelegramHtml() {
        runEvents(success.replace("\"name\": \"prepare\"", "\"name\": \"build <android> & ios\""))
        assertThat(delivered.first().text).contains("build &lt;android&gt; &amp; ios")
        assertThat(delivered.first().text).doesNotContain("<android>")
    }

    @Test
    fun delayedOutOfOrderPipelineEventDoesNotOverwriteNewerTerminalState() {
        val completeSuccess = success.replace(
            "\"finished_at\": \"2024-06-19 03:10:00 UTC\"",
            "\"finished_at\": \"2024-06-19 03:15:00 UTC\"",
        )
        // First establish lastFinishedAt with the newer event
        runEvents(completeSuccess)
        assertThat(service.getPipelineLastTerminalStatus(installationId, 53481)).isEqualTo("success")
        val initialReplies = delivered.filter { it.replyToMessageId != null }
        assertThat(initialReplies).hasSize(1)

        val staleFailure = success.replace("\"status\": \"success\"", "\"status\": \"failed\"")
            .replace("\"finished_at\": \"2024-06-19 03:10:00 UTC\"", "\"finished_at\": \"2024-06-19 03:08:00 UTC\"")
        runEvents(staleFailure)
        assertThat(service.getPipelineLastTerminalStatus(installationId, 53481)).isEqualTo("success")
        val totalReplies = delivered.filter { it.replyToMessageId != null }
        assertThat(totalReplies).hasSize(1)
        assertThat(totalReplies.first().text).contains("✅")
    }

    @Test
    fun cachedForkMrParticipantsResolveTargetProjectId() {
        coEvery { repository.getActiveMrForBranch(installationId, 105L, "feature-branch") } returns
            ActiveMergeRequest(2923L, "feature-branch", targetProjectId = 200L, lastCommitSha = null)
        coEvery { repository.getMrParticipants(installationId, 200L, 2923L) } returns
            MrParticipants("upstream_author", listOf("upstream_reviewer"))
        val forkEvent = PipelineEventWebhookTest.SAMPLE_PAYLOAD_MR_SUCCESS
        runEvents(forkEvent)
        val reply = delivered.last()
        assertThat(reply.text).contains("@upstream_reviewer")
    }

    @Test
    fun transientTelegramFailureRetriesAndSucceeds() {
        rejectReply = true
        runEvents(success, success)
        val replies = delivered.filter { it.replyToMessageId != null }
        assertThat(replies).hasSize(1)
    }

    private companion object {
        val success = PipelineEventWebhookTest.SAMPLE_PAYLOAD_SUCCESS
    }
}
