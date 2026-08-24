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

@Suppress("TooManyFunctions")
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
            assertThat(runBlocking { installationRepository.telegramPrivateChatId(42) }).isEqualTo(42)
        }

    @Test
    fun statusRequiresValidInstallationIdAndChecksTelegramAdminBeforePrivateBootstrap() =
        testApplication {
            configureTestApplication()
            val initialAuditCount = auditEventCount()

            assertThat(
                postTelegram(groupUpdate(50, "/status", installation.telegramChatId)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Usage: <code>/status &lt;installation-id&gt;</code>")

            assertThat(
                postTelegram(
                    groupUpdate(51, "/status ${installation.id}", installation.telegramChatId, userId = 51),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().last().text,
            ).isEqualTo("Only Telegram group administrators can use this command.")
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
        }

    @Test
    fun statusSupportsShort8CharInstallationIdPrefix() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(88)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 88, "administrator")

            val shortId = installation.id.toString().take(8)
            assertThat(
                postTelegram(
                    groupUpdate(88, "/status $shortId", installation.telegramChatId, userId = 88),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)

            val response = sentMessages().last().text
            assertThat(response).contains("Installation: ${installation.id}")
            assertThat(response).contains("GitLab: ${installation.gitlabBaseUrl}")
        }

    @Test
    fun statusRequiresTelegramAdministratorAndChatOwnership() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(52)
            val initialAuditCount = auditEventCount()

            assertThat(
                postTelegram(
                    groupUpdate(52, "/status ${installation.id}", installation.telegramChatId, userId = 52),
                ).status,
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
                postTelegram(
                    groupUpdate(53, "/status ${other.id}", installation.telegramChatId, userId = 52),
                ).status,
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
                postTelegram(
                    groupUpdate(55, "/status ${installation.id}", installation.telegramChatId, userId = 55),
                ).status,
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
                postTelegram(
                    groupUpdate(54, "/status ${installation.id}", installation.telegramChatId, userId = 54),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).contains(installation.id.toString())
            assertThat(sentMessages().last().text).contains("Muted: no")
            assertThat(sentMessages().last().text).contains(installation.gitlabBaseUrl)
        }

    @Test
    fun digestReturnsSafeInstallationSummary() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(60)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 60, "administrator")

            assertThat(
                postTelegram(
                    groupUpdate(60, "/digest ${installation.id}", installation.telegramChatId, userId = 60),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).contains("Digest for ${installation.id}")
            assertThat(sentMessages().last().text).contains(installation.gitlabBaseUrl)
            assertThat(sentMessages().last().text).contains("Muted: no")
        }

    @Test
    fun testSendsDeliveryMessageToStoredDestinationAndAuditsOnce() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(61)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 61, "administrator")

            assertThat(
                postTelegram(
                    groupUpdate(61, "/test ${installation.id}", installation.telegramChatId, userId = 61),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            val delivered = sentMessages().last()
            assertThat(delivered.chatId).isEqualTo(installation.telegramChatId.toString())
            assertThat(delivered.threadId).isEqualTo(installation.telegramTopicId)
            assertThat(delivered.text).contains(installation.id.toString())
            assertThat(auditActionCount("telegram_delivery_test")).isEqualTo(1)

            assertThat(
                postTelegram(
                    groupUpdate(61, "/test ${installation.id}", installation.telegramChatId, userId = 61),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages()).hasSize(1)
            assertThat(auditActionCount("telegram_delivery_test")).isEqualTo(1)
        }

    @Test
    fun muteAndUnmutePersistAndAuditSuccessfulCommands() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(62)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 62, "administrator")
            val initialMuteCount = auditActionCount("telegram_mute")
            val initialUnmuteCount = auditActionCount("telegram_unmute")

            assertThat(
                postTelegram(
                    groupUpdate(62, "/mute ${installation.id}", installation.telegramChatId, userId = 62),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Installation muted.")
            assertThat(installationMuted(installation.id)).isTrue()
            assertThat(auditActionCount("telegram_mute")).isEqualTo(initialMuteCount + 1)

            assertThat(
                postTelegram(
                    groupUpdate(63, "/unmute ${installation.id}", installation.telegramChatId, userId = 62),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Installation unmuted.")
            assertThat(installationMuted(installation.id)).isFalse()
            assertThat(auditActionCount("telegram_unmute")).isEqualTo(initialUnmuteCount + 1)
        }

    @Test
    fun unauthorizedMuteIsNoOpAndWritesNoAudit() =
        testApplication {
            configureTestApplication()
            val initialAuditCount = auditEventCount()
            val initialMuteCount = auditActionCount("telegram_mute")

            assertThat(
                postTelegram(
                    groupUpdate(64, "/mute ${installation.id}", installation.telegramChatId, userId = 64),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().last().text,
            ).isEqualTo("Only Telegram group administrators can use this command.")
            assertThat(installationMuted(installation.id)).isFalse()
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
            assertThat(auditActionCount("telegram_mute")).isEqualTo(initialMuteCount)
        }

    private fun bootstrapPrivateUser(userId: Long) {
        runBlocking {
            installationRepository.upsertTelegramPrivateChat(userId, userId)
        }
    }

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

    private fun auditActionCount(action: String): Long =
        runBlocking {
            DatabaseFactory.dbQuery { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM audit_events WHERE action = ?").use { statement ->
                    statement.setString(1, action)
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getLong(1)
                    }
                }
            }
        }

    private fun installationMuted(installationId: java.util.UUID): Boolean =
        runBlocking {
            installationRepository.installationAdminContext(installationId)?.muted ?: false
        }
}

private fun privateUpdate(updateId: Long, text: String, userId: Long) =
    """
    {"update_id":$updateId,"message":{"text":"$text","chat":{"id":$userId,"type":"private"},"from":{"id":$userId}}}
    """.trimIndent()

private fun groupUpdate(
    updateId: Long,
    text: String,
    chatId: Long,
    userId: Long = updateId,
) =
    """
    {"update_id":$updateId,"message":{"text":"$text","chat":{"id":$chatId,"type":"group"},"from":{"id":$userId}}}
    """.trimIndent()

private suspend fun ApplicationTestBuilder.postTelegram(
    body: String,
    token: String? = "test-telegram-webhook-token",
) =
    client.post("/nuecagram/telegram/webhook") {
        contentType(ContentType.Application.Json)
        setBody(body)
        if (token != null) header("X-Telegram-Bot-Api-Secret-Token", token)
    }
