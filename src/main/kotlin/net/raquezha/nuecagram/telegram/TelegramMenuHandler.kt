package net.raquezha.nuecagram.telegram

import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.configuredPublicUrl
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

private data class InstallationPickerSpec(
    val actionPrefix: String,
    val pageActionPrefix: String,
    val headerText: String,
)

sealed interface PrivateCallbackAction {
    data class ListPage(val page: Int, val action: String) : PrivateCallbackAction
    data class Menu(val targetId: String) : PrivateCallbackAction
    data class Status(val targetId: String) : PrivateCallbackAction
    data class Test(val targetId: String) : PrivateCallbackAction
    data class Digest(val targetId: String) : PrivateCallbackAction
    data class Mute(val targetId: String, val muted: Boolean) : PrivateCallbackAction
    data class RotateConfirm(val targetId: String) : PrivateCallbackAction
    data class RotateExecute(val targetId: String) : PrivateCallbackAction
    data class Back(val page: Int) : PrivateCallbackAction
    data object HelpMenu : PrivateCallbackAction
    data object HelpSetup : PrivateCallbackAction
    data object HelpCommands : PrivateCallbackAction
    data object Unknown : PrivateCallbackAction

    companion object {
        @Suppress("CyclomaticComplexMethod")
        fun from(payload: TelegramCallbackPayload): PrivateCallbackAction =
            when {
                payload.targetId.startsWith("page=") -> ListPage(parsePageIndex(payload.targetId), payload.action)
                payload.action == "list" -> ListPage(parsePageIndex(payload.targetId), payload.action)
                else -> when (payload.action) {
                "menu" -> Menu(payload.targetId)
                "status" -> Status(payload.targetId)
                "test" -> Test(payload.targetId)
                "digest" -> Digest(payload.targetId)
                "mute" -> Mute(payload.targetId, muted = true)
                "unmute" -> Mute(payload.targetId, muted = false)
                "rotate:confirm" -> RotateConfirm(payload.targetId)
                "rotate:execute" -> RotateExecute(payload.targetId)
                "back" -> Back(parsePageIndex(payload.targetId))
                "help_menu" -> HelpMenu
                "help_setup" -> HelpSetup
                "help_commands" -> HelpCommands
                else -> Unknown
            }
            }

        private fun parsePageIndex(targetId: String): Int =
            targetId.removePrefix("page=").toIntOrNull() ?: 0
    }
}

object TelegramMenuMessages {
    fun rotateConfirmation(inst: InstallationAdminContext): String =
        "⚠️ <b>Rotate Webhook Secret</b>\n\n" +
            "Are you sure you want to rotate the webhook secret for <b>${inst.repositoryButtonLabel()}</b>?\n\n" +
            "The existing secret will stop working immediately."

    fun rotateSuccess(inst: InstallationAdminContext, secret: String): String =
        "✅ <b>Rotated secret for:</b> <b>${inst.repositoryButtonLabel()}</b>\n" +
            "<b>GitLab secret token:</b> <code>$secret</code>"

    fun statusDetails(inst: InstallationAdminContext): String = buildString {
        append("⚙️ <b>Installation Status</b>\n\n")
        append("<b>Repository:</b> <b>${inst.repositoryButtonLabel()}</b>\n")
        append("<b>GitLab:</b> <code>${inst.gitlabBaseUrl}</code>\n")
        inst.gitlabProjectId?.let { append("<b>Project ID:</b> <code>$it</code>\n") }
        append("<b>Telegram Chat:</b> <code>${inst.telegramChatId}</code>\n")
        inst.telegramTopicId?.let { append("<b>Topic ID:</b> <code>$it</code>\n") }
        append("<b>Muted:</b> ${if (inst.muted) "Yes 🔇" else "No 🔔"}")
    }

    fun testNotification(inst: InstallationAdminContext): String =
        "🔔 <b>Test Message</b>\nNuecagram notification test for <b>${inst.repositoryButtonLabel()}</b>."

    fun digestSummary(inst: InstallationAdminContext): String =
        "📊 <b>Weekly Digest</b> for <b>${inst.repositoryButtonLabel()}</b>\n\n" +
            "Status: Active\n" +
            "Notifications: Enabled\n" +
            "Group: <code>${inst.telegramChatId}</code>"

    fun managementLinkText(config: ConfigWithSecrets, inst: InstallationAdminContext, token: String): String =
        "Management link for <b>${inst.repositoryButtonLabel()}</b>:\n" +
            "${config.publicBaseUrl()}/manage?token=$token"

    fun submenuText(inst: InstallationAdminContext): String = buildString {
        append("<b>Repository:</b> <b>")
        append(inst.repositoryButtonLabel())
        append("</b>\nGitLab: ")
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
        append(if (inst.muted) "<b>Muted</b> 🔇" else "<b>Active</b> 🔔")
    }
    const val LIST_HEADER = "Select an installation to manage:"

    const val HELP_SETUP_TEXT =
        "⚙️ <b>First-Time Setup Instructions</b>\n\n" +
            "1. Send <code>/start</code> in a private chat with the bot.\n" +
            "2. Add the bot as an Administrator to your Telegram group or topic.\n" +
            "3. Run <code>/setup</code> in your group/topic, then finish GitLab setup in the Web App wizard."

    const val HELP_COMMANDS_TEXT =
        "📖 <b>Command Reference</b>\n\n" +
            "• <code>/repos</code> (or <code>/repositories</code>, <code>/projects</code>) :\n" +
            "  View connected repositories\n" +
            "• <code>/status</code> (DM) : View repository status summary\n" +
            "• <code>/test</code> (DM) : Send test notification\n" +
            "• <code>/rotate</code> (DM) : Rotate secret token\n" +
            "• <code>/mute</code> / <code>/unmute</code> (DM) : Pause / resume notifications\n" +
            "• <code>/digest</code> (DM) : View digest summary\n" +
            "• <code>/setup</code> (Group) : Open setup wizard"
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
            val authorized = getAuthorizedInstallations(userId)
            if (authorized.isEmpty()) {
                send(message.chat.id, "No installations found for your account.", message.messageThreadId)
            } else {
                val (text, markup) = buildInstallationListMarkup(authorized, page = 0)
                send(message.chat.id, text, message.messageThreadId, replyMarkup = markup)
            }
        } else {
            val inst = findAuthorizedInstallation(userId, query)
            if (inst == null) {
                val text = if (userIsDemoted(userId, query)) TELEGRAM_ADMIN_ONLY_MESSAGE else "Installation not found."
                send(message.chat.id, text, message.messageThreadId)
            } else {
                val managementLink = installationRepository.issueManagementLink(inst.id, managementLinkExpiry())
                installationRepository.writeAuditEvent(
                    installationId = inst.id,
                    actorType = "telegram",
                    actorId = userId.toString(),
                    action = "telegram_management_link",
                )
                send(message.chat.id, TelegramMenuMessages.managementLinkText(config, inst, managementLink.raw))
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
            is PrivateCallbackAction.ListPage ->
                handlePrivateListCallback(callbackQuery, message, userId, action.page, action.action)
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
            PrivateCallbackAction.HelpMenu -> handlePrivateHelpMenuCallback(callbackQuery, message)
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
        action: String,
    ) {
        val authorized = getAuthorizedInstallations(userId)
        if (authorized.isEmpty()) {
            answerCallbackError(callbackQuery.id, "No installations found.", showAlert = true)
            return
        }
        val spec = pickerSpec(action)
        val (text, markup) = buildInstallationListMarkup(
            authorized,
            page,
            actionPrefix = spec.actionPrefix,
            headerText = spec.headerText,
            pageActionPrefix = spec.pageActionPrefix,
        )
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
    ) = handlePrivateListCallback(callbackQuery, message, userId, page, "list")

    private suspend fun handlePrivateMenuCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        targetId: String,
    ) {
        val inst = findCallbackInstallation(callbackQuery, userId, targetId) ?: return
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
        val inst = findCallbackInstallation(callbackQuery, userId, targetId) ?: return
        answerCallbackError(callbackQuery.id, TelegramMenuMessages.statusDetails(inst), showAlert = true)
    }

    private suspend fun handlePrivateTestCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        userId: Long,
        targetId: String,
    ) {
        val inst = findCallbackInstallation(callbackQuery, userId, targetId) ?: return
        send(
            chatId = inst.telegramChatId,
            text = TelegramMenuMessages.testNotification(inst),
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
        val inst = findCallbackInstallation(callbackQuery, userId, targetId) ?: return
        val digestText = TelegramMenuMessages.digestSummary(inst)
        answerCallbackError(callbackQuery.id, digestText, showAlert = true)
    }

    private suspend fun handlePrivateMuteCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        targetId: String,
        muted: Boolean,
    ) {
        val inst = findCallbackInstallation(callbackQuery, userId, targetId) ?: return
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
        val inst = findCallbackInstallation(callbackQuery, userId, targetId) ?: return
        val text = TelegramMenuMessages.rotateConfirmation(inst)
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
        val inst = findCallbackInstallation(callbackQuery, userId, targetId) ?: return
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
        val text = TelegramMenuMessages.rotateSuccess(inst, newSecret.raw)
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
                        text = "« Cancel",
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
                        text = "« Back to Menu",
                        callbackData = "inst:menu:$installationId",
                    ),
                ),
            ),
        )

    private fun pickerSpec(action: String): InstallationPickerSpec =
        when (action) {
            "status" -> InstallationPickerSpec(
                actionPrefix = "inst:status",
                pageActionPrefix = "inst:status",
                headerText = "<b>Repository Status Summary</b>\nSelect a repository to view details:",
            )
            "test" -> InstallationPickerSpec(
                actionPrefix = "inst:test",
                pageActionPrefix = "inst:test",
                headerText = "Select a repository to send a test notification:",
            )
            "rotate:confirm" -> InstallationPickerSpec(
                actionPrefix = "inst:rotate:confirm",
                pageActionPrefix = "inst:rotate:confirm",
                headerText = "Select a repository to rotate its webhook secret:",
            )
            "mute" -> InstallationPickerSpec(
                actionPrefix = "inst:mute",
                pageActionPrefix = "inst:mute",
                headerText = "Select a repository to pause notifications:",
            )
            "unmute" -> InstallationPickerSpec(
                actionPrefix = "inst:unmute",
                pageActionPrefix = "inst:unmute",
                headerText = "Select a repository to resume notifications:",
            )
            "digest" -> InstallationPickerSpec(
                actionPrefix = "inst:digest",
                pageActionPrefix = "inst:digest",
                headerText = "Select a repository to view digest summary:",
            )
            else -> InstallationPickerSpec(
                actionPrefix = "inst:menu",
                pageActionPrefix = "inst:list",
                headerText = TelegramMenuMessages.LIST_HEADER,
            )
        }

    private fun buildInstallationListMarkup(
        installations: List<InstallationAdminContext>,
        page: Int,
        actionPrefix: String = "inst:menu",
        headerText: String = TelegramMenuMessages.LIST_HEADER,
        pageActionPrefix: String = "inst:list",
    ): Pair<String, InlineKeyboardMarkup> {
        val totalPages = (installations.size + PAGE_SIZE - 1) / PAGE_SIZE
        val validPage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        val pageItems = installations.drop(validPage * PAGE_SIZE).take(PAGE_SIZE)

        val rows = mutableListOf<List<InlineKeyboardButton>>()
        for (inst in pageItems) {
            rows += listOf(
                InlineKeyboardButton(
                    text = inst.repositoryButtonLabel(),
                    callbackData = "$actionPrefix:${inst.id}",
                ),
            )
        }

        if (totalPages > 1) {
            val navRow = mutableListOf<InlineKeyboardButton>()
            if (validPage > 0) {
                navRow += InlineKeyboardButton(
                    text = "<< Prev",
                    callbackData = "$pageActionPrefix:page=${validPage - 1}",
                )
            }
            navRow += InlineKeyboardButton(
                text = "${validPage + 1}/$totalPages",
                callbackData = "$pageActionPrefix:page=$validPage",
            )
            if (validPage < totalPages - 1) {
                navRow += InlineKeyboardButton(
                    text = "Next >>",
                    callbackData = "$pageActionPrefix:page=${validPage + 1}",
                )
            }
            rows += navRow
        }

        return headerText to InlineKeyboardMarkup(inlineKeyboard = rows)
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
                    InlineKeyboardButton(text = "« Back", callbackData = "inst:help_menu:all"),
                ),
            ),
        )
        return text to markup
    }

    suspend fun sendPrivateHelpMenu(
        message: TelegramUpdate.Message,
        messageId: String? = null,
    ) {
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
        send(
            chatId = message.chat.id,
            text = text,
            messageThreadId = message.messageThreadId,
            replyMarkup = markup,
            messageId = messageId,
        )
    }

    private suspend fun handlePrivateHelpMenuCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
    ) {
        sendPrivateHelpMenu(message, messageId = message.messageId?.toString())
        telegramService.answerCallbackQuery(callbackQuery.id)
    }

    private suspend fun handlePrivateHelpSetupCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
    ) {
        val markup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(InlineKeyboardButton(text = "« Back", callbackData = "inst:help_menu:all")),
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
                listOf(InlineKeyboardButton(text = "« Back", callbackData = "inst:help_menu:all")),
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

    suspend fun handlePrivateRepos(
        message: TelegramUpdate.Message,
        userId: Long,
        query: String? = null,
    ) {
        if (query != null) {
            handlePrivateTargetAction(message, userId, query) { inst ->
                val (text, markup) = buildSubmenuMarkup(inst)
                send(message.chat.id, text, message.messageThreadId, replyMarkup = markup)
            }
            return
        }

        val authorized = getAuthorizedInstallations(userId)
        if (authorized.isEmpty()) {
            send(message.chat.id, "No installations found for your account.", message.messageThreadId)
        } else {
            val (text, markup) = buildInstallationListMarkup(
                authorized,
                page = 0,
                actionPrefix = "inst:menu",
                headerText = TelegramMenuMessages.LIST_HEADER,
            )
            send(message.chat.id, text, message.messageThreadId, replyMarkup = markup)
        }
    }

    suspend fun handlePrivateStatus(
        message: TelegramUpdate.Message,
        userId: Long,
        query: String? = null,
    ) {
        if (query != null) {
            handlePrivateTargetAction(message, userId, query) { inst ->
                send(message.chat.id, TelegramMenuMessages.statusDetails(inst), message.messageThreadId)
            }
            return
        }

        val authorized = getAuthorizedInstallations(userId)
        sendInstallationPicker(
            message = message,
            userId = userId,
            actionPrefix = "inst:status",
            headerText = "<b>Repository Status Summary</b>\nSelect a repository to view details:",
        )
    }

    suspend fun handlePrivateRotate(
        message: TelegramUpdate.Message,
        userId: Long,
        query: String? = null,
    ) {
        if (query != null) {
            handlePrivateTargetAction(message, userId, query) { inst ->
                val text = TelegramMenuMessages.rotateConfirmation(inst)
                val markup = buildRotateConfirmationMarkup(inst.id)
                send(message.chat.id, text, message.messageThreadId, replyMarkup = markup)
            }
            return
        }

        sendInstallationPicker(
            message = message,
            userId = userId,
            actionPrefix = "inst:rotate:confirm",
            headerText = "Select a repository to rotate its webhook secret:",
        )
    }

    suspend fun handlePrivateMuteCommand(
        message: TelegramUpdate.Message,
        userId: Long,
        query: String? = null,
    ) {
        if (query != null) {
            handlePrivateTargetAction(message, userId, query) { inst ->
                executePrivateMute(message, userId, inst, muted = true)
            }
            return
        }

        sendInstallationPicker(
            message = message,
            userId = userId,
            actionPrefix = "inst:mute",
            headerText = "Select a repository to pause notifications:",
        )
    }

    suspend fun handlePrivateUnmuteCommand(
        message: TelegramUpdate.Message,
        userId: Long,
        query: String? = null,
    ) {
        if (query != null) {
            handlePrivateTargetAction(message, userId, query) { inst ->
                executePrivateMute(message, userId, inst, muted = false)
            }
            return
        }

        sendInstallationPicker(
            message = message,
            userId = userId,
            actionPrefix = "inst:unmute",
            headerText = "Select a repository to resume notifications:",
        )
    }

    suspend fun handlePrivateTestCommand(
        message: TelegramUpdate.Message,
        userId: Long,
        query: String? = null,
    ) {
        if (query != null) {
            handlePrivateTargetAction(message, userId, query) { inst ->
                executePrivateTest(message, userId, inst)
            }
            return
        }

        sendInstallationPicker(
            message = message,
            userId = userId,
            actionPrefix = "inst:test",
            headerText = "Select a repository to send a test notification:",
        )
    }

    suspend fun handlePrivateDigestCommand(
        message: TelegramUpdate.Message,
        userId: Long,
        query: String? = null,
    ) {
        if (query != null) {
            handlePrivateTargetAction(message, userId, query) { inst ->
                val digestText = TelegramMenuMessages.digestSummary(inst)
                send(message.chat.id, digestText, message.messageThreadId)
            }
            return
        }

        sendInstallationPicker(
            message = message,
            userId = userId,
            actionPrefix = "inst:digest",
            headerText = "Select a repository to view digest summary:",
        )
    }

    private suspend fun sendInstallationPicker(
        message: TelegramUpdate.Message,
        userId: Long,
        actionPrefix: String,
        headerText: String,
    ) {
        val authorized = getAuthorizedInstallations(userId)
        if (authorized.isEmpty()) {
            send(message.chat.id, "No installations found for your account.", message.messageThreadId)
            return
        }
        val (text, markup) = buildInstallationListMarkup(
            authorized,
            page = 0,
            actionPrefix = actionPrefix,
            headerText = headerText,
            pageActionPrefix = actionPrefix,
        )
        send(message.chat.id, text, message.messageThreadId, replyMarkup = markup)
    }

    private suspend fun executePrivateMute(
        message: TelegramUpdate.Message,
        userId: Long,
        inst: InstallationAdminContext,
        muted: Boolean,
    ) {
        installationRepository.setMuted(inst.id, muted)
        installationRepository.writeAuditEvent(
            installationId = inst.id,
            actorType = "telegram",
            actorId = userId.toString(),
            action = if (muted) "telegram_mute" else "telegram_unmute",
        )
        val text = if (muted) "Installation muted." else "Installation unmuted."
        send(message.chat.id, text, message.messageThreadId)
    }

    private suspend fun executePrivateTest(
        message: TelegramUpdate.Message,
        userId: Long,
        inst: InstallationAdminContext,
    ) {
        send(
            chatId = inst.telegramChatId,
            text = TelegramMenuMessages.testNotification(inst),
            messageThreadId = inst.telegramTopicId,
        )
        installationRepository.writeAuditEvent(
            installationId = inst.id,
            actorType = "telegram",
            actorId = userId.toString(),
            action = "telegram_delivery_test",
        )
        send(message.chat.id, "Test notification sent to chat ${inst.telegramChatId}.", message.messageThreadId)
    }

    private suspend fun handlePrivateTargetAction(
        message: TelegramUpdate.Message,
        userId: Long,
        query: String,
        action: suspend (InstallationAdminContext) -> Unit,
    ) {
        val inst = findAuthorizedInstallation(userId, query)
        when {
            inst != null -> action(inst)
            userIsDemoted(userId, query) ->
                send(message.chat.id, TELEGRAM_ADMIN_ONLY_MESSAGE, message.messageThreadId)
            else -> send(message.chat.id, "Installation not found.", message.messageThreadId)
        }
    }

    private suspend fun findCallbackInstallation(
        callbackQuery: TelegramUpdate.CallbackQuery,
        userId: Long,
        targetId: String,
    ): InstallationAdminContext? {
        val inst = findAuthorizedInstallation(userId, targetId)
        if (inst == null) {
            val text = if (userIsDemoted(userId, targetId)) TELEGRAM_ADMIN_ONLY_MESSAGE else "Installation not found."
            answerCallbackError(callbackQuery.id, text, showAlert = true)
            return null
        }
        return inst
    }

    private suspend fun findAuthorizedInstallation(
        userId: Long,
        query: String,
    ): InstallationAdminContext? {
        val inst = installationRepository.findInstallationByQuery(query)
            ?: installationRepository.installationsForAdmin(userId).firstOrNull {
                it.id.toString().take(SHORT_ID_LENGTH).equals(query.take(SHORT_ID_LENGTH), ignoreCase = true)
            }
            ?: return null
        val status = runCatching { telegramService.chatMemberStatus(inst.telegramChatId, userId) }.getOrNull()
        return if (isTelegramAdmin(status)) {
            installationRepository.recordInstallationAdmin(inst.id, userId)
            inst
        } else {
            null
        }
    }

    private suspend fun userIsDemoted(userId: Long, query: String): Boolean {
        val inst = installationRepository.findInstallationByQuery(query) ?: return false
        val status = runCatching { telegramService.chatMemberStatus(inst.telegramChatId, userId) }.getOrNull()
        return !isTelegramAdmin(status)
    }

    private suspend fun getAuthorizedInstallations(userId: Long): List<InstallationAdminContext> {
        val candidates = installationRepository.installationsForAdmin(userId)
        return candidates.filter { inst ->
            val status = runCatching { telegramService.chatMemberStatus(inst.telegramChatId, userId) }.getOrNull()
            isTelegramAdmin(status)
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
