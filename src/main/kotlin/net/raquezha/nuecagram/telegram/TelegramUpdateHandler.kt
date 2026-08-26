@file:Suppress("TooManyFunctions")

package net.raquezha.nuecagram.telegram

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.configuredPublicUrl
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRecord
import net.raquezha.nuecagram.db.InstallationRepository

private const val PRIVATE_BOOTSTRAP_MESSAGE = "Use /start in a private chat before using admin commands."
private const val GROUP_HELP_MESSAGE =
    "<b>Nuecagram GitLab Notification Gateway</b>\n\n" +
        "Run <code>/setup &lt;gitlab-base-url&gt; &lt;project-id&gt;</code> in this group to bind notifications.\n" +
        "For status, management, and configuration options, open a private chat with the bot."
private const val STATUS_USAGE_MESSAGE = "Usage: <code>/status &lt;installation-id&gt;</code>"
private const val DIGEST_USAGE_MESSAGE = "Usage: <code>/digest &lt;installation-id&gt;</code>"
private const val TEST_USAGE_MESSAGE =
    "Usage: <code>/test &lt;installation-id&gt;</code>\nExample: <code>/test a1b2c3d4</code>"
private const val MUTE_USAGE_MESSAGE = "Usage: <code>/mute &lt;installation-id&gt;</code>"
private const val UNMUTE_USAGE_MESSAGE = "Usage: <code>/unmute &lt;installation-id&gt;</code>"
private const val SETUP_USAGE_MESSAGE =
    "Usage: <code>/setup &lt;gitlab-base-url&gt; &lt;project-id&gt;</code>\n" +
        "Example: <code>/setup https://gitlab.com 12345678</code>"
private const val MANAGE_USAGE_MESSAGE =
    "Usage: <code>/manage &lt;installation-id&gt;</code>\nExample: <code>/manage a1b2c3d4</code>"
private const val ROTATE_USAGE_MESSAGE =
    "Usage: <code>/rotate &lt;installation-id&gt;</code>\nExample: <code>/rotate a1b2c3d4</code>"
private const val WRONG_CHAT_MESSAGE = "Installation not found in this chat."
private const val PRIVATE_COMMAND_MESSAGE = "Run this command in the installation group."
private const val PRIVATE_DELIVERY_MESSAGE = "Private setup details sent."
private const val MANAGEMENT_DM_REDIRECT_MESSAGE =
    "Continue in a private chat with the bot to manage this installation."
private const val MANAGEMENT_DM_URL = "https://t.me/NuecagramBot"
private const val MANAGEMENT_LINK_TTL_MINUTES = 30L
private const val ROTATION_GRACE_MINUTES = 0L
private const val SETUP_ARG_COUNT_MIN = 3
private const val SETUP_ARG_COUNT_MAX = 4
private const val SETUP_URL_INDEX = 1
private const val SETUP_PROJECT_INDEX = 2
private const val SETUP_REPO_NAME_INDEX = 3
private const val LAUNCH_NONCE_TTL_MINUTES = 10L

private data class AuthorizedGroupAdmin(
    val actorId: Long,
    val privateChatId: Long,
    val user: TelegramUpdate.User? = null,
)

private data class AuthorizedInstallationCommand(
    val installation: InstallationAdminContext,
    val actorId: Long,
    val privateChatId: Long,
)

private data class SetupArguments(
    val gitlabBaseUrl: String,
    val projectId: Long,
    val repoName: String?,
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
        telegramService.sendMessage(installation.deliveryTestMessage())
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
            "/status" -> {
                val authorized = authorizeInstallationCommand(message, STATUS_USAGE_MESSAGE) ?: return
                sendLauncherMessage(
                    message.chat.id,
                    message.messageThreadId,
                    authorized.actorId,
                    authorized.installation.statusText(),
                    "Open Web App",
                )
            }
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

    private suspend fun handleDigest(message: TelegramUpdate.Message) {
        val authorized = authorizeInstallationCommand(message, DIGEST_USAGE_MESSAGE) ?: return
        send(message.chat.id, authorized.installation.digestText(), message.messageThreadId)
    }

    private suspend fun handleDeliveryTest(message: TelegramUpdate.Message) {
        val authorized = authorizeInstallationCommand(message, TEST_USAGE_MESSAGE) ?: return
        telegramService.sendMessage(authorized.installation.deliveryTestMessage())
        installationRepository.writeAuditEvent(
            installationId = authorized.installation.id,
            actorType = "telegram",
            actorId = authorized.actorId.toString(),
            action = "telegram_delivery_test",
        )
    }

    private suspend fun handleMute(message: TelegramUpdate.Message) {
        val authorized = authorizeInstallationCommand(message, MUTE_USAGE_MESSAGE) ?: return
        installationRepository.setMuted(authorized.installation.id, true)
        installationRepository.writeAuditEvent(
            installationId = authorized.installation.id,
            actorType = "telegram",
            actorId = authorized.actorId.toString(),
            action = "telegram_mute",
        )
        send(message.chat.id, "Installation muted.", message.messageThreadId)
    }

    private suspend fun handleUnmute(message: TelegramUpdate.Message) {
        val authorized = authorizeInstallationCommand(message, UNMUTE_USAGE_MESSAGE) ?: return
        installationRepository.setMuted(authorized.installation.id, false)
        installationRepository.writeAuditEvent(
            installationId = authorized.installation.id,
            actorType = "telegram",
            actorId = authorized.actorId.toString(),
            action = "telegram_unmute",
        )
        send(message.chat.id, "Installation unmuted.", message.messageThreadId)
    }

    private suspend fun handleSetup(message: TelegramUpdate.Message) {
        val authorized = authorizeGroupAdmin(message, SETUP_USAGE_MESSAGE) ?: return
        val setup = parseSetupArgumentsValue(message.text) ?: run {
            send(message.chat.id, SETUP_USAGE_MESSAGE, message.messageThreadId)
            return
        }
        val installation =
            installationRepository.createInstallation(
                repoName = setup.repoName ?: "Project #${setup.projectId}",
                chatName = message.chat.title,
                gitlabBaseUrl = setup.gitlabBaseUrl,
                gitlabProjectId = setup.projectId,
                telegramChatId = message.chat.id,
                telegramTopicId = message.messageThreadId,
            )
        installationRepository.recordInstallationAdmin(installation.id, authorized.actorId)
        val credential = installationRepository.issueWebhookSecret(installation.id)
        val managementLink = installationRepository.issueManagementLink(installation.id, managementLinkExpiry())
        installationRepository.writeAuditEvent(
            installationId = installation.id,
            actorType = "telegram",
            actorId = authorized.actorId.toString(),
            action = "telegram_setup",
            metadataJson = authorized.user.toAuditMetadataJson(),
        )
        installationRepository.writeAuditEvent(
            installationId = installation.id,
            actorType = "telegram",
            actorId = authorized.actorId.toString(),
            action = "telegram_management_link",
        )
        send(
            authorized.privateChatId,
            setupDetailsText(config, installation, credential.raw, managementLink.raw),
        )
        sendLauncherMessage(
            message.chat.id,
            message.messageThreadId,
            authorized.actorId,
            PRIVATE_DELIVERY_MESSAGE,
            "Open Setup in Web App",
        )
    }

    private suspend fun handleManage(message: TelegramUpdate.Message) {
        val userId = message.from?.id
        val query = parseInstallationQuery(message.text)

        if (message.chat.type == "private" && userId != null && query == null) {
            menuHandler.handlePrivateManage(message, userId, query)
            return
        }

        val authorized = authorizeInstallationCommand(message, MANAGE_USAGE_MESSAGE) ?: return
        val managementLink =
            installationRepository.issueManagementLink(
                authorized.installation.id,
                managementLinkExpiry(),
            )
        installationRepository.writeAuditEvent(
            installationId = authorized.installation.id,
            actorType = "telegram",
            actorId = authorized.actorId.toString(),
            action = "telegram_management_link",
        )
        send(
            authorized.privateChatId,
            managementLinkText(config, authorized.installation.id, managementLink.raw),
        )
        sendLauncherMessage(
            message.chat.id,
            message.messageThreadId,
            authorized.actorId,
            PRIVATE_DELIVERY_MESSAGE,
            "Open Dashboard in Web App",
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

    private suspend fun handleRotate(message: TelegramUpdate.Message) {
        val authorized = authorizeInstallationCommand(message, ROTATE_USAGE_MESSAGE) ?: return
        val credential =
            installationRepository.rotateWebhookSecret(
                installationId = authorized.installation.id,
                graceUntil = Instant.now().plus(ROTATION_GRACE_MINUTES, ChronoUnit.MINUTES),
            )
        val managementLink =
            installationRepository.issueManagementLink(
                authorized.installation.id,
                managementLinkExpiry(),
            )
        installationRepository.writeAuditEvent(
            installationId = authorized.installation.id,
            actorType = "telegram",
            actorId = authorized.actorId.toString(),
            action = "telegram_rotate",
        )
        installationRepository.writeAuditEvent(
            installationId = authorized.installation.id,
            actorType = "telegram",
            actorId = authorized.actorId.toString(),
            action = "telegram_management_link",
        )
        send(
            authorized.privateChatId,
            rotationDetailsText(config, authorized.installation, credential.raw, managementLink.raw),
        )
        sendLauncherMessage(
            message.chat.id,
            message.messageThreadId,
            authorized.actorId,
            PRIVATE_DELIVERY_MESSAGE,
            "Open Dashboard in Web App",
        )
    }

    private suspend fun authorizeGroupAdmin(
        message: TelegramUpdate.Message,
        usageMessage: String,
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
            requireArguments && text.substringAfter(' ', "").isBlank() -> {
                send(message.chat.id, usageMessage, message.messageThreadId)
                null
            }
            userId == null || privateChatId == null -> {
                send(message.chat.id, PRIVATE_BOOTSTRAP_MESSAGE, message.messageThreadId)
                null
            }
            else -> AuthorizedGroupAdmin(userId, privateChatId, message.from)
        }

        return result
    }

    private suspend fun authorizeInstallationCommand(
        message: TelegramUpdate.Message,
        usageMessage: String,
    ): AuthorizedInstallationCommand? {
        val query = parseInstallationQuery(message.text)
        return when {
            message.chat.type != "private" -> {
                sendManagementDmRedirect(message)
                null
            }
            query == null -> {
                send(message.chat.id, usageMessage, message.messageThreadId)
                null
            }
            else -> authorizePrivateInstallationCommand(message, query)
        }
    }

    private suspend fun authorizePrivateInstallationCommand(
        message: TelegramUpdate.Message,
        query: String,
    ): AuthorizedInstallationCommand? {
        val userId = message.from?.id ?: return null
        val installation = installationRepository.findInstallationByQuery(query)
        val status = installation?.let {
            runCatching { telegramService.chatMemberStatus(it.telegramChatId, userId) }.getOrNull()
        }

        return when {
            installation == null -> {
                send(message.chat.id, "Installation not found.", message.messageThreadId)
                null
            }
            !isTelegramAdmin(status) -> {
                send(message.chat.id, TELEGRAM_ADMIN_ONLY_MESSAGE, message.messageThreadId)
                null
            }
            else -> {
                installationRepository.recordInstallationAdmin(installation.id, userId)
                AuthorizedInstallationCommand(
                    installation = installation,
                    actorId = userId,
                    privateChatId = message.chat.id,
                )
            }
        }
    }

    private suspend fun sendManagementDmRedirect(message: TelegramUpdate.Message) {
        val markup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(listOf(InlineKeyboardButton(text = "Open bot DM", url = MANAGEMENT_DM_URL))),
        )
        send(message.chat.id, MANAGEMENT_DM_REDIRECT_MESSAGE, message.messageThreadId, replyMarkup = markup)
    }

    private fun parseInstallationQuery(text: String?): String? =
        text
            ?.substringAfter(' ', "")
            ?.trim()
            ?.takeIf(String::isNotBlank)

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

private fun parseSetupArgumentsValue(text: String?): SetupArguments? {
    val parts = text?.trim()?.split(Regex("\\s+")) ?: return null
    if (parts.size !in SETUP_ARG_COUNT_MIN..SETUP_ARG_COUNT_MAX) return null
    val url = parts[SETUP_URL_INDEX]
    val projectId = parts[SETUP_PROJECT_INDEX].toLongOrNull() ?: return null
    val repoName = parts.getOrNull(SETUP_REPO_NAME_INDEX)?.trim()?.takeIf(String::isNotBlank)
    return SetupArguments(url, projectId, repoName)
}

private fun TelegramUpdate.User?.toAuditMetadataJson(): String =
    this?.let { u ->
        val map = buildMap {
            u.username?.takeIf(String::isNotBlank)?.let { put("username", it) }
            u.firstName?.takeIf(String::isNotBlank)?.let { put("first_name", it) }
            u.lastName?.takeIf(String::isNotBlank)?.let { put("last_name", it) }
        }
        if (map.isNotEmpty()) Json.encodeToString(map) else "{}"
    } ?: "{}"

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

private fun InstallationAdminContext.digestText(): String =
    buildString {
        append("Digest for ")
        append(id)
        append("\nGitLab: ")
        append(gitlabBaseUrl)
        gitlabProjectId?.let {
            append("\nProject: ")
            append(it)
        }
        append("\nMuted: ")
        append(if (muted) "yes" else "no")
    }

private fun InstallationAdminContext.deliveryTestMessage(): Message =
    Message(
        chatId = telegramChatId.toString(),
        text = "Nuecagram delivery test for installation $id.",
        threadId = telegramTopicId,
    )

private fun managementLinkExpiry(): Instant =
    Instant.now().plus(MANAGEMENT_LINK_TTL_MINUTES, ChronoUnit.MINUTES)

private fun ConfigWithSecrets.publicBaseUrl(): String = configuredPublicUrl()

private fun ConfigWithSecrets.webhookUrl(): String = "${publicBaseUrl()}/webhook"

private fun ConfigWithSecrets.managementUrl(code: String): String =
    "${publicBaseUrl()}/manage/$code"

private fun setupDetailsText(
    config: ConfigWithSecrets,
    installation: InstallationRecord,
    credential: String,
    managementCode: String,
): String =
    buildString {
        append("Installation: ")
        append(installation.id)
        append("\nWebhook URL: ")
        append(config.webhookUrl())
        append("\nGitLab secret token: ")
        append(credential)
        append("\nManagement URL: ")
        append(config.managementUrl(managementCode))
    }

private fun managementLinkText(
    config: ConfigWithSecrets,
    installationId: UUID,
    managementCode: String,
): String =
    "Management for $installationId\n${config.managementUrl(managementCode)}"

private fun rotationDetailsText(
    config: ConfigWithSecrets,
    installation: InstallationAdminContext,
    credential: String,
    managementCode: String,
): String =
    buildString {
        append("Rotated installation: ")
        append(installation.id)
        append("\nWebhook URL: ")
        append(config.webhookUrl())
        append("\nGitLab secret token: ")
        append(credential)
        append("\nManagement URL: ")
        append(config.managementUrl(managementCode))
    }
