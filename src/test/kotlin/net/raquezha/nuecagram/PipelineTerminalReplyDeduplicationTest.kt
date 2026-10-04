package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.coEvery
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.webhook.*
import org.gitlab4j.api.utils.JacksonJson
import org.gitlab4j.api.webhook.PipelineEvent
import org.junit.Test

class PipelineTerminalReplyDeduplicationTest {
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
        handler.processQueue()
    }

    @Test
    fun pipelineRetryRunningFollowedBySameFinishedAtDoesNotSendDuplicateReply() {
        val failed = success.replace("\"status\": \"success\"", "\"status\": \"failed\"")
        val running = success.replace("\"status\": \"success\"", "\"status\": \"running\"")
        runEvents(failed, running, failed)
        val replies = delivered.filter { it.replyToMessageId != null }
        assertThat(replies).hasSize(1)
        assertThat(replies.first().text).contains("❌")
    }

    @Test
    fun pipelineRetryWithNewerFinishedAtNotifiesFailureAgain() {
        val failed1 = success.replace("\"status\": \"success\"", "\"status\": \"failed\"")
            .replace("\"finished_at\": \"2024-06-19 03:10:00 UTC\"", "\"finished_at\": \"2024-06-19 03:10:00 UTC\"")
        val running = success.replace("\"status\": \"success\"", "\"status\": \"running\"")
        val failed2 = success.replace("\"status\": \"success\"", "\"status\": \"failed\"")
            .replace("\"finished_at\": \"2024-06-19 03:10:00 UTC\"", "\"finished_at\": \"2024-06-19 03:15:00 UTC\"")
        runEvents(failed1, running, failed2)
        val replies = delivered.filter { it.replyToMessageId != null }
        assertThat(replies).hasSize(2)
        assertThat(replies[0].text).contains("❌")
        assertThat(replies[1].text).contains("❌")
    }

    @Test
    fun failedTelegramSendRollsBackClaimAllowingSubsequentSuccess() {
        rejectReply = true
        val failed = success.replace("\"status\": \"success\"", "\"status\": \"failed\"")
        runEvents(failed, failed)
        val replies = delivered.filter { it.replyToMessageId != null }
        assertThat(replies).hasSize(1)
        assertThat(service.getPipelineLastTerminalStatus(installationId, 53481)).isEqualTo("failed")
    }

    @Test
    fun concurrentTerminalStatusClaimsOnlyAllowSingleWinner() {
        val pipelineId = 99999L
        service.setPipelineMessageId(installationId, pipelineId, "100")
        val results = ConcurrentLinkedQueue<WebHookService.TerminalStatusClaim>()
        val threads = (1..10).map {
            Thread {
                results.add(
                    service.tryClaimPipelineTerminalStatus(
                        installationId = installationId,
                        pipelineId = pipelineId,
                        status = "failed",
                        finishedAtEpochSeconds = 1000L,
                    ),
                )
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val claimed = results.filterIsInstance<WebHookService.TerminalStatusClaim.Claimed>()
        val alreadyNotified = results.filterIsInstance<WebHookService.TerminalStatusClaim.AlreadyNotified>()
        assertThat(claimed).hasSize(1)
        assertThat(alreadyNotified).hasSize(9)
        assertThat(service.getPipelineLastTerminalStatus(installationId, pipelineId)).isEqualTo("failed")
    }

    private companion object {
        val success = PipelineEventWebhookTest.SAMPLE_PAYLOAD_SUCCESS
    }
}
