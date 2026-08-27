package net.raquezha.nuecagram.telegram

import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.configuredPublicUrl
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface PrivateCallbackAction {
    data class ListPage(val page: Int) : PrivateCallbackAction
    data class Menu(val targetId: String) : PrivateCallbackAction
    data class Status(val targetId: String) : PrivateCallbackAction
    data class Test(val targetId: String) : PrivateCallbackAction
    data class Digest(val targetId: String) : PrivateCallbackAction
    data class Mute(val targetId: String, val muted: Boolean) : PrivateCallbackAction
    data class RotateConfirm(val targetId: String) : PrivateCallbackAction
    data class RotateExecute(val targetId: String) : PrivateCallbackAction
    data class Back(val page: Int) : PrivateCallbackAction
    data object HelpSetup : PrivateCallbackAction
    data object HelpCommands : PrivateCallbackAction
    data object Unknown : PrivateCallbackAction

    companion object {
        fun from(payload: TelegramCallbackPayload): PrivateCallbackAction =
            when (payload.action) {
                "list" -> ListPage(parsePageIndex(payload.targetId))
                "menu" -> Menu(payload.targetId)
                "status" -> Status(payload.targetId)
                "test" -> Test(payload.targetId)
                "digest" -> Digest(payload.targetId)
                "mute" -> Mute(payload.targetId, muted = true)
                "unmute" -> Mute(payload.targetId, muted = false)
                "rotate:confirm" -> RotateConfirm(payload.targetId)
                "rotate:execute" -> RotateExecute(payload.targetId)
                "back" -> Back(parsePageIndex(payload.targetId))
                "help_setup" -> HelpSetup
                "help_commands" -> HelpCommands
                else -> Unknown
            }

        private fun parsePageIndex(targetId: String): Int =
            targetId.removePrefix("page=").toIntOrNull() ?: 0
    }
}

object TelegramMenuMessages {
    fun rotateConfirmation(installationId: UUID): String =
        "⚠️ <b>Rotate Webhook Secret</b>\n\n" +
            "Are you sure you want to rotate the webhook secret for installation <code>$installationId</code>?\n\n" +
            "The existing secret will stop working immediately."

    fun rotateSuccess(installationId: UUID, secret: String): String =
        "🔑 <b>Rotated installation:</b> <code>$installationId</code>\n" +
            "<b>GitLab secret token:</b> <code>$secret</code>"

    fun statusDetails(inst: InstallationAdminContext): String = buildString {
        append("⚙️ <b>Installation Status</b>\n\n")
        append("<b>ID:</b> <code>${inst.id}</code>\n")
        append("<b>GitLab:</b> <code>${inst.gitlabBaseUrl}</code>\n")
        inst.gitlabProjectId?.let { append("<b>Project ID:</b> <code>$it</code>\n") }
        append("<b>Telegram Chat:</b> <code>${inst.telegramChatId}</code>\n")
        inst.telegramTopicId?.let { append("<b>Topic ID:</b> <code>$it</code>\n") }
        append("<b>Muted:</b> ${if (inst.muted) "Yes 🔇" else "No 🔔"}")
    }

    fun testNotification(installationId: UUID): String =
        "🧪 <b>Test Message</b>\nNuecagram integration test for installation <code>$installationId</code>."

    fun digestSummary(installationId: UUID, chatId: Long): String =
        "📊 <b>Weekly Digest</b> for installation <code>$installationId</code>\n\n" +
            "Status: Active\n" +
            "Notifications: Enabled\n" +
            "Group: <code>$chatId</code>"

    fun managementLinkText(config: ConfigWithSecrets, installationId: UUID, token: String): String =
        "Management link for installation <code>$installationId</code>:\n" +
            "${config.publicBaseUrl()}/manage?token=$token"

    fun submenuText(inst: InstallationAdminContext): String = buildString {
        append("<b>Installation:</b> <code>")
        append(inst.id)
        append("</code>\nGitLab: ")
        append(inst.gitlabBaseUrl)
        inst.gitlabProjectId?.let {
            append("\nProject: ")
            append(it)
        }
        inst.telegramTopicId?.let {
            append("\nTopic: ")
            append(it)
        }
        append("\nStatus: ")
        append(if (inst.muted) "<b>Muted</b> 🔕" else "<b>Active</b> 🔔")
    }

    const val LIST_HEADER = "Select an installation to manage:"

    const val HELP_SETUP_TEXT =
        "⚙️ <b>First-Time Setup Instructions</b>\n\n" +
            "1. Send <code>/start</code> in a private chat with the bot.\n" +
            "2. Add the bot as an Administrator to your Telegram group or topic.\n" +
            "3. Run <code>/setup</code> in your group/topic, then finish GitLab setup in the Web App wizard."

    const val HELP_COMMANDS_TEXT =
        "📖 <b>Command Reference</b>\n\n" +
            "• <code>/setup</code> (Group) : Open setup wizard\n" +
            "• <code>/status &lt;inst-id&gt;</code> (DM) : View installation status\n" +
            "• <code>/test &lt;inst-id&gt;</code> (DM) : Send test notification\n" +
            "• <code>/manage [inst-id]</code> (DM) : Management dashboard / menu\n" +
            "• <code>/rotate &lt;inst-id&gt;</code> (DM) : Rotate secret token\n" +
            "• <code>/mute &lt;inst-id&gt;</code> / <code>/unmute &lt;inst-id&gt;</code> (DM) :\n" +
            "  Pause / resume notifications\n" +
            "• <code>/digest &lt;inst-id&gt;</code> (DM) : View summary text"
}

private fun ConfigWithSecrets.publicBaseUrl(): String = configuredPublicUrl()

@Suppress("TooManyFunctions")
class TelegramMenuHandler(
    private val telegramService: TelegramService,
    private val installationRepository: InstallationRepository,
    private val config: ConfigWithSecrets,
) {

    suspend fun handlePrivateManage(
        message: TelegramUpdate.Message,
        userId: Long,
        query: String?,
    ) {
        if (query == null) {
            val adminInstalls = installationRepository.installationsForAdmin(userId)
            if (adminInstalls.isEmpty()) {
                send(message.chat.id, "No installations found for your account.", message.messageThreadId)
            } else {
                val (text, markup) = buildInstallationListMarkup(adminInstalls, page = 0)
                send(message.chat.id, text, message.messageThreadId, replyMarkup = markup)
            }
        } else {
            val inst = findAdminInstallation(userId, query)
            if (inst == null) {
                send(message.chat.id, "Installation not found.", message.messageThreadId)
            } else {
                val managementLink = installationRepository.issueManagementLink(inst.id, managementLinkExpiry())
                installationRepository.writeAuditEvent(
                    installationId = inst.id,
                    actorType = "telegram",
                    actorId = userId.toString(),
                    action = "telegram_management_link",
                )
                send(message.chat.id, TelegramMenuMessages.managementLinkText(config, inst.id, managementLink.raw))
                sendLauncherMessage(
                    message.chat.id,
                    message.messageThreadId,
                    userId,
                    PRIVATE_DELIVERY_MESSAGE,
                    "Open Dashboard in Web App",
                )
            }
        }
    }

    suspend fun handlePrivateCallbackQuery(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        payload: TelegramCallbackPayload,
        userId: Long,
    ) {
        when (val action = PrivateCallbackAction.from(payload)) {
            is PrivateCallbackAction.ListPage -> handlePrivateListCallback(callbackQuery, message, userId, action.page)
            is PrivateCallbackAction.Back -> handlePrivateBackCallback(callbackQuery, message, userId, action.page)
            is PrivateCallbackAction.Menu -> handlePrivateMenuCallback(callbackQuery, message, userId, action.targetId)
            is PrivateCallbackAction.Status -> handlePrivateStatusCallback(callbackQuery, userId, action.targetId)
            is PrivateCallbackAction.Test -> handlePrivateTestCallback(callbackQuery, userId, action.targetId)
            is PrivateCallbackAction.Digest -> handlePrivateDigestCallback(callbackQuery, userId, action.targetId)
            is PrivateCallbackAction.Mute ->
                handlePrivateMuteCallback(callbackQuery, message, userId, action.targetId, action.muted)
            is PrivateCallbackAction.RotateConfirm ->
                handlePrivateRotateConfirmCallback(callbackQuery, message, userId, action.targetId)
            is PrivateCallbackAction.RotateExecute ->
                handlePrivateRotateExecuteCallback(callbackQuery, message, userId, action.targetId)
            PrivateCallbackAction.HelpSetup -> handlePrivateHelpSetupCallback(callbackQuery, message)
            PrivateCallbackAction.HelpCommands -> handlePrivateHelpCommandsCallback(callbackQuery, message)
            PrivateCallbackAction.Unknown -> answerCallbackError(callbackQuery.id, "Unknown callback action.")
        }
    }

    private suspend fun handlePrivateListCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        page: Int,
    ) {
        val adminInstalls = installationRepository.installationsForAdmin(userId)
        if (adminInstalls.isEmpty()) {
            answerCallbackError(callbackQuery.id, "No installations found.", showAlert = true)
            return
        }
        val (text, markup) = buildInstallationListMarkup(adminInstalls, page)
        send(
            chatId = message.chat.id,
            text = text,
            replyMarkup = markup,
            messageId = message.messageId?.toString(),
        )
        telegramService.answerCallbackQuery(callbackQuery.id)
    }

    private suspend fun handlePrivateBackCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        page: Int,
    ) = handlePrivateListCallback(callbackQuery, message, userId, page)

    private suspend fun handlePrivateMenuCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        targetId: String,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            answerCallbackError(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        val (text, markup) = buildSubmenuMarkup(inst)
        send(
            chatId = message.chat.id,
            text = text,
            replyMarkup = markup,
            messageId = message.messageId?.toString(),
        )
        telegramService.answerCallbackQuery(callbackQuery.id)
    }

    private suspend fun handlePrivateStatusCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        userId: Long,
        targetId: String,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            answerCallbackError(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        answerCallbackError(callbackQuery.id, TelegramMenuMessages.statusDetails(inst), showAlert = true)
    }

    private suspend fun handlePrivateTestCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        userId: Long,
        targetId: String,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            answerCallbackError(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        send(
            chatId = inst.telegramChatId,
            text = TelegramMenuMessages.testNotification(inst.id),
            messageThreadId = inst.telegramTopicId,
        )
        telegramService.answerCallbackQuery(
            callbackQueryId = callbackQuery.id,
            text = "Test notification sent to chat ${inst.telegramChatId}.",
            showAlert = true,
        )
    }

    private suspend fun handlePrivateDigestCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        userId: Long,
        targetId: String,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            answerCallbackError(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        val digestText = TelegramMenuMessages.digestSummary(inst.id, inst.telegramChatId)
        answerCallbackError(callbackQuery.id, digestText, showAlert = true)
    }

    private suspend fun handlePrivateMuteCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        targetId: String,
        muted: Boolean,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            answerCallbackError(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        installationRepository.setMuted(inst.id, muted)
        installationRepository.writeAuditEvent(
            installationId = inst.id,
            actorType = "telegram",
            actorId = userId.toString(),
            action = if (muted) "telegram_mute" else "telegram_unmute",
        )
        val updatedInst = inst.copy(muted = muted)
        val (text, markup) = buildSubmenuMarkup(updatedInst)
        send(
            chatId = message.chat.id,
            text = text,
            replyMarkup = markup,
            messageId = message.messageId?.toString(),
        )
        telegramService.answerCallbackQuery(
            callbackQueryId = callbackQuery.id,
            text = if (muted) "Notifications muted." else "Notifications unmuted.",
        )
    }

    private suspend fun handlePrivateRotateConfirmCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        targetId: String,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            answerCallbackError(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        val text = TelegramMenuMessages.rotateConfirmation(inst.id)
        val markup = buildRotateConfirmationMarkup(inst.id)
        send(
            chatId = message.chat.id,
            text = text,
            replyMarkup = markup,
            messageId = message.messageId?.toString(),
        )
        telegramService.answerCallbackQuery(callbackQuery.id)
    }

    private suspend fun handlePrivateRotateExecuteCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        targetId: String,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            answerCallbackError(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        val newSecret = installationRepository.rotateWebhookSecret(
            installationId = inst.id,
            graceUntil = Instant.now(),
        )
        installationRepository.writeAuditEvent(
            installationId = inst.id,
            actorType = "telegram",
            actorId = userId.toString(),
            action = "telegram_rotate",
        )
        val text = TelegramMenuMessages.rotateSuccess(inst.id, newSecret.raw)
        val markup = buildRotateSuccessMarkup(inst.id)
        send(
            chatId = message.chat.id,
            text = text,
            replyMarkup = markup,
            messageId = message.messageId?.toString(),
        )
        telegramService.answerCallbackQuery(
            callbackQueryId = callbackQuery.id,
            text = "Webhook secret rotated successfully.",
        )
    }

    private fun buildRotateConfirmationMarkup(installationId: UUID): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(
                        text = "✅ Yes, Rotate Secret",
                        callbackData = "inst:rotate:execute:$installationId",
                    ),
                ),
                listOf(
                    InlineKeyboardButton(
                        text = "🔙 Cancel",
                        callbackData = "inst:menu:$installationId",
                    ),
                ),
            ),
        )

    private fun buildRotateSuccessMarkup(installationId: UUID): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(
                        text = "🔙 Back to Menu",
                        callbackData = "inst:menu:$installationId",
                    ),
                ),
            ),
        )

    private fun buildInstallationListMarkup(
        installations: List<InstallationAdminContext>,
        page: Int,
    ): Pair<String, InlineKeyboardMarkup> {
        val totalPages = (installations.size + PAGE_SIZE - 1) / PAGE_SIZE
        val validPage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        val pageItems = installations.drop(validPage * PAGE_SIZE).take(PAGE_SIZE)

        val rows = mutableListOf<List<InlineKeyboardButton>>()
        for (inst in pageItems) {
            val label = buildString {
                inst.gitlabProjectId?.let { append("Project $it ") }
                    ?: append(
                        inst.gitlabBaseUrl
                            .removePrefix("https://")
                            .removePrefix("http://")
                            .take(DISPLAY_URL_MAX_LENGTH) + " ",
                    )
                append("(${inst.id.toString().take(SHORT_ID_LENGTH)})")
            }
            rows += listOf(
                InlineKeyboardButton(
                    text = label,
                    callbackData = "inst:menu:${inst.id}",
                ),
            )
        }

        if (totalPages > 1) {
            val navRow = mutableListOf<InlineKeyboardButton>()
            if (validPage > 0) {
                navRow += InlineKeyboardButton(text = "⬅️ Prev", callbackData = "inst:list:page=${validPage - 1}")
            }
            navRow += InlineKeyboardButton(
                text = "${validPage + 1}/$totalPages",
                callbackData = "inst:list:page=$validPage",
            )
            if (validPage < totalPages - 1) {
                navRow += InlineKeyboardButton(text = "Next ➡️", callbackData = "inst:list:page=${validPage + 1}")
            }
            rows += navRow
        }

        return TelegramMenuMessages.LIST_HEADER to InlineKeyboardMarkup(inlineKeyboard = rows)
    }

    private fun buildSubmenuMarkup(
        inst: InstallationAdminContext,
    ): Pair<String, InlineKeyboardMarkup> {
        val webAppUrl = "${config.publicBaseUrl()}/webapp?startapp=inst_${inst.id.toString().take(SHORT_ID_LENGTH)}"
        val muteLabel = if (inst.muted) "Unmute" else "Mute"
        val muteAction = if (inst.muted) "inst:unmute:${inst.id}" else "inst:mute:${inst.id}"

        val text = TelegramMenuMessages.submenuText(inst)
        val markup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(text = "Status", callbackData = "inst:status:${inst.id}"),
                    InlineKeyboardButton(text = "Test", callbackData = "inst:test:${inst.id}"),
                ),
                listOf(
                    InlineKeyboardButton(text = muteLabel, callbackData = muteAction),
                    InlineKeyboardButton(text = "Digest", callbackData = "inst:digest:${inst.id}"),
                ),
                listOf(
                    InlineKeyboardButton(text = "Rotate Secret", callbackData = "inst:rotate:confirm:${inst.id}"),
                    InlineKeyboardButton(text = "Open Web App", webApp = WebAppInfo(url = webAppUrl)),
                ),
                listOf(
                    InlineKeyboardButton(text = "⬅️ Back", callbackData = "inst:back:page=0"),
                ),
            ),
        )
        return text to markup
    }

    suspend fun sendPrivateHelpMenu(message: TelegramUpdate.Message) {
        val text =
            "<b>Nuecagram Assistant</b>\n\n" +
                "Select an option below to manage notification installations or view command instructions:"
        val markup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(InlineKeyboardButton(text = "📦 My Installations", callbackData = "inst:list:page=0")),
                listOf(InlineKeyboardButton(text = "⚙️ Setup Instructions", callbackData = "inst:help_setup:all")),
                listOf(InlineKeyboardButton(text = "📖 Command List", callbackData = "inst:help_commands:all")),
            ),
        )
        send(message.chat.id, text, message.messageThreadId, replyMarkup = markup)
    }

    private suspend fun handlePrivateHelpSetupCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
    ) {
        val markup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(InlineKeyboardButton(text = "⬅️ Back", callbackData = "inst:back:page=0")),
            ),
        )
        send(
            chatId = message.chat.id,
            text = TelegramMenuMessages.HELP_SETUP_TEXT,
            replyMarkup = markup,
            messageId = message.messageId?.toString(),
        )
        telegramService.answerCallbackQuery(callbackQuery.id)
    }

    private suspend fun handlePrivateHelpCommandsCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
    ) {
        val markup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(InlineKeyboardButton(text = "⬅️ Back", callbackData = "inst:back:page=0")),
            ),
        )
        send(
            chatId = message.chat.id,
            text = TelegramMenuMessages.HELP_COMMANDS_TEXT,
            replyMarkup = markup,
            messageId = message.messageId?.toString(),
        )
        telegramService.answerCallbackQuery(callbackQuery.id)
    }

    private suspend fun findAdminInstallation(
        userId: Long,
        query: String,
    ): InstallationAdminContext? {
        val adminInstalls = installationRepository.installationsForAdmin(userId)
        if (adminInstalls.isEmpty()) return null
        val cleanQuery = query.trim().lowercase()
        val uuid = runCatching { UUID.fromString(cleanQuery) }.getOrNull()
        if (uuid != null) {
            return adminInstalls.firstOrNull { it.id == uuid }
        }
        return adminInstalls.firstOrNull { inst ->
            inst.id.toString().lowercase().startsWith(cleanQuery) ||
                inst.gitlabProjectId?.toString() == cleanQuery ||
                inst.gitlabBaseUrl.lowercase().contains(cleanQuery)
        }
    }

    private suspend fun answerCallbackError(
        callbackId: String,
        text: String,
        showAlert: Boolean = false,
    ) {
        telegramService.answerCallbackQuery(
            callbackQueryId = callbackId,
            text = text,
            showAlert = showAlert,
        )
    }

    private suspend fun sendLauncherMessage(
        chatId: Long,
        topicId: Long?,
        actorId: Long,
        text: String,
        buttonText: String,
    ) {
        val nonce = installationRepository.issueLaunchNonce(
            telegramChatId = chatId,
            telegramTopicId = topicId,
            telegramUserId = actorId,
            expiresAt = managementLinkExpiry(),
        )
        val url = "${config.publicBaseUrl()}/webapp?startapp=nonce_${nonce.raw}"
        val markup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(
                        text = buttonText,
                        webApp = WebAppInfo(url = url),
                    ),
                ),
            ),
        )
        send(chatId, text, topicId, replyMarkup = markup)
    }

    private fun managementLinkExpiry(): Instant = Instant.now().plus(Duration.ofMinutes(LAUNCH_NONCE_TTL_MINUTES))

    private suspend fun send(
        chatId: Long,
        text: String,
        messageThreadId: Long? = null,
        replyMarkup: InlineKeyboardMarkup? = null,
        messageId: String? = null,
    ): String =
        telegramService.sendMessage(
            Message(
                chatId = chatId.toString(),
                text = text,
                threadId = messageThreadId,
                replyMarkup = replyMarkup,
                messageId = messageId,
            ),
        )

    companion object {
        private const val LAUNCH_NONCE_TTL_MINUTES = 10L
        private const val PAGE_SIZE = 8
        private const val DISPLAY_URL_MAX_LENGTH = 20
        private const val SHORT_ID_LENGTH = 8
        private const val PRIVATE_DELIVERY_MESSAGE = "Private setup details sent."
    }
}
