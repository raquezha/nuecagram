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
import net.raquezha.nuecagram.telegram.TelegramUpdate
import org.junit.Test

private const val GROUP_DM_REDIRECT_MESSAGE =
    "Continue in a private chat with <b>@NuecagramBot</b> to manage connected repositories."

@Suppress("TooManyFunctions")
class TelegramOnboardingWebhookTest : BaseEventTestHelper() {
    @Test
    fun helpCommandInGroupExplainsDmManagement() =
        testApplication {
            configureTestApplication()

            val response = postTelegram(
                groupUpdate(69, "/help", installation.telegramChatId, userId = 69, messageThreadId = 100),
            )
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val lastMessage = sentMessages().last()
            assertThat(lastMessage.text).contains("Nuecagram is managed in private chat")
            assertThat(lastMessage.text).contains("tap <b>OPEN</b>")
            val button = lastMessage.replyMarkup!!.inlineKeyboard.single().single()
            assertThat(button.text).isEqualTo("Open @NuecagramBot")
            assertThat(button.url).isEqualTo("https://t.me/NuecagramBot")
        }

    @Test
    fun groupHelpRecordsKnownTelegramDestination() =
        testApplication {
            configureTestApplication()

            assertThat(
                postTelegram(
                    groupUpdate(
                        68,
                        "/help",
                        installation.telegramChatId,
                        userId = 68,
                        messageThreadId = 123,
                        chatTitle = "Mobile Devs",
                    ),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                postTelegram(
                    groupUpdate(
                        67,
                        "/help",
                        installation.telegramChatId,
                        userId = 67,
                        messageThreadId = 123,
                        chatTitle = "Mobile Devs",
                    ),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)

            val destinations = runBlocking { installationRepository.knownTelegramDestinations() }
                .filter { it.telegramChatId == installation.telegramChatId && it.telegramTopicId == 123L }
            assertThat(destinations).hasSize(1)
            assertThat(destinations.single().chatTitle).isEqualTo("Mobile Devs")
        }

    @Test
    fun removedSetupCommandRedirectsGroupToDm() =
        testApplication {
            configureTestApplication()
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 70, "administrator")
            val initialAuditCount = auditEventCount()
            val initialLaunchAuditCount = auditActionCount("telegram_webapp_launch")

            assertThat(
                postTelegram(
                    groupUpdate(70, "/setup", installation.telegramChatId, userId = 70),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo(GROUP_DM_REDIRECT_MESSAGE)
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
            assertThat(auditActionCount("telegram_webapp_launch")).isEqualTo(initialLaunchAuditCount)
        }

    @Test
    fun removedSetupCommandDoesNotRequireGroupAdminCheck() =
        testApplication {
            configureTestApplication()
            val initialAuditCount = auditEventCount()
            val initialLaunchAuditCount = auditActionCount("telegram_webapp_launch")

            assertThat(
                postTelegram(
                    groupUpdate(175, "/setup", installation.telegramChatId, userId = 75),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo(GROUP_DM_REDIRECT_MESSAGE)
            assertThat(installationCount("https://gitlab.example.com", 321L)).isEqualTo(0)
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
            assertThat(auditActionCount("telegram_webapp_launch")).isEqualTo(initialLaunchAuditCount)
        }

    @Test
    fun removedSetupCommandRedirectsWithoutUsageMessage() =
        testApplication {
            configureTestApplication()

            assertThat(
                postTelegram(
                    groupUpdate(176, "/setup", installation.telegramChatId, userId = 76),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo(GROUP_DM_REDIRECT_MESSAGE)
        }

    @Test
    fun removedSetupCommandIncludesDmButtonInGroup() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(77)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 77, "administrator")

            assertThat(
                postTelegram(
                    groupUpdate(177, "/setup", installation.telegramChatId, userId = 77),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)

            val lastMessage = sentMessages().last()
            assertThat(lastMessage.text).isEqualTo(GROUP_DM_REDIRECT_MESSAGE)
            val button = lastMessage.replyMarkup!!.inlineKeyboard.single().single()
            assertThat(button.text).isEqualTo("Open @NuecagramBot")
            assertThat(button.url).isEqualTo("https://t.me/NuecagramBot")
        }

    @Test
    fun removedSetupCommandDoesNotLaunchWebAppOrCreateInstallation() {
        val previous = System.getProperty("nuecagram.publicUrl")
        System.setProperty("nuecagram.publicUrl", "https://android.nweca.com/nuecagram")
        try {
            testApplication {
                configureTestApplication()
                bootstrapPrivateUser(71)
                mockTelegramService().setChatMemberStatus(installation.telegramChatId, 71, "administrator")
                val initialLaunchAuditCount = auditActionCount("telegram_webapp_launch")

                assertThat(
                    postTelegram(
                        groupUpdate(
                            71,
                            "/setup",
                            installation.telegramChatId,
                            userId = 71,
                            messageThreadId = 777,
                            username = "alice71",
                            firstName = "Alice",
                        ),
                    ).status,
                ).isEqualTo(HttpStatusCode.OK)

                val groupMessage = messagesForChat(installation.telegramChatId).single()
                assertThat(groupMessage.text).isEqualTo(GROUP_DM_REDIRECT_MESSAGE)
                assertThat(messagesForChat(71)).isEmpty()
                assertThat(installationCount("https://gitlab.example.com", 321L)).isEqualTo(0)
                assertThat(auditActionCount("telegram_webapp_launch")).isEqualTo(initialLaunchAuditCount)
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
    fun manageSendsPrivateMenuOnlyOnceForDuplicateUpdates() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(72)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 72, "administrator")
            val initialLinkAuditCount = auditActionCount("telegram_management_link")

            val command = privateUpdate(72, "/manage ${installation.id}", userId = 72)
            assertThat(postTelegram(command).status).isEqualTo(HttpStatusCode.OK)
            assertThat(postTelegram(command).status).isEqualTo(HttpStatusCode.OK)

            val privateMessages = messagesForChat(72)
            assertThat(privateMessages).hasSize(1)
            val detailsMessage = privateMessages.single()
            assertThat(detailsMessage.text).contains("Repository:")
            assertThat(detailsMessage.text).contains(installation.repoName)
            assertThat(detailsMessage.replyMarkup).isNotNull()
            assertThat(auditActionCount("telegram_management_link")).isEqualTo(initialLinkAuditCount)
        }

    @Test
    fun rotatePromptsForConfirmationBeforeRevokingCredential() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(73)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 73, "creator")
            val oldCredential = runBlocking { installationRepository.issueWebhookSecret(installation.id).raw }
            val initialRotateAuditCount = auditActionCount("telegram_rotate")
            val initialLinkAuditCount = auditActionCount("telegram_management_link")

            assertThat(
                postTelegram(privateUpdate(73, "/rotate ${installation.id}", userId = 73)).status,
            ).isEqualTo(HttpStatusCode.OK)

            val privateMessages = messagesForChat(73)
            assertThat(privateMessages).hasSize(1)
            val privateMessage = privateMessages.single()
            assertThat(privateMessage.text).contains("Rotate Webhook Secret")
            assertThat(privateMessage.text).contains("Are you sure you want to rotate")
            assertThat(privateMessage.replyMarkup).isNotNull()
            assertThat(privateMessage.replyMarkup!!.inlineKeyboard.first().first().callbackData)
                .isEqualTo("inst:rotate:execute:${installation.id}")
            assertThat(runBlocking { installationRepository.verifyWebhookSecret(oldCredential) }).isNotNull()
            assertThat(auditActionCount("telegram_rotate")).isEqualTo(initialRotateAuditCount)
            assertThat(auditActionCount("telegram_management_link")).isEqualTo(initialLinkAuditCount)
        }

    @Test
    fun webappCommandInGroupRedirectsToDm() =
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
            assertThat(lastMessage.text).isEqualTo(GROUP_DM_REDIRECT_MESSAGE)
            val button = lastMessage.replyMarkup!!.inlineKeyboard.single().single()
            assertThat(button.text).isEqualTo("Open @NuecagramBot")
            assertThat(button.url).isEqualTo("https://t.me/NuecagramBot")
        }

    @Test
    fun startCommandInPrivateChatReturnsWelcomeWithoutInlineButton() =
        testApplication {
            configureTestApplication()
            val privateUpdate = """
            {"update_id":75,"message":{"text":"/start","chat":{"id":75,"type":"private"},"from":{"id":75}}}
            """.trimIndent()

            val response = postTelegram(privateUpdate)
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)

            val lastMessage = sentMessages().last()
            assertThat(lastMessage.chatId).isEqualTo("75")
            assertThat(lastMessage.text).contains("Nuecagram GitLab Notification Gateway")
            assertThat(lastMessage.text).contains("OPEN")
            assertThat(lastMessage.replyMarkup).isNull()
        }

    @Test
    fun statusCommandReturnsStatusDetailsInDm() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(76)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 76, "administrator")

            val command = privateUpdate(76, "/status ${installation.id}", userId = 76)
            assertThat(postTelegram(command).status).isEqualTo(HttpStatusCode.OK)

            val lastMessage = sentMessages().last()
            assertThat(lastMessage.chatId).isEqualTo("76")
            assertThat(lastMessage.text).contains("Installation Status")
            assertThat(lastMessage.text).contains(installation.gitlabBaseUrl)
            assertThat(lastMessage.replyMarkup).isNull()
        }

    private fun bootstrapPrivateUser(userId: Long) {
        runBlocking {
            installationRepository.upsertTelegramPrivateChat(userId, userId)
        }
        mockTelegramService().setChatMemberStatus(installation.telegramChatId, userId, "administrator")
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
    username: String? = null,
    firstName: String? = null,
    chatTitle: String? = null,
): String =
    Json.encodeToString(
        TelegramUpdate(
            updateId = updateId,
            message = TelegramUpdate.Message(
                text = text,
                chat = TelegramUpdate.Chat(id = chatId, type = "group", title = chatTitle),
                from = TelegramUpdate.User(id = userId, username = username, firstName = firstName),
                messageThreadId = messageThreadId,
            ),
        ),
    )

private fun privateUpdate(updateId: Long, text: String, userId: Long): String =
    Json.encodeToString(
        TelegramUpdate(
            updateId = updateId,
            message = TelegramUpdate.Message(
                text = text,
                chat = TelegramUpdate.Chat(id = userId, type = "private"),
                from = TelegramUpdate.User(userId),
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
