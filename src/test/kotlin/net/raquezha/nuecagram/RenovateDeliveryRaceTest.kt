package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.webhook.*
import org.gitlab4j.api.utils.JacksonJson
import org.gitlab4j.api.webhook.MergeRequestEvent
import org.junit.Test

class RenovateDeliveryRaceTest : BaseEventTestHelper() {
    private fun handler(telegram: TelegramService, now: Instant) = WebhookRequestHandler(
        randomMessageProvider = RandomMessageProvider(),
        webhookService = WebHookService(KotlinLogging.logger {}, installationRepository),
        installationRepository = installationRepository,
        telegramService = telegram,
        formatter = WebhookMessageFormatter(),
        logger = KotlinLogging.logger {},
        renovateClock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun mrArrivingDuringFallbackSendAdoptsThatMessage() = runBlocking {
        val now = Instant.now()
        val project = installation.gitlabProjectId!!
        val key = RenovatePipelineCards.Key(installation.id, project, "renovate/race", "race-sha")
        val destination = ChatDetails(installation.telegramChatId.toString())
        RenovatePipelineCards(Clock.fixed(now, ZoneOffset.UTC)).record(key, 101172, destination, "Failed validate")
        val sending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val edited = CompletableDeferred<Message>()
        val messages = mutableListOf<Message>()
        val telegram = io.mockk.mockk<TelegramService>()
        io.mockk.coEvery { telegram.sendMessage(any()) } coAnswers {
            val message = firstArg<Message>()
            messages.add(message)
            if (message.messageId == null) {
                sending.complete(Unit)
                release.await()
            } else {
                edited.complete(message)
            }
            message.messageId ?: "999"
        }
        val handler = handler(telegram, now.plusSeconds(121))
        val otherHandler = handler(telegram, now)
        val worker = launch { handler.processQueue() }
        val otherWorker = launch { otherHandler.processQueue() }
        try {
            withTimeout(5000) { sending.await() }
            val payload = """
                {"object_kind":"merge_request","user":{"name":"RENOVATE","username":"project_599_bot"},
                 "project":{"id":$project,"name":"test","web_url":"https://gitlab.com/team/test"},
                 "object_attributes":{"id":112,"iid":12,"title":"Update","source_branch":"${key.branch}",
                 "target_branch":"main","action":"open","last_commit":{"id":"${key.sha}"}}}
            """.trimIndent()
            otherHandler.enqueue(
                EventData(
                    installation.id,
                    JacksonJson().unmarshal(MergeRequestEvent::class.java, payload),
                    EVENT_MERGE,
                    destination,
                ),
            )
            release.complete(Unit)
            val adoption = withTimeout(5000) { edited.await() }
            assertThat(adoption.messageId).isEqualTo("999")
            assertThat(messages.count { it.messageId == null }).isEqualTo(1)
        } finally {
            release.complete(Unit)
            handler.close()
            otherHandler.close()
            worker.join()
            otherWorker.join()
        }
    }
}
