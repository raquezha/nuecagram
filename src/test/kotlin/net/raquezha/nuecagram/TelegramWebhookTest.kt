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
import net.raquezha.nuecagram.db.DatabaseFactory
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
            val update = privateUpdate(42, "/start")
            assertThat(postTelegram(update).status).isEqualTo(HttpStatusCode.OK)
            assertThat(postTelegram(update).status).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages()).hasSize(1)
            assertThat(runBlocking { installationRepository.telegramPrivateChatId(7) }).isEqualTo(7)
        }

    @Test
    fun statusRequiresValidInstallationIdAndPrivateBootstrap() =
        testApplication {
            configureTestApplication()
            assertThat(postTelegram(groupUpdate(50, "/status nope")).status).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Usage: /status <installation-id>")

            assertThat(
                postTelegram(groupUpdate(51, "/status ${installation.id}")).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().last().text,
            ).isEqualTo("Use /start in a private chat before using admin commands.")
            assertThat(auditEventCount()).isEqualTo(0)
        }

    @Test
    fun statusRequiresTelegramAdministratorAndChatOwnership() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(7)

            assertThat(postTelegram(groupUpdate(52, "/status ${installation.id}")).status).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Only Telegram group administrators can use this command.")

            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 7, "administrator")
            val other =
                runBlocking {
                    installationRepository.createInstallation(
                        gitlabBaseUrl = INSTANCE,
                        gitlabProjectId = 999,
                        telegramChatId = installation.telegramChatId + 1,
                        telegramTopicId = null,
                    )
                }
            assertThat(postTelegram(groupUpdate(53, "/status ${other.id}")).status).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Installation not found in this chat.")
            assertThat(auditEventCount()).isEqualTo(0)
        }

    @Test
    fun statusTreatsTelegramAdminLookupFailureAsUnauthorized() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(7)
            mockTelegramService().failChatMemberLookups()

            assertThat(postTelegram(groupUpdate(55, "/status ${installation.id}")).status).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Only Telegram group administrators can use this command.")
            assertThat(auditEventCount()).isEqualTo(0)
        }

    @Test
    fun statusAllowsTelegramAdministratorsForOwnedInstallation() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(7)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 7, "creator")

            assertThat(postTelegram(groupUpdate(54, "/status ${installation.id}")).status).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).contains(installation.id.toString())
            assertThat(sentMessages().last().text).contains("Muted: no")
            assertThat(sentMessages().last().text).contains(installation.gitlabBaseUrl)
        }

    private fun bootstrapPrivateUser(userId: Long) {
        runBlocking {
            installationRepository.upsertTelegramPrivateChat(userId, userId)
        }
    }

    private fun privateUpdate(updateId: Long, text: String) =
        """{"update_id":$updateId,"message":{"text":"$text","chat":{"id":7,"type":"private"},"from":{"id":7}}}"""

    private fun groupUpdate(updateId: Long, text: String) =
        """
        {"update_id":$updateId,"message":{"text":"$text","chat":{"id":${installation.telegramChatId},"type":"group"},"from":{"id":7}}}
        """.trimIndent()

    private fun auditEventCount(): Long =
        runBlocking {
            DatabaseFactory.dbQuery { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM audit_events").use { statement ->
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getLong(1)
                    }
                }
            }
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
