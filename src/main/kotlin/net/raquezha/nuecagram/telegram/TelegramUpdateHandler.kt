@file:Suppress("TooManyFunctions")

package net.raquezha.nuecagram.telegram

import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.db.AuditMetadataPatch
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRepository

private const val PRIVATE_BOOTSTRAP_MESSAGE = "Use /start in a private chat before using admin commands."
private const val GROUP_HELP_MESSAGE =
    "<b>Nuecagram is managed in private chat</b>\n\n" +
        "This group can receive GitLab notifications, but setup and repository management happen in DM.\n\n" +
        "Open <b>@NuecagramBot</b> in a private chat and tap <b>OPEN</b> to connect repositories."
private const val PRIVATE_START_MESSAGE =
    "<b>Nuecagram GitLab Notification Gateway</b>\n\n" +
        "I can help you deliver GitLab notifications directly to your Telegram groups and topics.\n\n" +
        "You can control me by sending these commands:\n\n" +
        "<b>Manage Installations</b>\n" +
        "• /manage - View and manage your connected repositories\n" +
        "• /rotate - Rotate webhook secret token for a project\n" +
        "• /mute - Pause notifications for a project\n" +
        "• /unmute - Resume notifications for a project\n" +
        "• /digest - View summary text for a project\n" +
        "• /test - Send a test notification to a group\n\n" +
        "<b>Help</b>\n" +
        "• /help - View command reference and instructions\n\n" +
        "💡 <i>Tap the <b>OPEN</b> menu button beside the chat box anytime to launch the WebApp Dashboard.</i>"
private const val WRONG_CHAT_MESSAGE = "Installation not found in this chat."
private const val MANAGEMENT_DM_REDIRECT_MESSAGE =
    "Continue in a private chat with <b>@NuecagramBot</b> to manage connected repositories."
private const val MANAGEMENT_DM_URL = "https://t.me/NuecagramBot"

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
        executeCallbackAction(callbackQuery, userId, installation, payload.action)
    }

    private suspend fun answerCallbackError(
        callbackId: String,
        text: String,
        showAlert: Boolean = false,
    ) {
        telegramService.answerCallbackQuery(callbackId, text, showAlert)
    }

    private suspend fun executeCallbackAction(
        callbackQuery: TelegramUpdate.CallbackQuery,
        userId: Long,
        installation: InstallationAdminContext,
        action: String,
    ) = when (action) {
        "mute" ->
            handleCallbackMute(
                callbackId = callbackQuery.id,
                userId = userId,
                installation = installation,
                actor = callbackQuery.from,
                muted = true,
                text = "Installation muted.",
                auditAction = "telegram_mute",
            )
        "unmute" ->
            handleCallbackMute(
                callbackId = callbackQuery.id,
                userId = userId,
                installation = installation,
                actor = callbackQuery.from,
                muted = false,
                text = "Installation unmuted.",
                auditAction = "telegram_unmute",
            )
        "test" -> handleCallbackTest(callbackQuery.id, userId, installation, callbackQuery.from)
        "status" -> telegramService.answerCallbackQuery(callbackQuery.id, installation.statusText(), showAlert = true)
        else -> telegramService.answerCallbackQuery(callbackQuery.id, "Unknown callback action.")
    }

    private suspend fun handleCallbackMute(
        callbackId: String,
        userId: Long,
        installation: InstallationAdminContext,
        actor: TelegramUpdate.User,
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
            metadataPatch = AuditMetadataPatch(
                actorUsername = actor.username,
                actorFirstName = actor.firstName,
            ),
        )
        return telegramService.answerCallbackQuery(callbackId, text)
    }

    private suspend fun handleCallbackTest(
        callbackId: String,
        userId: Long,
        installation: InstallationAdminContext,
        actor: TelegramUpdate.User,
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
            metadataPatch = AuditMetadataPatch(
                actorUsername = actor.username,
                actorFirstName = actor.firstName,
            ),
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
            "/manage" -> handleManage(message)
            "/webapp" -> sendManagementDmRedirect(message)
            "/rotate" -> handleRotate(message)
            "/setup" -> handleRemovedSetup(message)
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
                    listOf(InlineKeyboardButton(text = "Open @NuecagramBot", url = MANAGEMENT_DM_URL)),
                ),
            )
            send(message.chat.id, GROUP_HELP_MESSAGE, message.messageThreadId, replyMarkup = markup)
        }
    }

    private suspend fun handleStart(message: TelegramUpdate.Message) {
        val userId = message.from?.id
        if (message.chat.type == "private" && userId != null) {
            installationRepository.upsertTelegramPrivateChat(userId, message.chat.id)
            send(message.chat.id, PRIVATE_START_MESSAGE)
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



    private suspend fun handleRemovedSetup(message: TelegramUpdate.Message) {
        if (message.chat.type == "private") {
            send(
                message.chat.id,
                "Unknown command. Send <code>/help</code> for available commands.",
                message.messageThreadId,
            )
        } else {
            sendManagementDmRedirect(message)
        }
    }

    private suspend fun sendManagementDmRedirect(message: TelegramUpdate.Message) {
        val markup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(listOf(InlineKeyboardButton(text = "Open @NuecagramBot", url = MANAGEMENT_DM_URL))),
        )
        send(message.chat.id, MANAGEMENT_DM_REDIRECT_MESSAGE, message.messageThreadId, replyMarkup = markup)
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
