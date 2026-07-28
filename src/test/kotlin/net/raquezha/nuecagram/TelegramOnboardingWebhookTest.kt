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
import net.raquezha.nuecagram.telegram.Message
import org.junit.Test

class TelegramOnboardingWebhookTest : BaseEventTestHelper() {
    @Test
    fun setupRequiresPrivateBootstrap() =
        testApplication {
            configureTestApplication()
            val initialAuditCount = auditEventCount()

            assertThat(
                postTelegram(
                    groupUpdate(70, "/setup https://gitlab.example.com 321", installation.telegramChatId, userId = 70),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().last().text,
            ).isEqualTo("Use /start in a private chat before using admin commands.")
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
            assertThat(auditActionCount("telegram_setup")).isEqualTo(0)
        }

    @Test
    fun setupSendsCredentialOnlyToPrivateChatAndAudits() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(71)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 71, "administrator")
            val initialSetupAuditCount = auditActionCount("telegram_setup")
            val initialLinkAuditCount = auditActionCount("telegram_management_link")

            assertThat(
                postTelegram(
                    groupUpdate(
                        71,
                        "/setup https://gitlab.example.com 321 777",
                        installation.telegramChatId,
                        userId = 71,
                    ),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)

            val privateMessage = messagesForChat(71).single()
            val groupMessage = messagesForChat(installation.telegramChatId).single()
            assertThat(privateMessage.text).contains("GitLab credential:")
            assertThat(privateMessage.text).contains("Management URL:")
            assertThat(privateMessage.text).contains("Webhook URL:")
            assertThat(groupMessage.text).isEqualTo("Private setup details sent.")
            assertThat(groupMessage.text).doesNotContain("GitLab credential:")
            assertThat(groupMessage.text).doesNotContain("Management URL:")
            assertThat(installationCount("https://gitlab.example.com", 321L)).isEqualTo(1)
            assertThat(auditActionCount("telegram_setup")).isEqualTo(initialSetupAuditCount + 1)
            assertThat(auditActionCount("telegram_management_link")).isEqualTo(initialLinkAuditCount + 1)
        }

    @Test
    fun manageSendsPrivateLinkOnlyOnceForDuplicateUpdates() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(72)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 72, "administrator")
            val initialLinkAuditCount = auditActionCount("telegram_management_link")

            val command =
                groupUpdate(72, "/manage ${installation.id}", installation.telegramChatId, userId = 72)
            assertThat(postTelegram(command).status).isEqualTo(HttpStatusCode.OK)
            assertThat(postTelegram(command).status).isEqualTo(HttpStatusCode.OK)

            val privateMessage = messagesForChat(72).single()
            val groupMessage = messagesForChat(installation.telegramChatId).single()
            assertThat(privateMessage.text).contains("Management for ${installation.id}")
            assertThat(privateMessage.text).contains("/nuecagram/manage/")
            assertThat(groupMessage.text).isEqualTo("Private setup details sent.")
            assertThat(groupMessage.text).doesNotContain("/nuecagram/manage/")
            assertThat(auditActionCount("telegram_management_link")).isEqualTo(initialLinkAuditCount + 1)
            assertThat(sentMessages()).hasSize(2)
        }

    @Test
    fun rotateSendsNewCredentialPrivatelyAndRevokesOldCredential() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(73)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 73, "creator")
            val oldCredential = runBlocking { installationRepository.issueWebhookSecret(installation.id).raw }
            val initialRotateAuditCount = auditActionCount("telegram_rotate")
            val initialLinkAuditCount = auditActionCount("telegram_management_link")

            assertThat(
                postTelegram(
                    groupUpdate(73, "/rotate ${installation.id}", installation.telegramChatId, userId = 73),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)

            val privateMessage = messagesForChat(73).single()
            val groupMessage = messagesForChat(installation.telegramChatId).single()
            val rotatedCredential = privateMessage.text.substringAfter("GitLab credential: ").substringBefore('\n')
            assertThat(rotatedCredential).isNotEqualTo(oldCredential)
            assertThat(privateMessage.text).contains("Management URL:")
            assertThat(groupMessage.text).isEqualTo("Private setup details sent.")
            assertThat(groupMessage.text).doesNotContain(oldCredential)
            assertThat(groupMessage.text).doesNotContain(rotatedCredential)
            assertThat(runBlocking { installationRepository.verifyWebhookSecret(oldCredential) }).isNull()
            assertThat(runBlocking { installationRepository.verifyWebhookSecret(rotatedCredential) }).isNotNull()
            assertThat(auditActionCount("telegram_rotate")).isEqualTo(initialRotateAuditCount + 1)
            assertThat(auditActionCount("telegram_management_link")).isEqualTo(initialLinkAuditCount + 1)
        }

    private fun bootstrapPrivateUser(userId: Long) {
        runBlocking {
            installationRepository.upsertTelegramPrivateChat(userId, userId)
        }
    }

    private fun messagesForChat(chatId: Long): List<Message> =
        sentMessages().filter { it.chatId == chatId.toString() }

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

    private fun installationCount(gitlabBaseUrl: String, projectId: Long): Long =
        runBlocking {
            DatabaseFactory.dbQuery { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM installations WHERE gitlab_base_url = ? AND gitlab_project_id = ?",
                ).use { statement ->
                    statement.setString(1, gitlabBaseUrl)
                    statement.setLong(2, projectId)
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getLong(1)
                    }
                }
            }
        }
}

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
