package net.raquezha.nuecagram.telegram

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
import net.raquezha.nuecagram.BaseEventTestHelper
import org.junit.Test

class TelegramDeterministicInlineKeyboardTest : BaseEventTestHelper() {

    @Test
    fun reposAndAliasesWorkInDmWithoutArgumentsAndUseHumanReadableLabels() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateAdmin(901)

            listOf("/repos", "/repositories", "/projects").forEach { cmd ->
                assertThat(
                    postTelegram(privateUpdate(901, cmd, userId = 901)).status,
                ).isEqualTo(HttpStatusCode.OK)

                val msg = sentMessages().last()
                assertThat(msg.text).contains("Select an installation to manage:")
                val buttons = msg.replyMarkup!!.inlineKeyboard.flatten()
                assertThat(buttons).isNotEmpty()

                val repoButton = buttons.first()
                assertThat(repoButton.callbackData).isEqualTo("inst:menu:${installation.id}")
                // Ensure human-readable identity without raw UUID prefix like "(a1b2c3d4)"
                val expectedLabel = runBlocking {
                    installationRepository.installationAdminContext(installation.id)!!.repositoryButtonLabel()
                }
                assertThat(repoButton.text).contains(expectedLabel)
                assertThat(repoButton.text).doesNotContain("(${installation.id.toString().take(8)})")
            }
        }

    @Test
    fun dmSlashCommandsWorkWithoutInstallationIdArguments() =
        testApplication {
            configureTestApplication()
            bootstrapPrivateAdmin(902)

            // /status without args
            assertThat(
                postTelegram(privateUpdate(902, "/status", userId = 902)).status,
            ).isEqualTo(HttpStatusCode.OK)
            val statusMsg = sentMessages().last()
            assertThat(statusMsg.text).contains("Installation Status")

            // /rotate without args
            assertThat(
                postTelegram(privateUpdate(903, "/rotate", userId = 902)).status,
            ).isEqualTo(HttpStatusCode.OK)
            val rotateMsg = sentMessages().last()
            assertThat(rotateMsg.text).contains("Rotate Webhook Secret")
            val rotateCancelBtn = rotateMsg.replyMarkup!!.inlineKeyboard.flatten().last()
            assertThat(rotateCancelBtn.text).isEqualTo("« Cancel")

            // /mute without args
            assertThat(
                postTelegram(privateUpdate(904, "/mute", userId = 902)).status,
            ).isEqualTo(HttpStatusCode.OK)
            val muteMsg = sentMessages().last()
            assertThat(muteMsg.text).contains("Installation muted.")

            // /unmute without args
            assertThat(
                postTelegram(privateUpdate(905, "/unmute", userId = 902)).status,
            ).isEqualTo(HttpStatusCode.OK)
            val unmuteMsg = sentMessages().last()
            assertThat(unmuteMsg.text).contains("Installation unmuted.")

            // /test without args
            assertThat(
                postTelegram(privateUpdate(906, "/test", userId = 902)).status,
            ).isEqualTo(HttpStatusCode.OK)
            val testMsg = sentMessages().last()
            assertThat(testMsg.text).contains("Test notification sent")

            // /digest without args
            assertThat(
                postTelegram(privateUpdate(907, "/digest", userId = 902)).status,
            ).isEqualTo(HttpStatusCode.OK)
            val digestMsg = sentMessages().last()
            assertThat(digestMsg.text).contains("Weekly Digest")
        }

    @Test
    fun helpSubScreensUseMainBackTargetAvoidingNoInstallationsFoundPopup() =
        testApplication {
            configureTestApplication()
            // User with NO installations
            val userId = 999L
            bootstrapPrivateUser(userId)

            // 1. Open /help
            assertThat(
                postTelegram(privateUpdate(1001, "/help", userId = userId)).status,
            ).isEqualTo(HttpStatusCode.OK)
            val helpMsg = sentMessages().last()
            assertThat(helpMsg.text).contains("Nuecagram Assistant")

            // 2. Click Setup Instructions
            val setupCb = callbackPrivateUpdate(1002, "cb_setup", "inst:help_setup:all", userId)
            assertThat(postTelegram(setupCb).status).isEqualTo(HttpStatusCode.OK)
            val setupMsg = sentMessages().last()
            assertThat(setupMsg.text).contains("First-Time Setup Instructions")
            val setupBackBtn = setupMsg.replyMarkup!!.inlineKeyboard.single().single()
            assertThat(setupBackBtn.text).isEqualTo("« Back")
            assertThat(setupBackBtn.callbackData).isEqualTo("inst:help_menu:all")

            // 3. Click « Main Menu back button
            val menuBackCb = callbackPrivateUpdate(1003, "cb_back_help", "inst:help_menu:all", userId)
            assertThat(postTelegram(menuBackCb).status).isEqualTo(HttpStatusCode.OK)

            // Verify answered callback had no alert error popup ("No installations found")
            val answeredCallbacks = mockTelegramService().answeredCallbacks()
            val lastAnswered = answeredCallbacks.last()
            assertThat(lastAnswered.showAlert).isFalse()
            assertThat(lastAnswered.text).isNull()

            // Verify main help menu returned
            val returnedHelpMsg = sentMessages().last()
            assertThat(returnedHelpMsg.text).contains("Nuecagram Assistant")
        }

    @Test
    fun demotedGroupAdminIsBlockedFromDmCommandsAndCallbacks() =
        testApplication {
            configureTestApplication()
            val userId = 908L
            bootstrapPrivateAdmin(userId)

            // Mock telegramService chatMemberStatus to return "member" instead of "administrator" / "creator"
            mockTelegramService().setChatMemberStatus(installation.telegramChatId, userId, "member")

            // 1. Try /repos in DM
            assertThat(
                postTelegram(privateUpdate(1201, "/repos", userId = userId)).status,
            ).isEqualTo(HttpStatusCode.OK)
            val reposMsg = sentMessages().last()
            assertThat(reposMsg.text).contains("No installations found for your account.")

            // 2. Try callback query on installation target
            val cb = callbackPrivateUpdate(1202, "cb_demoted", "inst:menu:${installation.id}", userId)
            assertThat(postTelegram(cb).status).isEqualTo(HttpStatusCode.OK)
            val lastAnswered = mockTelegramService().answeredCallbacks().last()
            assertThat(lastAnswered.showAlert).isTrue()
            assertThat(lastAnswered.text).isEqualTo("Only Telegram group administrators can use this command.")
        }

    @Test
    fun handlePrivateManageSupportsArgFreeAndQueryModesWithAdminAuth() =
        testApplication {
            configureTestApplication()
            val userId = 909L
            bootstrapPrivateAdmin(userId)

            // /manage without args
            assertThat(
                postTelegram(privateUpdate(1301, "/manage", userId = userId)).status,
            ).isEqualTo(HttpStatusCode.OK)
            val listMsg = sentMessages().last()
            assertThat(listMsg.text).contains("Select an installation to manage:")

            // /manage with query arg
            assertThat(
                postTelegram(privateUpdate(1302, "/manage ${installation.id}", userId = userId)).status,
            ).isEqualTo(HttpStatusCode.OK)
            val expectedLabel = runBlocking {
                installationRepository.installationAdminContext(installation.id)!!.repositoryButtonLabel()
            }
            val linkMsg = sentMessages().first { it.text.contains("Repository:") }
            assertThat(linkMsg.text).contains(expectedLabel)
        }

    @Test
    fun unauthorizedUserCannotHijackCallbackForAnotherInstallation() =
        testApplication {
            configureTestApplication()
            val attackerUserId = 666L
            bootstrapPrivateUser(attackerUserId)

            // Attacker attempts to send callback for installation they don't administer
            val tamperedCb = callbackPrivateUpdate(
                updateId = 1401,
                callbackId = "cb_hijack",
                data = "inst:rotate:execute:${installation.id}",
                userId = attackerUserId,
            )

            assertThat(postTelegram(tamperedCb).status).isEqualTo(HttpStatusCode.OK)

            val answered = mockTelegramService().answeredCallbacks().last()
            assertThat(answered.showAlert).isTrue()
            assertThat(answered.text).contains("Only Telegram group administrators")
        }

    @Test
    fun replayAttacksAreSuppressedByUpdateIdDeduplication() =
        testApplication {
            configureTestApplication()
            val userId = 911L
            bootstrapPrivateAdmin(userId)

            val updateJson = privateUpdate(updateId = 9999, text = "/repos", userId = userId)

            // First processing succeeds
            assertThat(postTelegram(updateJson).status).isEqualTo(HttpStatusCode.OK)
            val initialSentCount = sentMessages().size

            // Second processing (replay attack) is ignored due to recordTelegramUpdate deduplication
            assertThat(postTelegram(updateJson).status).isEqualTo(HttpStatusCode.OK)
            assertThat(sentMessages().size).isEqualTo(initialSentCount)
        }

    @Test
    fun repositoryButtonsIncludeGroupNameWhenPresent() =
        testApplication {
            configureTestApplication()
            val userId = 905L
            bootstrapPrivateUser(userId)

            val customInst = runBlocking {
                val inst = installationRepository.createInstallation(
                    gitlabBaseUrl = "https://gitlab.example.com/team/repo",
                    gitlabProjectId = 8888L,
                    telegramChatId = -8888L,
                    telegramTopicId = null,
                    repoName = "nuecagram-core",
                    chatName = "Engineering Team",
                )
                installationRepository.recordInstallationAdmin(inst.id, userId)
                inst
            }
            mockTelegramService().setChatMemberStatus(-8888L, userId, "administrator")

            assertThat(
                postTelegram(privateUpdate(910, "/repos", userId = userId)).status,
            ).isEqualTo(HttpStatusCode.OK)

            val msg = sentMessages().last()
            val repoButton = msg.replyMarkup!!.inlineKeyboard.flatten().first()
            assertThat(repoButton.text).isEqualTo("nuecagram-core | Engineering Team")
        }

    private fun bootstrapPrivateUser(userId: Long) {
        runBlocking {
            installationRepository.upsertTelegramPrivateChat(userId, userId)
        }
    }

    private fun bootstrapPrivateAdmin(userId: Long) {
        bootstrapPrivateUser(userId)
        runBlocking {
            installationRepository.recordInstallationAdmin(installation.id, userId)
        }
        mockTelegramService().setChatMemberStatus(installation.telegramChatId, userId, "administrator")
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

    private fun callbackPrivateUpdate(
        updateId: Long,
        callbackId: String,
        data: String,
        userId: Long,
    ): String =
        Json.encodeToString(
            TelegramUpdate(
                updateId = updateId,
                callbackQuery = TelegramUpdate.CallbackQuery(
                    id = callbackId,
                    from = TelegramUpdate.User(userId),
                    message = TelegramUpdate.Message(
                        text = "previous message",
                        chat = TelegramUpdate.Chat(id = userId, type = "private"),
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
}
