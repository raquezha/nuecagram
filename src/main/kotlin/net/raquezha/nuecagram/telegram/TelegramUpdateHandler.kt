@file:Suppress("TooManyFunctions")

package net.raquezha.nuecagram.telegram

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.configuredPublicUrl
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRecord
import net.raquezha.nuecagram.db.InstallationRepository

private const val PRIVATE_BOOTSTRAP_MESSAGE = "Use /start in a private chat before using admin commands."
private const val HELP_MESSAGE =
    "<b>Nuecagram GitLab Notification Gateway</b>\n\n" +
        "1. First-time setup:\n" +
        " - Send <code>/start</code> in a private DM with the bot.\n" +
        " - Add bot as Admin to your Telegram group or topic.\n" +
        " - Run <code>/setup &lt;gitlab-url&gt; &lt;project-id&gt;</code> in your group/topic.\n\n" +
        "2. Group commands:\n" +
        " - <code>/status &lt;inst-id&gt;</code> : View installation status\n" +
        " - <code>/test &lt;inst-id&gt;</code> : Send test notification\n" +
        " - <code>/manage &lt;inst-id&gt;</code> : Web management link\n" +
        " - <code>/rotate &lt;inst-id&gt;</code> : Rotate webhook secret\n" +
        " - <code>/mute &lt;inst-id&gt;</code> : Pause notifications\n" +
        " - <code>/unmute &lt;inst-id&gt;</code> : Resume notifications"
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
private const val MANAGEMENT_LINK_TTL_MINUTES = 30L
private const val ROTATION_GRACE_MINUTES = 0L
private const val SETUP_ARG_COUNT_MIN = 3
private const val SETUP_ARG_COUNT_MAX = 3
private const val SETUP_URL_INDEX = 1
private const val SETUP_PROJECT_INDEX = 2
private const val LAUNCH_NONCE_TTL_MINUTES = 10L
private const val PAGE_SIZE = 8
private const val DISPLAY_URL_MAX_LENGTH = 20
private const val SHORT_ID_LENGTH = 8

private data class AuthorizedGroupAdmin(
    val actorId: Long,
    val privateChatId: Long,
)

private data class AuthorizedInstallationCommand(
    val installation: InstallationAdminContext,
    val actorId: Long,
    val privateChatId: Long,
)

private data class SetupArguments(
    val gitlabBaseUrl: String,
    val projectId: Long,
)

@Suppress("TooManyFunctions")
class TelegramUpdateHandler(
    private val installationRepository: InstallationRepository,
    private val telegramService: TelegramService,
    private val config: ConfigWithSecrets,
) {
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
            handlePrivateCallbackQuery(callbackQuery, message, payload, userId)
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

    private suspend fun handlePrivateCallbackQuery(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        payload: TelegramCallbackPayload,
        userId: Long,
    ) {
        when (payload.action) {
            "list" -> handlePrivateListCallback(callbackQuery, message, userId, payload.targetId)
            "menu" -> handlePrivateMenuCallback(callbackQuery, message, userId, payload.targetId)
            "status" -> handlePrivateStatusCallback(callbackQuery, userId, payload.targetId)
            "test" -> handlePrivateTestCallback(callbackQuery, userId, payload.targetId)
            "digest" -> handlePrivateDigestCallback(callbackQuery, userId, payload.targetId)
            "mute" -> handlePrivateMuteCallback(callbackQuery, message, userId, payload.targetId, muted = true)
            "unmute" -> handlePrivateMuteCallback(callbackQuery, message, userId, payload.targetId, muted = false)
            "rotate:confirm" -> handlePrivateRotateConfirmCallback(callbackQuery, message, userId, payload.targetId)
            "rotate:execute" -> handlePrivateRotateExecuteCallback(callbackQuery, message, userId, payload.targetId)
            "back" -> handlePrivateBackCallback(callbackQuery, message, userId, payload.targetId)
            else -> telegramService.answerCallbackQuery(callbackQuery.id, "Unknown callback action.")
        }
    }

    private suspend fun handlePrivateListCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        targetId: String,
    ) {
        val page = parsePageIndex(targetId)
        val adminInstalls = installationRepository.installationsForAdmin(userId)
        if (adminInstalls.isEmpty()) {
            telegramService.answerCallbackQuery(callbackQuery.id, "No installations found.", showAlert = true)
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

    private suspend fun handlePrivateMenuCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        targetId: String,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            telegramService.answerCallbackQuery(callbackQuery.id, "Installation not found.", showAlert = true)
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
            telegramService.answerCallbackQuery(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        telegramService.answerCallbackQuery(callbackQuery.id, inst.statusText(), showAlert = true)
    }

    private suspend fun handlePrivateTestCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        userId: Long,
        targetId: String,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            telegramService.answerCallbackQuery(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        telegramService.sendMessage(inst.deliveryTestMessage())
        installationRepository.writeAuditEvent(
            installationId = inst.id,
            actorType = "telegram",
            actorId = userId.toString(),
            action = "telegram_delivery_test",
        )
        telegramService.answerCallbackQuery(callbackQuery.id, "Test notification sent.")
    }

    private suspend fun handlePrivateDigestCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        userId: Long,
        targetId: String,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            telegramService.answerCallbackQuery(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        telegramService.answerCallbackQuery(callbackQuery.id, inst.digestText(), showAlert = true)
    }

    private suspend fun handlePrivateMuteCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        targetId: String,
        muted: Boolean,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            telegramService.answerCallbackQuery(callbackQuery.id, "Installation not found.", showAlert = true)
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
            callbackQuery.id,
            if (muted) "Installation muted." else "Installation unmuted.",
        )
    }

    private suspend fun handlePrivateRotateConfirmCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        targetId: String,
    ) {
        val inst = findAdminInstallation(userId, targetId) ?: run {
            telegramService.answerCallbackQuery(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        val text =
            "⚠️ <b>Rotate Webhook Secret</b>\n\n" +
                "Are you sure you want to rotate the webhook secret for installation <code>${inst.id}</code>?\n\n" +
                "The existing secret will stop working immediately."
        val markup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(
                        text = "⚠️ Yes, Rotate Secret",
                        callbackData = "inst:rotate:execute:${inst.id}",
                    ),
                ),
                listOf(
                    InlineKeyboardButton(
                        text = "Cancel",
                        callbackData = "inst:menu:${inst.id}",
                    ),
                ),
            ),
        )
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
            telegramService.answerCallbackQuery(callbackQuery.id, "Installation not found.", showAlert = true)
            return
        }
        val credential = installationRepository.rotateWebhookSecret(
            installationId = inst.id,
            graceUntil = Instant.now().plus(ROTATION_GRACE_MINUTES, ChronoUnit.MINUTES),
        )
        val managementLink = installationRepository.issueManagementLink(
            inst.id,
            managementLinkExpiry(),
        )
        installationRepository.writeAuditEvent(
            installationId = inst.id,
            actorType = "telegram",
            actorId = userId.toString(),
            action = "telegram_rotate",
        )
        installationRepository.writeAuditEvent(
            installationId = inst.id,
            actorType = "telegram",
            actorId = userId.toString(),
            action = "telegram_management_link",
        )
        val text = rotationDetailsText(config, inst, credential.raw, managementLink.raw)
        val markup = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(
                        text = "Back to Menu",
                        callbackData = "inst:menu:${inst.id}",
                    ),
                ),
            ),
        )
        send(
            chatId = message.chat.id,
            text = text,
            replyMarkup = markup,
            messageId = message.messageId?.toString(),
        )
        telegramService.answerCallbackQuery(callbackQuery.id, "Secret rotated.")
    }

    private suspend fun handlePrivateBackCallback(
        callbackQuery: TelegramUpdate.CallbackQuery,
        message: TelegramUpdate.Message,
        userId: Long,
        targetId: String,
    ) {
        val page = parsePageIndex(targetId)
        val adminInstalls = installationRepository.installationsForAdmin(userId)
        if (adminInstalls.isEmpty()) {
            telegramService.answerCallbackQuery(callbackQuery.id, "No installations found.", showAlert = true)
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
            "/help" -> {
                val userId = message.from?.id
                if (userId != null) {
                    sendLauncherMessage(
                        message.chat.id,
                        message.messageThreadId,
                        userId,
                        HELP_MESSAGE,
                        "Open Web App",
                    )
                } else {
                    send(message.chat.id, HELP_MESSAGE, message.messageThreadId)
                }
            }
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

        if (message.chat.type == "private" && userId != null) {
            handlePrivateManage(message, userId, query)
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

    private suspend fun handlePrivateManage(
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
                send(message.chat.id, managementLinkText(config, inst.id, managementLink.raw))
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
            else -> AuthorizedGroupAdmin(userId, privateChatId)
        }

        return result
    }

    private suspend fun authorizeInstallationCommand(
        message: TelegramUpdate.Message,
        usageMessage: String,
    ): AuthorizedInstallationCommand? {
        val query = parseInstallationQuery(message.text)
        val groupAdmin =
            if (query != null && message.chat.type != "private") {
                authorizeGroupAdmin(message, usageMessage)
            } else {
                null
            }
        val installation =
            if (groupAdmin != null && query != null) {
                installationRepository.findInstallationByQuery(query, message.chat.id, message.messageThreadId)
            } else {
                null
            }

        val result = when {
            message.chat.type == "private" -> {
                send(message.chat.id, PRIVATE_COMMAND_MESSAGE, message.messageThreadId)
                null
            }
            query == null -> {
                send(message.chat.id, usageMessage, message.messageThreadId)
                null
            }
            groupAdmin == null -> null
            installation == null -> {
                send(message.chat.id, WRONG_CHAT_MESSAGE, message.messageThreadId)
                null
            }
            else -> {
                installationRepository.recordInstallationAdmin(installation.id, groupAdmin.actorId)
                AuthorizedInstallationCommand(
                    installation = installation,
                    actorId = groupAdmin.actorId,
                    privateChatId = groupAdmin.privateChatId,
                )
            }
        }

        return result
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

    private fun buildInstallationListMarkup(
        installations: List<InstallationAdminContext>,
        page: Int,
    ): Pair<String, InlineKeyboardMarkup> {
        val totalPages = (installations.size + PAGE_SIZE - 1) / PAGE_SIZE
        val validPage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        val startIndex = validPage * PAGE_SIZE
        val pageItems = installations.subList(
            startIndex,
            minOf(startIndex + PAGE_SIZE, installations.size),
        )

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
            val navButtons = mutableListOf<InlineKeyboardButton>()
            if (validPage > 0) {
                navButtons += InlineKeyboardButton(
                    text = "⬅️ Prev",
                    callbackData = "inst:list:page=${validPage - 1}",
                )
            }
            navButtons += InlineKeyboardButton(
                text = "${validPage + 1}/$totalPages",
                callbackData = "inst:list:page=$validPage",
            )
            if (validPage < totalPages - 1) {
                navButtons += InlineKeyboardButton(
                    text = "Next ➡️",
                    callbackData = "inst:list:page=${validPage + 1}",
                )
            }
            rows += navButtons
        }

        val text = "<b>Select an installation to manage:</b>"
        return text to InlineKeyboardMarkup(inlineKeyboard = rows)
    }

    private fun buildSubmenuMarkup(
        inst: InstallationAdminContext,
    ): Pair<String, InlineKeyboardMarkup> {
        val webAppUrl = "${config.publicBaseUrl()}/webapp?startapp=inst_${inst.id.toString().take(SHORT_ID_LENGTH)}"
        val rows = listOf(
            listOf(
                InlineKeyboardButton(text = "Status", callbackData = "inst:status:${inst.id}"),
                InlineKeyboardButton(text = "Test", callbackData = "inst:test:${inst.id}"),
            ),
            listOf(
                InlineKeyboardButton(
                    text = if (inst.muted) "Unmute" else "Mute",
                    callbackData = "inst:${if (inst.muted) "unmute" else "mute"}:${inst.id}",
                ),
                InlineKeyboardButton(text = "Digest", callbackData = "inst:digest:${inst.id}"),
            ),
            listOf(
                InlineKeyboardButton(text = "Rotate Secret", callbackData = "inst:rotate:confirm:${inst.id}"),
                InlineKeyboardButton(text = "Open Web App", webApp = WebAppInfo(url = webAppUrl)),
            ),
            listOf(
                InlineKeyboardButton(text = "⬅️ Back", callbackData = "inst:back:page=0"),
            ),
        )
        return inst.submenuText() to InlineKeyboardMarkup(inlineKeyboard = rows)
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

private fun parsePageIndex(targetId: String): Int =
    when {
        targetId.startsWith("page=") -> targetId.removePrefix("page=").toIntOrNull() ?: 0
        else -> targetId.toIntOrNull() ?: 0
    }

private fun InstallationAdminContext.submenuText(): String =
    buildString {
        append("<b>Installation:</b> <code>")
        append(id)
        append("</code>\nGitLab: ")
        append(gitlabBaseUrl)
        gitlabProjectId?.let {
            append("\nProject: ")
            append(it)
        }
        telegramTopicId?.let {
            append("\nTopic: ")
            append(it)
        }
        append("\nStatus: ")
        append(if (muted) "<b>Muted</b> 🔕" else "<b>Active</b> 🔔")
    }

private fun parseSetupArgumentsValue(text: String?): SetupArguments? {
    val parts = text?.trim()?.split(Regex("\\s+")) ?: return null
    if (parts.size !in SETUP_ARG_COUNT_MIN..SETUP_ARG_COUNT_MAX) return null
    val projectId = parts[SETUP_PROJECT_INDEX].toLongOrNull() ?: return null
    return SetupArguments(parts[SETUP_URL_INDEX], projectId)
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
