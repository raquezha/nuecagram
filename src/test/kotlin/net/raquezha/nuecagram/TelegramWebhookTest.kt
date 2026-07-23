package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import net.raquezha.nuecagram.db.InstallationRepository
import org.junit.Test

class TelegramWebhookTest : BaseEventTestHelper() {
    @Test
    fun rejectsMissingAndInvalidAuthenticationAndMalformedUpdates() =
        testApplication {
            configureTestApplication()
            assertThat(postTelegram("{}", null).status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(postTelegram("{}", "wrong").status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(postTelegram("{}").status).isEqualTo(HttpStatusCode.BadRequest)
        }

    @Test
    fun recordsPrivateStartOnlyOnce() =
        testApplication {
            configureTestApplication()
            val update =
                """{"update_id":42,"message":{"text":"/start","chat":{"id":7,"type":"private"},"from":{"id":7}}}"""
            assertThat(postTelegram(update).status).isEqualTo(HttpStatusCode.OK)
            assertThat(postTelegram(update).status).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages()).hasSize(1)
            assertThat(runBlocking { InstallationRepository().telegramPrivateChatId(7) }).isEqualTo(7)
        }

    private suspend fun ApplicationTestBuilder.postTelegram(
        body: String,
        token: String? = "test-telegram-webhook-token",
    ) =
        client.post("/nuecagram/telegram/webhook") {
            contentType(ContentType.Application.Json)
            setBody(body)
            if (token != null) header("X-Telegram-Bot-Api-Secret-Token", token)
        }
}
