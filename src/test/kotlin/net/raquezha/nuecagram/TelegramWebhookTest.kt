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
import net.raquezha.nuecagram.telegram.TelegramChat
import net.raquezha.nuecagram.telegram.TelegramCallbackQuery
import net.raquezha.nuecagram.telegram.TelegramMessage
import net.raquezha.nuecagram.telegram.TelegramUpdate
import net.raquezha.nuecagram.telegram.TelegramUser
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
    fun recordsInstallationAdminMembershipOnSuccessfulGroupCommand() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(970)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 970, "administrator")

            assertThat(
                runBlocking { installationRepository.installationsForAdmin(970) },
            ).isEmpty()

            assertThat(
                postTelegram(
                    groupUpdate(970, "/status ${installation.id}", installation.telegramChatId, userId = 970),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)

            val adminInstalls = runBlocking { installationRepository.installationsForAdmin(970) }
            assertThat(adminInstalls).hasSize(1)
            assertThat(adminInstalls.first().id).isEqualTo(installation.id)
        }

    @Test
    fun liveReVerificationBlocksDemotedAdminsWithStaleRecord() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(971)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 971, "administrator")

            // First execution records membership
            assertThat(
                postTelegram(
                    groupUpdate(971, "/status ${installation.id}", installation.telegramChatId, userId = 971),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                runBlocking { installationRepository.installationsForAdmin(971) },
            ).hasSize(1)

            // Admin is demoted to member
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 971, "member")

            // Destructive command execution live re-verification blocks demoted admin
            assertThat(
                postTelegram(
                    groupUpdate(972, "/rotate ${installation.id}", installation.telegramChatId, userId = 971),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().last().text,
            ).isEqualTo("Only Telegram group administrators can use this command.")

            // Mute is also blocked
            assertThat(
                postTelegram(
                    groupUpdate(973, "/mute ${installation.id}", installation.telegramChatId, userId = 971),
                ).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().last().text,
            ).isEqualTo("Only Telegram group administrators can use this command.")
            assertThat(installationMuted(installation.id)).isFalse()
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

    @Test
    fun parsesAndAnswersCallbackQuery() =
        testApplication {
            configureTestApplication()
            val update = """
            {"update_id":124,"callback_query":{"id":"cb_124","from":{"id":99},"data":"test_data"}}
            """.trimIndent()

            assertThat(postTelegram(update).status).isEqualTo(HttpStatusCode.OK)
            val answered = mockTelegramService().answeredCallbacks()
            assertThat(answered).hasSize(1)
            assertThat(answered.first().callbackQueryId).isEqualTo("cb_124")
            assertThat(answered.first().text).isEqualTo("Invalid or expired callback action.")
        }

    @Test
    fun callbackQueryMuteAndUnmuteRequiresAdminAndAudits() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(200)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 200, "administrator")
            val initialMuteCount = auditActionCount("telegram_mute")
            val initialUnmuteCount = auditActionCount("telegram_unmute")

            // Mute via callback
            val muteUpdate = callbackGroupUpdate(
                updateId = 201,
                callbackId = "cb_mute",
                data = "cb:mute:${installation.id}",
                chatId = installation.telegramChatId,
                userId = 200,
            )
            assertThat(postTelegram(muteUpdate).status).isEqualTo(HttpStatusCode.OK)
            val answeredMute = mockTelegramService().answeredCallbacks().last()
            assertThat(answeredMute.callbackQueryId).isEqualTo("cb_mute")
            assertThat(answeredMute.text).isEqualTo("Installation muted.")
            assertThat(installationMuted(installation.id)).isTrue()
            assertThat(auditActionCount("telegram_mute")).isEqualTo(initialMuteCount + 1)

            // Unmute via callback
            val unmuteUpdate = callbackGroupUpdate(
                updateId = 202,
                callbackId = "cb_unmute",
                data = "inst:unmute:${installation.id}",
                chatId = installation.telegramChatId,
                userId = 200,
            )
            assertThat(postTelegram(unmuteUpdate).status).isEqualTo(HttpStatusCode.OK)
            val answeredUnmute = mockTelegramService().answeredCallbacks().last()
            assertThat(answeredUnmute.callbackQueryId).isEqualTo("cb_unmute")
            assertThat(answeredUnmute.text).isEqualTo("Installation unmuted.")
            assertThat(installationMuted(installation.id)).isFalse()
            assertThat(auditActionCount("telegram_unmute")).isEqualTo(initialUnmuteCount + 1)
        }

    @Test
    fun callbackQueryRejectsUnauthorizedNonAdminUser() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(203)
            // User 203 is not an admin
            val initialMuteCount = auditActionCount("telegram_mute")

            val muteUpdate = callbackGroupUpdate(
                updateId = 204,
                callbackId = "cb_unauth",
                data = "cb:mute:${installation.id}",
                chatId = installation.telegramChatId,
                userId = 203,
            )
            assertThat(postTelegram(muteUpdate).status).isEqualTo(HttpStatusCode.OK)
            val answered = mockTelegramService().answeredCallbacks().last()
            assertThat(answered.callbackQueryId).isEqualTo("cb_unauth")
            assertThat(answered.text).isEqualTo("Only Telegram group administrators can use this command.")
            assertThat(answered.showAlert).isTrue()
            assertThat(installationMuted(installation.id)).isFalse()
            assertThat(auditActionCount("telegram_mute")).isEqualTo(initialMuteCount)
        }

    @Test
    fun callbackQueryTestAndStatusActionsWorkForAdmin() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(205)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 205, "creator")

            val testUpdate = callbackGroupUpdate(
                updateId = 206,
                callbackId = "cb_test",
                data = "cb:test:${installation.id}",
                chatId = installation.telegramChatId,
                userId = 205,
            )
            assertThat(postTelegram(testUpdate).status).isEqualTo(HttpStatusCode.OK)
            val answeredTest = mockTelegramService().answeredCallbacks().last()
            assertThat(answeredTest.text).isEqualTo("Test notification sent.")
            assertThat(sentMessages().last().text).contains(installation.id.toString())

            val statusUpdate = callbackGroupUpdate(
                updateId = 207,
                callbackId = "cb_status",
                data = "cb:status:${installation.id}",
                chatId = installation.telegramChatId,
                userId = 205,
            )
            assertThat(postTelegram(statusUpdate).status).isEqualTo(HttpStatusCode.OK)
            val answeredStatus = mockTelegramService().answeredCallbacks().last()
            assertThat(answeredStatus.text).contains("Installation: ${installation.id}")
            assertThat(answeredStatus.showAlert).isTrue()
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

private fun privateUpdate(updateId: Long, text: String, userId: Long): String =
    Json.encodeToString(
        TelegramUpdate(
            updateId = updateId,
            message = TelegramMessage(
                text = text,
                chat = TelegramChat(id = userId, type = "private"),
                from = TelegramUser(userId),
            ),
        ),
    )

private fun groupUpdate(
    updateId: Long,
    text: String,
    chatId: Long,
    userId: Long = updateId,
): String =
    Json.encodeToString(
        TelegramUpdate(
            updateId = updateId,
            message = TelegramMessage(
                text = text,
                chat = TelegramChat(id = chatId, type = "group"),
                from = TelegramUser(userId),
            ),
        ),
    )

private fun callbackGroupUpdate(
    updateId: Long,
    callbackId: String,
    data: String?,
    chatId: Long,
    userId: Long,
    messageThreadId: Long? = null,
): String =
    Json.encodeToString(
        TelegramUpdate(
            updateId = updateId,
            callbackQuery = TelegramCallbackQuery(
                id = callbackId,
                from = TelegramUser(userId),
                message = TelegramMessage(
                    chat = TelegramChat(id = chatId, type = "group"),
                    from = TelegramUser(userId),
                    messageThreadId = messageThreadId,
                ),
                data = data,
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
