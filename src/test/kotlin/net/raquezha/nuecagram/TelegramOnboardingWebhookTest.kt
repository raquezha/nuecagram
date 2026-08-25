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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramChat
import net.raquezha.nuecagram.telegram.TelegramMessage
import net.raquezha.nuecagram.telegram.TelegramUpdate
import net.raquezha.nuecagram.telegram.TelegramUser
import org.junit.Test

@Suppress("TooManyFunctions")
class TelegramOnboardingWebhookTest : BaseEventTestHelper() {
    @Test
    fun setupRequiresPrivateBootstrap() =
        testApplication {
            configureTestApplication()
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 70, "administrator")
            val initialAuditCount = auditEventCount()
            val initialSetupAuditCount = auditActionCount("telegram_setup")

            assertThat(
                postTelegram(
                    groupUpdate(70, "/setup https://gitlab.example.com 321", installation.telegramChatId, userId = 70),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().last().text,
            ).isEqualTo("Use /start in a private chat before using admin commands.")
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
            assertThat(auditActionCount("telegram_setup")).isEqualTo(initialSetupAuditCount)
        }

    @Test
    fun setupRejectsNonAdminBeforePrivateBootstrap() =
        testApplication {
            configureTestApplication()
            val initialAuditCount = auditEventCount()
            val initialSetupAuditCount = auditActionCount("telegram_setup")

            assertThat(
                postTelegram(
                    groupUpdate(175, "/setup https://gitlab.example.com 321", installation.telegramChatId, userId = 75),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Only Telegram group administrators can use this command.")
            assertThat(installationCount("https://gitlab.example.com", 321L)).isEqualTo(0)
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
            assertThat(auditActionCount("telegram_setup")).isEqualTo(initialSetupAuditCount)
        }

    @Test
    fun setupRejectsNonAdminBeforeUsageMessage() =
        testApplication {
            configureTestApplication()

            assertThat(
                postTelegram(
                    groupUpdate(176, "/setup", installation.telegramChatId, userId = 76),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Only Telegram group administrators can use this command.")
        }

    @Test
    fun setupShowsUsageToConfirmedAdminWithoutArguments() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(77)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 77, "administrator")

            assertThat(
                postTelegram(
                    groupUpdate(177, "/setup", installation.telegramChatId, userId = 77),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().last().text,
            ).isEqualTo(
                "Usage: <code>/setup &lt;gitlab-base-url&gt; &lt;project-id&gt;</code>\n" +
                    "Example: <code>/setup https://gitlab.com 12345678</code>",
            )
        }

    @Test
    fun setupSendsCredentialOnlyToPrivateChatAndAudits() {
        val previous = System.getProperty("nuecagram.publicUrl")
        System.setProperty("nuecagram.publicUrl", "https://android.nweca.com/nuecagram")
        try {
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
                            "/setup https://gitlab.example.com 321",
                            installation.telegramChatId,
                            userId = 71,
                            messageThreadId = 777,
                        ),
                    ).status,
                ).isEqualTo(HttpStatusCode.OK)

                val privateMessage = messagesForChat(71).single()
                val groupMessage = messagesForChat(installation.telegramChatId).single()
                assertThat(privateMessage.text).contains("GitLab secret token:")
                assertThat(privateMessage.text).contains("Management URL:")
                assertThat(privateMessage.text).contains("Webhook URL:")
                assertThat(privateMessage.text).contains("https://android.nweca.com/nuecagram/webhook")
                assertThat(privateMessage.text).contains("https://android.nweca.com/nuecagram/manage/")
                assertThat(groupMessage.text).isEqualTo("Private setup details sent.")
                assertThat(groupMessage.text).doesNotContain("GitLab secret token:")
                assertThat(groupMessage.text).doesNotContain("Management URL:")
                assertThat(installationCount("https://gitlab.example.com", 321L)).isEqualTo(1)
                assertThat(installationTopicId("https://gitlab.example.com", 321L)).isEqualTo(777)
                assertThat(auditActionCount("telegram_setup")).isEqualTo(initialSetupAuditCount + 1)
                assertThat(auditActionCount("telegram_management_link")).isEqualTo(initialLinkAuditCount + 1)
            }
        } finally {
            if (previous == null) {
                System.clearProperty("nuecagram.publicUrl")
            } else {
                System.setProperty("nuecagram.publicUrl", previous)
            }
        }
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
            val rotatedCredential = privateMessage.text.substringAfter("GitLab secret token: ").substringBefore('\n')
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

    @Test
    fun webappCommandAttachesInlineButtonWithNonce() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(74)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 74, "administrator")

            val response = postTelegram(
                groupUpdate(74, "/webapp", installation.telegramChatId, userId = 74, messageThreadId = 100),
            )
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val lastMessage = sentMessages().last()
            assertThat(lastMessage.chatId).isEqualTo(installation.telegramChatId.toString())
            assertThat(lastMessage.text).contains("Open Nuecagram Web App:")
            assertThat(lastMessage.replyMarkup).isNotNull()
            val button = lastMessage.replyMarkup!!.inlineKeyboard.single().single()
            assertThat(button.text).isEqualTo("Open Nuecagram Web App")
            assertThat(button.webApp).isNotNull()
            assertThat(button.webApp!!.url).contains("/webapp?startapp=nonce_")
        }

    @Test
    fun startCommandInPrivateChatAttachesInlineButton() =
        testApplication {
            configureTestApplication()
            val privateUpdate = """
            {"update_id":75,"message":{"text":"/start","chat":{"id":75,"type":"private"},"from":{"id":75}}}
            """.trimIndent()

            val response = postTelegram(privateUpdate)
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val lastMessage = sentMessages().last()
            assertThat(lastMessage.chatId).isEqualTo("75")
            assertThat(lastMessage.text).contains("Private onboarding is ready.")
            assertThat(lastMessage.replyMarkup).isNotNull()
            val button = lastMessage.replyMarkup!!.inlineKeyboard.single().single()
            assertThat(button.webApp).isNotNull()
            assertThat(button.webApp!!.url).contains("/webapp?startapp=nonce_")
        }

    @Test
    fun statusCommandAttachesInlineButtonWithNonce() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(76)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 76, "administrator")

            val command = groupUpdate(76, "/status ${installation.id}", installation.telegramChatId, userId = 76)
            assertThat(postTelegram(command).status).isEqualTo(HttpStatusCode.OK)

            val lastMessage = sentMessages().last()
            assertThat(lastMessage.chatId).isEqualTo(installation.telegramChatId.toString())
            assertThat(lastMessage.text).contains("Installation:")
            assertThat(lastMessage.replyMarkup).isNotNull()
            val button = lastMessage.replyMarkup!!.inlineKeyboard.single().single()
            assertThat(button.webApp).isNotNull()
            assertThat(button.webApp!!.url).contains("/webapp?startapp=nonce_")
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
        installationValue(gitlabBaseUrl, projectId, "COUNT(*)") as Long

    private fun installationTopicId(gitlabBaseUrl: String, projectId: Long): Long? =
        installationValue(gitlabBaseUrl, projectId, "telegram_topic_id") as Long?

    private fun installationValue(
        gitlabBaseUrl: String,
        projectId: Long,
        column: String,
    ): Any? =
        runBlocking {
            DatabaseFactory.dbQuery { connection ->
                connection.prepareStatement(
                    "SELECT $column FROM installations WHERE gitlab_base_url = ? AND gitlab_project_id = ?",
                ).use { statement ->
                    statement.setString(1, gitlabBaseUrl)
                    statement.setLong(2, projectId)
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getObject(1)
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
    messageThreadId: Long? = null,
): String =
    Json.encodeToString(
        TelegramUpdate(
            updateId = updateId,
            message = TelegramMessage(
                text = text,
                chat = TelegramChat(id = chatId, type = "group"),
                from = TelegramUser(userId),
                messageThreadId = messageThreadId,
            ),
        ),
    )

private suspend fun ApplicationTestBuilder.postTelegram(
    body: String,
    token: String? = "test-telegram-webhook-token",
) =
    client.post("/nuecagram/telegram/webhook") {
        contentType(ContentType.Application.Json)
        setBody(body)
        if (token != null) header("X-Telegram-Bot-Api-Secret-Token", token)
    }
