package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.raquezha.nuecagram.webhook.WebHookService
import org.junit.Test

class AuthenticatedWebhookRoutingTest : BaseEventTestHelper() {
    @Test
    fun rejectsInvalidExpiredAndSupersededTokens() =
        testApplication {
            configureTestApplication()
            val expired =
                runBlocking {
                    installationRepository.issueWebhookSecret(installation.id, Instant.now().minusSeconds(60)).raw
                }
            val rotated =
                runBlocking {
                    installationRepository.rotateWebhookSecret(installation.id, Instant.now().minusSeconds(60))
                }

            val invalidToken = "not-a-valid-token"
            val invalidResponse = pipelineResponse(invalidToken)
            assertThat(invalidResponse.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(invalidResponse.bodyAsText()).doesNotContain(invalidToken)
            assertThat(pipelineResponse(expired).status)
                .isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(pipelineResponse(webhookToken).status)
                .isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(pipelineResponse(rotated.raw).status)
                .isEqualTo(HttpStatusCode.OK)
        }

    @Test
    fun usesStoredDestinationConfirmsRotationAndSkipsMute() =
        testApplication {
            configureTestApplication()
            val rotated =
                runBlocking {
                    installationRepository.rotateWebhookSecret(installation.id, Instant.now().plusSeconds(60))
                }

            val response = pipelineResponse(rotated.raw)
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            delay(100)
            assertThat(sentMessages().first().chatId).isEqualTo(installation.telegramChatId.toString())
            assertThat(runBlocking { installationRepository.confirmWebhookSecret(rotated.id) }).isFalse()

            runBlocking { installationRepository.setMuted(installation.id, true) }
            val mutedResponse = pipelineResponse(rotated.raw)
            assertThat(mutedResponse.status).isEqualTo(HttpStatusCode.OK)
            assertThat(mutedResponse.bodyAsText()).isEqualTo("Event skipped: not relevant")
        }

    @Test
    fun isolatesPipelineTrackingAndRejectsOversizedPayloads() =
        testApplication {
            configureTestApplication()
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val service = WebHookService(KotlinLogging.logger { }, installationRepository)
            service.setPipelineMessageId(first, 7, "first")
            service.setPipelineMessageId(second, 7, "second")
            assertThat(service.getPipelineMessageId(first, 7)).isEqualTo("first")
            assertThat(service.getPipelineMessageId(second, 7)).isEqualTo("second")

            val response = postWebhookResponse(EVENT_PIPELINE, "x".repeat(1_048_577))
            assertThat(response.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.pipelineResponse(value: String) =
        postWebhookResponse(
            EVENT_PIPELINE,
            PipelineEventWebhookTest.SAMPLE_PAYLOAD_RUNNING,
            value,
        )
}
