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
import net.raquezha.nuecagram.telegram.TelegramCallbackData
import net.raquezha.nuecagram.telegram.TelegramUpdate
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
    fun groupManagementCommandsRedirectToPrivateDmWithoutUsageHints() =
        testApplication {
            configureTestApplication()
            val initialAuditCount = auditEventCount()

            listOf(
                "/status",
                "/test",
                "/manage",
                "/rotate",
                "/mute",
                "/unmute",
                "/digest",
            ).forEachIndexed { index, command ->
                assertThat(
                    postTelegram(
                        groupUpdate(
                            10_050 + index.toLong(),
                            "$command ${installation.id}",
                            installation.telegramChatId,
                        ),
                    ).status,
                ).isEqualTo(HttpStatusCode.OK)
                val message = sentMessages().last()
                assertThat(message.text).isEqualTo(
                    "Continue in a private chat with the bot to manage this installation.",
                )
                assertThat(message.replyMarkup!!.inlineKeyboard.first().first().url).isEqualTo(
                    "https://t.me/NuecagramBot",
                )
            }
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
        }

    @Test
    fun groupHelpReturnsShortGuidanceAndUrlButtonWithoutWebappLauncher() =
        testApplication {
            configureTestApplication()

            assertThat(
                postTelegram(groupUpdate(500, "/help", installation.telegramChatId)).status,
            ).isEqualTo(HttpStatusCode.OK)

            val message = sentMessages().last()
            assertThat(message.text).contains("open the GitLab setup wizard")
            assertThat(message.text).doesNotContain("Open Web App")
            assertThat(message.replyMarkup).isNotNull()
            val button = message.replyMarkup!!.inlineKeyboard.first().first()
            assertThat(button.url).isEqualTo("https://t.me/NuecagramBot")
            assertThat(button.webApp).isNull()
        }

    @Test
    fun privateHelpReturnsCategorizedInlineMenuAndHandlesHelpCallbacks() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(501)

            assertThat(
                postTelegram(privateUpdate(501, "/help", userId = 501)).status,
            ).isEqualTo(HttpStatusCode.OK)

            val helpMsg = sentMessages().last()
            assertThat(helpMsg.text).contains("Nuecagram Assistant")
            assertThat(helpMsg.replyMarkup).isNotNull()
            val rows = helpMsg.replyMarkup!!.inlineKeyboard
            assertThat(rows).hasSize(3)
            assertThat(rows[0][0].text).isEqualTo("📦 My Installations")
            assertThat(rows[0][0].callbackData).isEqualTo("inst:list:page=0")
            assertThat(rows[1][0].text).isEqualTo("⚙️ Setup Instructions")
            assertThat(rows[1][0].callbackData).isEqualTo("inst:help_setup:all")
            assertThat(rows[2][0].text).isEqualTo("📖 Command List")
            assertThat(rows[2][0].callbackData).isEqualTo("inst:help_commands:all")

            val setupCallback = callbackPrivateUpdate(
                updateId = 502,
                callbackId = "cb_help_setup",
                data = "inst:help_setup:all",
                userId = 501,
            )
            assertThat(postTelegram(setupCallback).status).isEqualTo(HttpStatusCode.OK)
            val setupMsg = sentMessages().last()
            assertThat(setupMsg.text).contains("First-Time Setup Instructions")

            val commandsCallback = callbackPrivateUpdate(
                updateId = 503,
                callbackId = "cb_help_commands",
                data = "inst:help_commands:all",
                userId = 501,
            )
            assertThat(postTelegram(commandsCallback).status).isEqualTo(HttpStatusCode.OK)
            val commandsMsg = sentMessages().last()
            assertThat(commandsMsg.text).contains("Command Reference")
        }

    @Test
    fun statusSupportsShort8CharInstallationIdPrefixInDm() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(88)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 88, "administrator")

            val shortId = installation.id.toString().take(8)
            assertThat(
                postTelegram(privateUpdate(88, "/status $shortId", userId = 88)).status,
            ).isEqualTo(HttpStatusCode.OK)

            val response = sentMessages().last().text
            assertThat(response).contains("Installation: ${installation.id}")
            assertThat(response).contains("GitLab: ${installation.gitlabBaseUrl}")
        }

    @Test
    fun statusRequiresTelegramAdministratorAndValidInstallationIdInDm() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(52)
            val initialAuditCount = auditEventCount()

            assertThat(
                postTelegram(privateUpdate(52, "/status ${installation.id}", userId = 52)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo(
                "Only Telegram group administrators can use this command.",
            )

            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 52, "administrator")
            assertThat(
                postTelegram(privateUpdate(53, "/status nope", userId = 52)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Installation not found.")
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
                postTelegram(privateUpdate(55, "/status ${installation.id}", userId = 55)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo(
                "Only Telegram group administrators can use this command.",
            )
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
        }

    @Test
    fun statusAllowsTelegramAdministratorsForOwnedInstallationInDm() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(54)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 54, "creator")

            assertThat(
                postTelegram(privateUpdate(54, "/status ${installation.id}", userId = 54)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).contains(installation.id.toString())
            assertThat(sentMessages().last().text).contains("Muted: no")
            assertThat(sentMessages().last().text).contains(installation.gitlabBaseUrl)
        }

    @Test
    fun recordsInstallationAdminMembershipOnSuccessfulDmCommand() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(970)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 970, "administrator")

            assertThat(runBlocking { installationRepository.installationsForAdmin(970) }).isEmpty()

            assertThat(
                postTelegram(privateUpdate(970, "/status ${installation.id}", userId = 970)).status,
            ).isEqualTo(HttpStatusCode.OK)

            val adminInstalls = runBlocking { installationRepository.installationsForAdmin(970) }
            assertThat(adminInstalls).hasSize(1)
            assertThat(adminInstalls.first().id).isEqualTo(installation.id)
        }

    @Test
    fun liveReVerificationBlocksDemotedAdminsWithStaleRecordInDm() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(971)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 971, "administrator")

            assertThat(
                postTelegram(privateUpdate(971, "/status ${installation.id}", userId = 971)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(runBlocking { installationRepository.installationsForAdmin(971) }).hasSize(1)

            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 971, "member")

            assertThat(
                postTelegram(privateUpdate(972, "/rotate ${installation.id}", userId = 971)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo(
                "Only Telegram group administrators can use this command.",
            )

            assertThat(
                postTelegram(privateUpdate(973, "/mute ${installation.id}", userId = 971)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo(
                "Only Telegram group administrators can use this command.",
            )
            assertThat(installationMuted(installation.id)).isFalse()
        }

    @Test
    fun digestReturnsSafeInstallationSummaryInDm() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(60)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 60, "administrator")

            assertThat(
                postTelegram(privateUpdate(60, "/digest ${installation.id}", userId = 60)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).contains("Digest for ${installation.id}")
            assertThat(sentMessages().last().text).contains(installation.gitlabBaseUrl)
            assertThat(sentMessages().last().text).contains("Muted: no")
        }

    @Test
    fun testSendsDeliveryMessageToStoredDestinationAndAuditsOnceFromDm() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(61)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 61, "administrator")

            val initialTestCount = auditActionCount("telegram_delivery_test")

            assertThat(
                postTelegram(privateUpdate(61, "/test ${installation.id}", userId = 61)).status,
            ).isEqualTo(HttpStatusCode.OK)
            val delivered = sentMessages().last()
            assertThat(delivered.chatId).isEqualTo(installation.telegramChatId.toString())
            assertThat(delivered.threadId).isEqualTo(installation.telegramTopicId)
            assertThat(delivered.text).contains(installation.id.toString())
            assertThat(auditActionCount("telegram_delivery_test")).isEqualTo(initialTestCount + 1)

            assertThat(
                postTelegram(privateUpdate(61, "/test ${installation.id}", userId = 61)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages()).hasSize(1)
            assertThat(auditActionCount("telegram_delivery_test")).isEqualTo(initialTestCount + 1)
        }

    @Test
    fun muteAndUnmutePersistAndAuditSuccessfulCommandsFromDm() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(62)
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 62, "administrator")
            val initialMuteCount = auditActionCount("telegram_mute")
            val initialUnmuteCount = auditActionCount("telegram_unmute")

            assertThat(
                postTelegram(privateUpdate(62, "/mute ${installation.id}", userId = 62)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Installation muted.")
            assertThat(installationMuted(installation.id)).isTrue()
            assertThat(auditActionCount("telegram_mute")).isEqualTo(initialMuteCount + 1)

            assertThat(
                postTelegram(privateUpdate(63, "/unmute ${installation.id}", userId = 62)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("Installation unmuted.")
            assertThat(installationMuted(installation.id)).isFalse()
            assertThat(auditActionCount("telegram_unmute")).isEqualTo(initialUnmuteCount + 1)
        }

    @Test
    fun unauthorizedMuteIsNoOpAndWritesNoAuditInDm() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(64)
            val initialAuditCount = auditEventCount()
            val initialMuteCount = auditActionCount("telegram_mute")

            assertThat(
                postTelegram(privateUpdate(64, "/mute ${installation.id}", userId = 64)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo(
                "Only Telegram group administrators can use this command.",
            )
            assertThat(installationMuted(installation.id)).isFalse()
            assertThat(auditEventCount()).isEqualTo(initialAuditCount)
            assertThat(auditActionCount("telegram_mute")).isEqualTo(initialMuteCount)
        }

    @Test
    fun telegramCallbackDataRegexParserEdgeCases() {
        // Null and blank
        assertThat(TelegramCallbackData.parse(null)).isNull()
        assertThat(TelegramCallbackData.parse("")).isNull()
        assertThat(TelegramCallbackData.parse("   ")).isNull()

        // Invalid prefixes
        assertThat(TelegramCallbackData.parse("invalid:mute:123")).isNull()
        assertThat(TelegramCallbackData.parse(":mute:123")).isNull()

        // Malformed segment counts
        assertThat(TelegramCallbackData.parse("cb:mute")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mute:123:extra")).isNull()
        assertThat(TelegramCallbackData.parse("cb::123")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mute:")).isNull()

        // Malformed characters
        assertThat(TelegramCallbackData.parse("cb:mu te:123")).isNull()
        assertThat(TelegramCallbackData.parse("cb:mute:123<script>")).isNull()

        // Valid formats
        val parsedCb = TelegramCallbackData.parse("cb:mute:a1b2c3d4")
        assertThat(parsedCb).isNotNull()
        assertThat(parsedCb!!.action).isEqualTo("mute")
        assertThat(parsedCb.targetId).isEqualTo("a1b2c3d4")

        val parsedInst = TelegramCallbackData.parse("  inst:unmute:550e8400-e29b-41d4-a716-446655440000  ")
        assertThat(parsedInst).isNotNull()
        assertThat(parsedInst!!.action).isEqualTo("unmute")
        assertThat(parsedInst.targetId).isEqualTo("550e8400-e29b-41d4-a716-446655440000")
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

    @Test
    fun dmBareManageDisplaysInteractiveInstallationMenuAndPaginatesOver8Items() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(300)

            // Grant admin on existing installation + create 9 more
            runBlocking {
                installationRepository.recordInstallationAdmin(installation.id, 300)
                for (i in 1..9) {
                    val inst = installationRepository.createInstallation(
                        gitlabBaseUrl = "https://gitlab.com",
                        gitlabProjectId = 100L + i,
                        telegramChatId = 1000L + i,
                        telegramTopicId = null,
                    )
                    installationRepository.recordInstallationAdmin(inst.id, 300)
                }
            }

            // Bare /manage in DM
            assertThat(postTelegram(privateUpdate(301, "/manage", userId = 300)).status).isEqualTo(HttpStatusCode.OK)
            val firstMsg = sentMessages().last()
            assertThat(firstMsg.text).contains("Select an installation to manage:")
            assertThat(firstMsg.replyMarkup).isNotNull()
            val keyboard = firstMsg.replyMarkup!!.inlineKeyboard
            assertThat(keyboard).hasSize(9) // 8 items + 1 nav row
            val navRow = keyboard.last()
            assertThat(navRow.last().callbackData).isEqualTo("inst:list:page=1")

            // Page 1 via callback
            val page1Update = callbackPrivateUpdate(
                updateId = 302,
                callbackId = "cb_page1",
                data = "inst:list:page=1",
                userId = 300,
                messageId = 5001,
            )
            assertThat(postTelegram(page1Update).status).isEqualTo(HttpStatusCode.OK)
            val page1Msg = sentMessages().last()
            assertThat(page1Msg.messageId).isEqualTo("5001")
            assertThat(page1Msg.replyMarkup).isNotNull()
            val page1Keyboard = page1Msg.replyMarkup!!.inlineKeyboard
            assertThat(page1Keyboard).hasSize(3) // 2 items + 1 nav row
        }

    @Test
    fun dmCallbackQueryMenuAndActionNavigationUpdatesMessageInPlace() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(310)
            runBlocking {
                installationRepository.recordInstallationAdmin(installation.id, 310)
            }

            // Open menu in place
            val menuUpdate = callbackPrivateUpdate(
                updateId = 311,
                callbackId = "cb_menu",
                data = "inst:menu:${installation.id}",
                userId = 310,
                messageId = 6001,
            )
            assertThat(postTelegram(menuUpdate).status).isEqualTo(HttpStatusCode.OK)
            val menuMsg = sentMessages().last()
            assertThat(menuMsg.messageId).isEqualTo("6001")
            assertThat(menuMsg.text).contains("Installation:")
            assertThat(menuMsg.text).contains(installation.id.toString())
            assertThat(menuMsg.text).contains("Active")

            // Mute in place
            val muteUpdate = callbackPrivateUpdate(
                updateId = 312,
                callbackId = "cb_mute",
                data = "inst:mute:${installation.id}",
                userId = 310,
                messageId = 6001,
            )
            assertThat(postTelegram(muteUpdate).status).isEqualTo(HttpStatusCode.OK)
            assertThat(installationMuted(installation.id)).isTrue()
            val mutedMsg = sentMessages().last()
            assertThat(mutedMsg.messageId).isEqualTo("6001")
            assertThat(mutedMsg.text).contains("Muted")
            val muteButton = mutedMsg.replyMarkup!!.inlineKeyboard[1][0]
            assertThat(muteButton.text).isEqualTo("Unmute")
            assertThat(muteButton.callbackData).isEqualTo("inst:unmute:${installation.id}")
        }

    @Test
    fun dmCallbackQueryRotationRequiresConfirmationBeforeExecuting() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(320)
            runBlocking {
                installationRepository.recordInstallationAdmin(installation.id, 320)
            }
            val initialRotateCount = auditActionCount("telegram_rotate")

            // Step 1: Confirmation prompt
            val confirmUpdate = callbackPrivateUpdate(
                updateId = 321,
                callbackId = "cb_confirm",
                data = "inst:rotate:confirm:${installation.id}",
                userId = 320,
                messageId = 7001,
            )
            assertThat(postTelegram(confirmUpdate).status).isEqualTo(HttpStatusCode.OK)
            val confirmMsg = sentMessages().last()
            assertThat(confirmMsg.messageId).isEqualTo("7001")
            assertThat(confirmMsg.text).contains("Are you sure you want to rotate")
            val executeButton = confirmMsg.replyMarkup!!.inlineKeyboard.first().first()
            assertThat(executeButton.callbackData).isEqualTo("inst:rotate:execute:${installation.id}")
            assertThat(auditActionCount("telegram_rotate")).isEqualTo(initialRotateCount)

            // Step 2: Execute rotation
            val executeUpdate = callbackPrivateUpdate(
                updateId = 322,
                callbackId = "cb_execute",
                data = "inst:rotate:execute:${installation.id}",
                userId = 320,
                messageId = 7001,
            )
            assertThat(postTelegram(executeUpdate).status).isEqualTo(HttpStatusCode.OK)
            assertThat(auditActionCount("telegram_rotate")).isEqualTo(initialRotateCount + 1)
            val executeMsg = sentMessages().last()
            assertThat(executeMsg.messageId).isEqualTo("7001")
            assertThat(executeMsg.text).contains("Rotated installation:")
            assertThat(executeMsg.text).contains("GitLab secret token:")
        }

    @Test
    fun dmCallbackQueryRejectsUnauthorizedInstallationId() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(330)
            // User 330 is NOT an admin of installation

            val menuUpdate = callbackPrivateUpdate(
                updateId = 331,
                callbackId = "cb_unauth_menu",
                data = "inst:menu:${installation.id}",
                userId = 330,
            )
            assertThat(postTelegram(menuUpdate).status).isEqualTo(HttpStatusCode.OK)
            val answered = mockTelegramService().answeredCallbacks().last()
            assertThat(answered.callbackQueryId).isEqualTo("cb_unauth_menu")
            assertThat(answered.text).isEqualTo("Installation not found.")
            assertThat(answered.showAlert).isTrue()
        }

    @Test
    fun dmBareManageHandlesEmptyInstallationsAndOutOfBoundsPage() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(340)

            // Bare /manage with no installations
            assertThat(postTelegram(privateUpdate(341, "/manage", userId = 340)).status).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().last().text).isEqualTo("No installations found for your account.")

            // Grant admin on installation
            runBlocking {
                installationRepository.recordInstallationAdmin(installation.id, 340)
            }

            // Callback page 999 clamps to valid page 0
            val page999Update = callbackPrivateUpdate(
                updateId = 342,
                callbackId = "cb_page999",
                data = "inst:list:page=999",
                userId = 340,
            )
            assertThat(postTelegram(page999Update).status).isEqualTo(HttpStatusCode.OK)
            val pageMsg = sentMessages().last()
            assertThat(pageMsg.text).contains("Select an installation to manage:")
        }

    @Test
    fun dmTypedManageSupportsShortIdFallback() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateUser(350)
            runBlocking {
                installationRepository.recordInstallationAdmin(installation.id, 350)
            }
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, 350, "administrator")

            val shortId = installation.id.toString().take(8)
            assertThat(
                postTelegram(privateUpdate(351, "/manage $shortId", userId = 350)).status,
            ).isEqualTo(HttpStatusCode.OK)
            assertThat(
                sentMessages().any {
                    it.text.contains("Management for ${installation.id}")
                },
            ).isTrue()
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
            message = TelegramUpdate.Message(
                text = text,
                chat = TelegramUpdate.Chat(id = userId, type = "private"),
                from = TelegramUpdate.User(userId),
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
            message = TelegramUpdate.Message(
                text = text,
                chat = TelegramUpdate.Chat(id = chatId, type = "group"),
                from = TelegramUpdate.User(userId),
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
            callbackQuery = TelegramUpdate.CallbackQuery(
                id = callbackId,
                from = TelegramUpdate.User(userId),
                message = TelegramUpdate.Message(
                    chat = TelegramUpdate.Chat(id = chatId, type = "group"),
                    from = TelegramUpdate.User(userId),
                    messageThreadId = messageThreadId,
                ),
                data = data,
            ),
        ),
    )

private fun callbackPrivateUpdate(
    updateId: Long,
    callbackId: String,
    data: String?,
    userId: Long,
    messageId: Long = 1001,
): String =
    Json.encodeToString(
        TelegramUpdate(
            updateId = updateId,
            callbackQuery = TelegramUpdate.CallbackQuery(
                id = callbackId,
                from = TelegramUpdate.User(userId),
                message = TelegramUpdate.Message(
                    messageId = messageId,
                    chat = TelegramUpdate.Chat(id = userId, type = "private"),
                    from = TelegramUpdate.User(userId),
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
