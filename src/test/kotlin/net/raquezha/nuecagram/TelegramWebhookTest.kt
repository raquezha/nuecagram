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
            val update = privateUpdate(42, "/start", userId = 42)
            assertThat(postTelegram(update).status).isEqualTo(HttpStatusCode.OK)
            assertThat(postTelegram(update).status).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages()).hasSize(1)
            assertThat(runBlocking { installationRepository.telegramPrivateChatId(7) }).isEqualTo(7)
        }

    @Test
    fun statusRequiresValidInstallationIdAndPrivateBootstrap() =
        testApplication {
            configureTestApplication()
            val initialAuditCount = auditEventCount()

            assertThat(postTelegram(groupUpdate(50, "/status nope")).status).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Usage: /status <installation-id>")

            assertThat(
                postTelegram(groupUpdate(51, "/status ${installation.id}", userId = 51)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().last().text,
            ).isEqualTo("Use /start in a private chat before using admin commands.")
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
        }

    @Test
    fun statusRequiresTelegramAdministratorAndChatOwnership() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(52)
            val initialAuditCount = auditEventCount()

            assertThat(
                postTelegram(groupUpdate(52, "/status ${installation.id}", userId = 52)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().last().text,
            ).isEqualTo("Only Telegram group administrators can use this command.")

            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 52, "administrator")
            val other =
                runBlocking {
                    installationRepository.createInstallation(
                        gitlabBaseUrl = INSTANCE,
                        gitlabProjectId = 999,
                        telegramChatId = installation.telegramChatId + 1,
                        telegramTopicId = null,
                    )
                }
            assertThat(
                postTelegram(groupUpdate(53, "/status ${other.id}", userId = 52)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Installation not found in this chat.")
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
        }

    @Test
    fun statusTreatsTelegramAdminLookupFailureAsUnauthorized() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(55)
            mockTelegramService().failChatMemberLookups()
            val initialAuditCount = auditEventCount()

            assertThat(
                postTelegram(groupUpdate(55, "/status ${installation.id}", userId = 55)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().last().text,
            ).isEqualTo("Only Telegram group administrators can use this command.")
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
        }

    @Test
    fun statusAllowsTelegramAdministratorsForOwnedInstallation() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(54)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 54, "creator")

            assertThat(
                postTelegram(groupUpdate(54, "/status ${installation.id}", userId = 54)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).contains(installation.id.toString())
            assertThat(sentMessages().last().text).contains("Muted: no")
            assertThat(sentMessages().last().text).contains(installation.gitlabBaseUrl)
        }

    private fun bootstrapPrivateUser(userId: Long) {
        runBlocking {
            installationRepository.upsertTelegramPrivateChat(userId, userId)
        }
    }

    private fun privateUpdate(updateId: Long, text: String, userId: Long) =
        """
        {"update_id":$updateId,"message":{"text":"$text","chat":{"id":$userId,"type":"private"},"from":{"id":$userId}}}
        """.trimIndent()

    private fun groupUpdate(updateId: Long, text: String, userId: Long = updateId) =
        """
        {"update_id":$updateId,"message":{"text":"$text","chat":{"id":${installation.telegramChatId},"type":"group"},"from":{"id":$userId}}}
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
