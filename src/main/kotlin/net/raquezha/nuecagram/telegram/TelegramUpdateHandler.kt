package net.raquezha.nuecagram.telegram

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.configuredPublicUrl
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRecord
import net.raquezha.nuecagram.db.InstallationRepository

private val ADMIN_STATUSES = setOf("creator", "administrator")
private const val PRIVATE_BOOTSTRAP_MESSAGE = "Use /start in a private chat before using admin commands."
private const val ADMIN_ONLY_MESSAGE = "Only Telegram group administrators can use this command."
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

        val message = update.message ?: return
        val command = message.text?.substringBefore(' ')?.substringBefore('@') ?: return
        dispatch(command, message)
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun dispatch(command: String, message: TelegramMessage) {
        when (command) {
            "/start" -> handleStart(message)
            "/hello" -> send(message.chat.id, "Hello. Use /help for available commands.", message.messageThreadId)
            "/help" -> {
                val userId = message.from?.id
                val markup = if (userId != null) {
                    webAppLauncherMarkup(message.chat.id, message.messageThreadId, userId, "Open Web App")
                } else null
                send(
                    message.chat.id,
                    HELP_MESSAGE,
                    message.messageThreadId,
                    replyMarkup = markup,
                )
            }
            "/status" -> {
                val authorized = authorizeInstallationCommand(message, STATUS_USAGE_MESSAGE) ?: return
                val markup = webAppLauncherMarkup(
                    message.chat.id,
                    message.messageThreadId,
                    authorized.actorId,
                    "Open Web App",
                )
                send(
                    message.chat.id,
                    authorized.installation.statusText(),
                    message.messageThreadId,
                    replyMarkup = markup,
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

    private suspend fun handleStart(message: TelegramMessage) {
        val userId = message.from?.id
        if (message.chat.type == "private" && userId != null) {
            installationRepository.upsertTelegramPrivateChat(userId, message.chat.id)
            val markup = webAppLauncherMarkup(message.chat.id, null, userId, "Open Management Dashboard")
            send(
                message.chat.id,
                "Private onboarding is ready. Return to your group to continue.",
                replyMarkup = markup,
            )
        } else {
            send(message.chat.id, "Start a private chat with the bot first.", message.messageThreadId)
        }
    }

    private suspend fun handleDigest(message: TelegramMessage) {
        val authorized = authorizeInstallationCommand(message, DIGEST_USAGE_MESSAGE) ?: return
        send(message.chat.id, authorized.installation.digestText(), message.messageThreadId)
    }

    private suspend fun handleDeliveryTest(message: TelegramMessage) {
        val authorized = authorizeInstallationCommand(message, TEST_USAGE_MESSAGE) ?: return
        telegramService.sendMessage(authorized.installation.deliveryTestMessage())
        installationRepository.writeAuditEvent(
            installationId = authorized.installation.id,
            actorType = "telegram",
            actorId = authorized.actorId.toString(),
            action = "telegram_delivery_test",
        )
    }

    private suspend fun handleMute(message: TelegramMessage) {
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

    private suspend fun handleUnmute(message: TelegramMessage) {
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

    private suspend fun handleSetup(message: TelegramMessage) {
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
        val markup = webAppLauncherMarkup(
            message.chat.id,
            message.messageThreadId,
            authorized.actorId,
            "Open Setup in Web App",
        )
        send(message.chat.id, PRIVATE_DELIVERY_MESSAGE, message.messageThreadId, replyMarkup = markup)
    }

    private suspend fun handleManage(message: TelegramMessage) {
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
        val markup = webAppLauncherMarkup(
            message.chat.id,
            message.messageThreadId,
            authorized.actorId,
            "Open Dashboard in Web App",
        )
        send(message.chat.id, PRIVATE_DELIVERY_MESSAGE, message.messageThreadId, replyMarkup = markup)
    }

    private suspend fun handleWebApp(message: TelegramMessage) {
        val groupAdmin =
            authorizeGroupAdmin(message, "Usage: <code>/webapp</code>", requireArguments = false) ?: return
        val markup = webAppLauncherMarkup(
            message.chat.id,
            message.messageThreadId,
            groupAdmin.actorId,
            "Open Nuecagram Web App",
        )
        installationRepository.writeAuditEvent(
            installationId = null,
            actorType = "telegram",
            actorId = groupAdmin.actorId.toString(),
            action = "telegram_webapp_launch",
        )
        send(
            message.chat.id,
            "Open Nuecagram Web App:",
            message.messageThreadId,
            replyMarkup = markup,
        )
    }

    private suspend fun handleRotate(message: TelegramMessage) {
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
        val markup = webAppLauncherMarkup(
            message.chat.id,
            message.messageThreadId,
            authorized.actorId,
            "Open Dashboard in Web App",
        )
        send(message.chat.id, PRIVATE_DELIVERY_MESSAGE, message.messageThreadId, replyMarkup = markup)
    }

    private suspend fun authorizeGroupAdmin(
        message: TelegramMessage,
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
            requireArguments && text.substringAfter(' ', "").isBlank() -> {
                send(message.chat.id, usageMessage, message.messageThreadId)
                null
            }
            userId == null || privateChatId == null -> {
                send(message.chat.id, PRIVATE_BOOTSTRAP_MESSAGE, message.messageThreadId)
                null
            }
            status !in ADMIN_STATUSES -> {
                send(message.chat.id, ADMIN_ONLY_MESSAGE, message.messageThreadId)
                null
            }
            else -> AuthorizedGroupAdmin(userId, privateChatId)
        }

        return result
    }

    private suspend fun authorizeInstallationCommand(
        message: TelegramMessage,
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
                installationRepository.findInstallationByQuery(query, message.chat.id)
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
            else -> AuthorizedInstallationCommand(
                installation = installation,
                actorId = groupAdmin.actorId,
                privateChatId = groupAdmin.privateChatId,
            )
        }

        return result
    }

    private fun parseInstallationQuery(text: String?): String? =
        text
            ?.substringAfter(' ', "")
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private suspend fun webAppLauncherMarkup(
        chatId: Long,
        threadId: Long?,
        userId: Long,
        buttonText: String = "Open Nuecagram Web App",
    ): InlineKeyboardMarkup {
        val nonce = installationRepository.issueLaunchNonce(
            telegramChatId = chatId,
            telegramTopicId = threadId,
            telegramUserId = userId,
            expiresAt = Instant.now().plus(LAUNCH_NONCE_TTL_MINUTES, ChronoUnit.MINUTES),
        )
        val url = "${config.publicBaseUrl()}/webapp?startapp=nonce_${nonce.raw}"
        return InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(
                        text = buttonText,
                        webApp = WebAppInfo(url = url),
                    ),
                ),
            ),
        )
    }

    private suspend fun send(
        chatId: Long,
        text: String,
        threadId: Long? = null,
        replyMarkup: InlineKeyboardMarkup? = null,
    ) {
        val attempts = listOfNotNull(
            Message(chatId = chatId.toString(), text = text, threadId = threadId, replyMarkup = replyMarkup),
            Message(
                chatId = chatId.toString(),
                text = text,
                threadId = threadId,
                parseMode = "",
                replyMarkup = replyMarkup,
            ),
            if (threadId != null) {
                Message(
                    chatId = chatId.toString(),
                    text = text,
                    threadId = null,
                    parseMode = "",
                    replyMarkup = replyMarkup,
                )
            } else {
                null
            },
        )

        attempts.firstNotNullOfOrNull { msg ->
            runCatching { telegramService.sendMessage(msg) }.getOrNull()
        }

        return
    }
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
        append("\nGitLab credential: ")
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
        append("\nGitLab credential: ")
        append(credential)
        append("\nManagement URL: ")
        append(config.managementUrl(managementCode))
    }

@Serializable
data class TelegramUpdate(
    @SerialName("update_id")
    val updateId: Long,
    val message: TelegramMessage? = null,
)

@Serializable
data class TelegramMessage(
    val text: String? = null,
    val chat: TelegramChat,
    val from: TelegramUser? = null,
    @SerialName("message_thread_id")
    val messageThreadId: Long? = null,
)

@Serializable
data class TelegramChat(
    val id: Long,
    val type: String,
)

@Serializable
data class TelegramUser(
    val id: Long,
)
