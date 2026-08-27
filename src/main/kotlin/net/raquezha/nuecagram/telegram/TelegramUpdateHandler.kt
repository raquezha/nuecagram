@file:Suppress("TooManyFunctions")

package net.raquezha.nuecagram.telegram

import java.time.Instant
import java.time.temporal.ChronoUnit
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.configuredPublicUrl
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRepository

private const val PRIVATE_BOOTSTRAP_MESSAGE = "Use /start in a private chat before using admin commands."
private const val GROUP_HELP_MESSAGE =
    "<b>Nuecagram GitLab Notification Gateway</b>\n\n" +
        "Run <code>/setup</code> in this group or topic to open the GitLab setup wizard.\n" +
        "For status, management, and configuration options, open a private chat with the bot."
private const val WRONG_CHAT_MESSAGE = "Installation not found in this chat."
private const val PRIVATE_COMMAND_MESSAGE = "Run this command in the installation group."
private const val MANAGEMENT_DM_REDIRECT_MESSAGE =
    "Continue in a private chat with the bot to manage this installation."
private const val MANAGEMENT_DM_URL = "https://t.me/NuecagramBot"
private const val LAUNCH_NONCE_TTL_MINUTES = 10L

private data class AuthorizedGroupAdmin(
    val actorId: Long,
)

private data class AuthorizedInstallationCommand(
    val installation: InstallationAdminContext,
    val actorId: Long,
    val privateChatId: Long,
)

@Suppress("TooManyFunctions")
class TelegramUpdateHandler(
    private val installationRepository: InstallationRepository,
    private val telegramService: TelegramService,
    private val config: ConfigWithSecrets,
) {
    private val menuHandler = TelegramMenuHandler(telegramService, installationRepository, config)
    suspend fun handle(update: TelegramUpdate) {
        if (!installationRepository.recordTelegramUpdate(update.updateId)) return

        val callbackQuery = update.callbackQuery
        if (callbackQuery != null) {
            handleCallbackQuery(callbackQuery)
            return
        }

        val message = update.message ?: return
        val command = message.text?.substringBefore(' ')?.substringBefore('@') ?: return
        dispatch(command, message)
    }

    private suspend fun handleCallbackQuery(callbackQuery: TelegramUpdate.CallbackQuery) {
        val message = callbackQuery.message
        val payload = callbackQuery.data?.let { TelegramCallbackData.parse(it) }
        if (message == null || payload == null) {
            answerCallbackError(callbackQuery.id, "Invalid or expired callback action.")
            return
        }

        val userId = callbackQuery.from.id
        if (message.chat.type == "private") {
            menuHandler.handlePrivateCallbackQuery(callbackQuery, message, payload, userId)
        } else {
            handleGroupCallbackQuery(callbackQuery, message, payload, userId)
        }
    }

    private suspend fun handleGroupCallbackQuery(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        payload: TelegramCallbackPayload,
        userId: Long,
    ) {
        val status = runCatching { telegramService.chatMemberStatus(message.chat.id, userId) }.getOrNull()
        if (!isTelegramAdmin(status)) {
            telegramService.answerCallbackQuery(
                callbackQueryId = callbackQuery.id,
                text = TELEGRAM_ADMIN_ONLY_MESSAGE,
                showAlert = true,
            )
            return
        }

        val installation = installationRepository.findInstallationByQuery(
            payload.targetId,
            message.chat.id,
            message.messageThreadId,
        ) ?: run {
            answerCallbackError(callbackQuery.id, WRONG_CHAT_MESSAGE, showAlert = true)
            return
        }

        installationRepository.recordInstallationAdmin(installation.id, userId)
        executeCallbackAction(callbackQuery.id, userId, installation, payload.action)
    }

    private suspend fun answerCallbackError(
        callbackId: String,
        text: String,
        showAlert: Boolean = false,
    ) {
        telegramService.answerCallbackQuery(callbackId, text, showAlert)
    }

    private suspend fun executeCallbackAction(
        callbackId: String,
        userId: Long,
        installation: InstallationAdminContext,
        action: String,
    ) = when (action) {
        "mute" ->
            handleCallbackMute(
                callbackId = callbackId,
                userId = userId,
                installation = installation,
                muted = true,
                text = "Installation muted.",
                auditAction = "telegram_mute",
            )
        "unmute" ->
            handleCallbackMute(
                callbackId = callbackId,
                userId = userId,
                installation = installation,
                muted = false,
                text = "Installation unmuted.",
                auditAction = "telegram_unmute",
            )
        "test" -> handleCallbackTest(callbackId, userId, installation)
        "status" -> telegramService.answerCallbackQuery(callbackId, installation.statusText(), showAlert = true)
        else -> telegramService.answerCallbackQuery(callbackId, "Unknown callback action.")
    }

    private suspend fun handleCallbackMute(
        callbackId: String,
        userId: Long,
        installation: InstallationAdminContext,
        muted: Boolean,
        text: String,
        auditAction: String,
    ): Boolean {
        installationRepository.setMuted(installation.id, muted)
        installationRepository.writeAuditEvent(
            installationId = installation.id,
            actorType = "telegram",
            actorId = userId.toString(),
            action = auditAction,
        )
        return telegramService.answerCallbackQuery(callbackId, text)
    }

    private suspend fun handleCallbackTest(
        callbackId: String,
        userId: Long,
        installation: InstallationAdminContext,
    ): Boolean {
        telegramService.sendMessage(
            Message(
                chatId = installation.telegramChatId.toString(),
                text = "Nuecagram notification test for ${installation.repositoryButtonLabel()}.",
                threadId = installation.telegramTopicId,
            ),
        )
        installationRepository.writeAuditEvent(
            installationId = installation.id,
            actorType = "telegram",
            actorId = userId.toString(),
            action = "telegram_delivery_test",
        )
        return telegramService.answerCallbackQuery(callbackId, "Test notification sent.")
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun dispatch(command: String, message: TelegramUpdate.Message) {
        when (command) {
            "/start" -> handleStart(message)
            "/hello" -> send(message.chat.id, "Hello. Use /help for available commands.", message.messageThreadId)
            "/help" -> handleHelp(message)
            "/repos", "/repositories", "/projects" -> handleRepos(message)
            "/status" -> handleStatus(message)
            "/digest" -> handleDigest(message)
            "/test" -> handleDeliveryTest(message)
            "/mute" -> handleMute(message)
            "/unmute" -> handleUnmute(message)
            "/setup" -> handleSetup(message)
            "/manage" -> handleManage(message)
            "/webapp" -> handleWebApp(message)
            "/rotate" -> handleRotate(message)
            else ->
                if (command.startsWith("/")) {
                    send(
                        message.chat.id,
                        "Unknown command. Send <code>/help</code> for available commands.",
                        message.messageThreadId,
                    )
                }
        }
    }

    private suspend fun handleHelp(message: TelegramUpdate.Message) {
        if (message.chat.type == "private") {
            menuHandler.sendPrivateHelpMenu(message)
        } else {
            val markup = InlineKeyboardMarkup(
                inlineKeyboard = listOf(
                    listOf(InlineKeyboardButton(text = "Open Private Chat", url = MANAGEMENT_DM_URL)),
                ),
            )
            send(message.chat.id, GROUP_HELP_MESSAGE, message.messageThreadId, replyMarkup = markup)
        }
    }

    private suspend fun handleStart(message: TelegramUpdate.Message) {
        val userId = message.from?.id
        if (message.chat.type == "private" && userId != null) {
            installationRepository.upsertTelegramPrivateChat(userId, message.chat.id)
            sendLauncherMessage(
                message.chat.id,
                null,
                userId,
                "Private onboarding is ready. Return to your group to continue.",
                "Open Management Dashboard",
            )
            return
        } else {
            send(message.chat.id, "Start a private chat with the bot first.", message.messageThreadId)
        }
    }

    private suspend fun handleRepos(message: TelegramUpdate.Message) {
        val userId = message.from?.id ?: return
        if (message.chat.type == "private") {
            val query = parseInstallationQuery(message.text)
            menuHandler.handlePrivateRepos(message, userId, query)
        } else {
            sendManagementDmRedirect(message)
        }
    }

    private suspend fun handleStatus(message: TelegramUpdate.Message) {
        val userId = message.from?.id ?: return
        if (message.chat.type == "private") {
            val query = parseInstallationQuery(message.text)
            menuHandler.handlePrivateStatus(message, userId, query)
        } else {
            sendManagementDmRedirect(message)
        }
    }

    private suspend fun handleDigest(message: TelegramUpdate.Message) {
        val userId = message.from?.id ?: return
        if (message.chat.type == "private") {
            val query = parseInstallationQuery(message.text)
            menuHandler.handlePrivateDigestCommand(message, userId, query)
        } else {
            sendManagementDmRedirect(message)
        }
    }

    private suspend fun handleDeliveryTest(message: TelegramUpdate.Message) {
        val userId = message.from?.id ?: return
        if (message.chat.type == "private") {
            val query = parseInstallationQuery(message.text)
            menuHandler.handlePrivateTestCommand(message, userId, query)
        } else {
            sendManagementDmRedirect(message)
        }
    }

    private suspend fun handleMute(message: TelegramUpdate.Message) {
        val userId = message.from?.id ?: return
        if (message.chat.type == "private") {
            val query = parseInstallationQuery(message.text)
            menuHandler.handlePrivateMuteCommand(message, userId, query)
        } else {
            sendManagementDmRedirect(message)
        }
    }

    private suspend fun handleUnmute(message: TelegramUpdate.Message) {
        val userId = message.from?.id ?: return
        if (message.chat.type == "private") {
            val query = parseInstallationQuery(message.text)
            menuHandler.handlePrivateUnmuteCommand(message, userId, query)
        } else {
            sendManagementDmRedirect(message)
        }
    }

    private suspend fun handleManage(message: TelegramUpdate.Message) {
        val userId = message.from?.id ?: return
        if (message.chat.type == "private") {
            val query = parseInstallationQuery(message.text)
            menuHandler.handlePrivateRepos(message, userId, query)
        } else {
            sendManagementDmRedirect(message)
        }
    }

    private suspend fun handleRotate(message: TelegramUpdate.Message) {
        val userId = message.from?.id ?: return
        if (message.chat.type == "private") {
            val query = parseInstallationQuery(message.text)
            menuHandler.handlePrivateRotate(message, userId, query)
        } else {
            sendManagementDmRedirect(message)
        }
    }

    private fun parseInstallationQuery(text: String?): String? =
        text
            ?.substringAfter(' ', "")
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private suspend fun handleSetup(message: TelegramUpdate.Message) {
        val authorized = authorizeGroupAdmin(message, requireArguments = false) ?: return
        installationRepository.writeAuditEvent(
            installationId = null,
            actorType = "telegram",
            actorId = authorized.actorId.toString(),
            action = "telegram_webapp_launch",
        )
        sendLauncherMessage(
            message.chat.id,
            message.messageThreadId,
            authorized.actorId,
            "Open Nuecagram Web App to set up GitLab notifications:",
            "Set Up GitLab Notifications",
        )
    }



    private suspend fun handleWebApp(message: TelegramUpdate.Message) {
        val userId = message.from?.id ?: return
        if (message.chat.type == "private") {
            send(message.chat.id, PRIVATE_COMMAND_MESSAGE)
            return
        }
        val status = runCatching { telegramService.chatMemberStatus(message.chat.id, userId) }.getOrNull()
        if (!isTelegramAdmin(status)) {
            send(message.chat.id, TELEGRAM_ADMIN_ONLY_MESSAGE, message.messageThreadId)
            return
        }
        installationRepository.writeAuditEvent(
            installationId = null,
            actorType = "telegram",
            actorId = userId.toString(),
            action = "telegram_webapp_launch",
        )
        sendLauncherMessage(
            message.chat.id,
            message.messageThreadId,
            userId,
            "Open Nuecagram Web App:",
            "Open Nuecagram Web App",
        )
    }



    private suspend fun authorizeGroupAdmin(
        message: TelegramUpdate.Message,
        requireArguments: Boolean = true,
    ): AuthorizedGroupAdmin? {
        val text = message.text?.trim().orEmpty()
        val userId = message.from?.id
        val privateChatId = if (userId != null) installationRepository.telegramPrivateChatId(userId) else null
        val status = if (userId != null) {
            runCatching { telegramService.chatMemberStatus(message.chat.id, userId) }.getOrNull()
        } else {
            null
        }

        val result = when {
            message.chat.type == "private" -> {
                send(message.chat.id, PRIVATE_COMMAND_MESSAGE)
                null
            }
            !isTelegramAdmin(status) -> {
                send(message.chat.id, TELEGRAM_ADMIN_ONLY_MESSAGE, message.messageThreadId)
                null
            }
            requireArguments && text.substringAfter(' ', "").isBlank() -> null
            userId == null || privateChatId == null -> {
                send(message.chat.id, PRIVATE_BOOTSTRAP_MESSAGE, message.messageThreadId)
                null
            }
            else -> AuthorizedGroupAdmin(userId)
        }

        return result
    }

    private suspend fun sendManagementDmRedirect(message: TelegramUpdate.Message) {
        val markup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(listOf(InlineKeyboardButton(text = "Open bot DM", url = MANAGEMENT_DM_URL))),
        )
        send(message.chat.id, MANAGEMENT_DM_REDIRECT_MESSAGE, message.messageThreadId, replyMarkup = markup)
    }

    private suspend fun sendLauncherMessage(
        chatId: Long,
        threadId: Long?,
        userId: Long,
        text: String,
        buttonText: String = "Open Nuecagram Web App",
    ) {
        val nonce = installationRepository.issueLaunchNonce(
            telegramChatId = chatId,
            telegramTopicId = threadId,
            telegramUserId = userId,
            expiresAt = Instant.now().plus(LAUNCH_NONCE_TTL_MINUTES, ChronoUnit.MINUTES),
        )
        val url = "${config.publicBaseUrl()}/webapp?startapp=nonce_${nonce.raw}"
        val webAppMarkup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(listOf(InlineKeyboardButton(text = buttonText, webApp = WebAppInfo(url = url)))),
        )
        val directUrlMarkup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(listOf(InlineKeyboardButton(text = buttonText, url = url))),
        )
        val cid = chatId.toString()
        val textWithLink = "$text\n\n<a href=\"$url\">$buttonText</a>"

        val attempts = listOfNotNull(
            Message(chatId = cid, text = text, threadId = threadId, replyMarkup = webAppMarkup),
            Message(chatId = cid, text = text, threadId = threadId, replyMarkup = directUrlMarkup),
            threadId?.let { Message(chatId = cid, text = text, threadId = null, replyMarkup = webAppMarkup) },
            threadId?.let { Message(chatId = cid, text = text, threadId = null, replyMarkup = directUrlMarkup) },
            Message(chatId = cid, text = textWithLink, threadId = threadId),
            threadId?.let { Message(chatId = cid, text = textWithLink, threadId = null) },
        )

        attempts.firstNotNullOfOrNull { msg ->
            runCatching { telegramService.sendMessage(msg) }.getOrNull()
        }
    }

    private fun buildSendAttempts(
        chatId: Long,
        text: String,
        threadId: Long?,
        replyMarkup: InlineKeyboardMarkup?,
        messageId: String? = null,
    ): List<Message> {
        val cid = chatId.toString()
        val withMarkup = listOfNotNull(
            Message(chatId = cid, messageId = messageId, text = text, threadId = threadId, replyMarkup = replyMarkup),
            Message(
                chatId = cid,
                messageId = messageId,
                text = text,
                threadId = threadId,
                parseMode = "",
                replyMarkup = replyMarkup,
            ),
            threadId?.let {
                Message(
                    chatId = cid,
                    messageId = messageId,
                    text = text,
                    threadId = null,
                    parseMode = "",
                    replyMarkup = replyMarkup,
                )
            },
        )
        val fallbackWithoutMarkup = if (replyMarkup != null && messageId == null) {
            listOfNotNull(
                Message(chatId = cid, text = text, threadId = threadId),
                threadId?.let { Message(chatId = cid, text = text, threadId = null) },
            )
        } else {
            emptyList()
        }

        return withMarkup + fallbackWithoutMarkup
    }

    private suspend fun send(
        chatId: Long,
        text: String,
        threadId: Long? = null,
        replyMarkup: InlineKeyboardMarkup? = null,
        messageId: String? = null,
    ) {
        buildSendAttempts(chatId, text, threadId, replyMarkup, messageId).firstNotNullOfOrNull { msg ->
            runCatching { telegramService.sendMessage(msg) }.getOrNull()
        }
    }
}

private fun InstallationAdminContext.statusText(): String =
    buildString {
        append("Installation: ")
        append(id)
        append("\nGitLab: ")
        append(gitlabBaseUrl)
        gitlabProjectId?.let {
            append("\nProject: ")
            append(it)
        }
        telegramTopicId?.let {
            append("\nTopic: ")
            append(it)
        }
        append("\nMuted: ")
        append(if (muted) "yes" else "no")
    }

private fun ConfigWithSecrets.publicBaseUrl(): String = configuredPublicUrl()
