package net.raquezha.nuecagram.telegram

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRecord
import net.raquezha.nuecagram.db.InstallationRepository

private val ADMIN_STATUSES = setOf("creator", "administrator")
private const val PRIVATE_BOOTSTRAP_MESSAGE = "Use /start in a private chat before using admin commands."
private const val ADMIN_ONLY_MESSAGE = "Only Telegram group administrators can use this command."
private const val STATUS_USAGE_MESSAGE = "Usage: /status <installation-id>"
private const val DIGEST_USAGE_MESSAGE = "Usage: /digest <installation-id>"
private const val TEST_USAGE_MESSAGE = "Usage: /test <installation-id>"
private const val MUTE_USAGE_MESSAGE = "Usage: /mute <installation-id>"
private const val UNMUTE_USAGE_MESSAGE = "Usage: /unmute <installation-id>"
private const val SETUP_USAGE_MESSAGE = "Usage: /setup <gitlab-base-url> <project-id> [topic-id]"
private const val MANAGE_USAGE_MESSAGE = "Usage: /manage <installation-id>"
private const val ROTATE_USAGE_MESSAGE = "Usage: /rotate <installation-id>"
private const val WRONG_CHAT_MESSAGE = "Installation not found in this chat."
private const val PRIVATE_COMMAND_MESSAGE = "Run this command in the installation group."
private const val PRIVATE_DELIVERY_MESSAGE = "Private setup details sent."
private const val MANAGEMENT_LINK_TTL_MINUTES = 30L
private const val ROTATION_GRACE_MINUTES = 0L
private const val SETUP_ARG_COUNT_MIN = 3
private const val SETUP_ARG_COUNT_MAX = 4
private const val SETUP_URL_INDEX = 1
private const val SETUP_PROJECT_INDEX = 2
private const val SETUP_TOPIC_INDEX = 3

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
    val topicId: Long?,
)

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

    private suspend fun dispatch(command: String, message: TelegramMessage) {
        when (command) {
            "/start" -> handleStart(message)
            "/hello" -> send(message.chat.id, "Hello. Use /help for available commands.")
            "/help" -> send(message.chat.id, "Use /start in a private chat before group setup.")
            "/status" -> {
                val authorized = authorizeInstallationCommand(message, STATUS_USAGE_MESSAGE) ?: return
                send(message.chat.id, authorized.installation.statusText())
            }
            "/digest" -> handleDigest(message)
            "/test" -> handleDeliveryTest(message)
            "/mute" -> handleMute(message)
            "/unmute" -> handleUnmute(message)
            "/setup" -> handleSetup(message)
            "/manage" -> handleManage(message)
            "/rotate" -> handleRotate(message)
        }
    }

    private suspend fun handleStart(message: TelegramMessage) {
        val userId = message.from?.id
        if (message.chat.type == "private" && userId != null) {
            installationRepository.upsertTelegramPrivateChat(userId, message.chat.id)
            send(message.chat.id, "Private onboarding is ready. Return to your group to continue.")
        } else {
            send(message.chat.id, "Start a private chat with the bot first.")
        }
    }

    private suspend fun handleDigest(message: TelegramMessage) {
        val authorized = authorizeInstallationCommand(message, DIGEST_USAGE_MESSAGE) ?: return
        send(message.chat.id, authorized.installation.digestText())
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
        send(message.chat.id, "Installation muted.")
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
        send(message.chat.id, "Installation unmuted.")
    }

    private suspend fun handleSetup(message: TelegramMessage) {
        val authorized = authorizeGroupAdmin(message, SETUP_USAGE_MESSAGE) ?: return
        val setup = parseSetupArgumentsValue(message.text) ?: run {
            send(message.chat.id, SETUP_USAGE_MESSAGE)
            return
        }
        val installation =
            installationRepository.createInstallation(
                gitlabBaseUrl = setup.gitlabBaseUrl,
                gitlabProjectId = setup.projectId,
                telegramChatId = message.chat.id,
                telegramTopicId = setup.topicId,
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
        send(message.chat.id, PRIVATE_DELIVERY_MESSAGE)
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
        send(message.chat.id, PRIVATE_DELIVERY_MESSAGE)
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
        send(message.chat.id, PRIVATE_DELIVERY_MESSAGE)
    }

    private suspend fun authorizeGroupAdmin(
        message: TelegramMessage,
        usageMessage: String,
    ): AuthorizedGroupAdmin? {
        if (message.chat.type == "private") {
            send(message.chat.id, PRIVATE_COMMAND_MESSAGE)
            return null
        }
        val text = message.text?.trim().orEmpty()
        if (text.substringAfter(' ', "").isBlank()) {
            send(message.chat.id, usageMessage)
            return null
        }
        val userId = message.from?.id
        val privateChatId = if (userId == null) null else installationRepository.telegramPrivateChatId(userId)
        if (userId == null || privateChatId == null) {
            send(message.chat.id, PRIVATE_BOOTSTRAP_MESSAGE)
            return null
        }
        val status = runCatching { telegramService.chatMemberStatus(message.chat.id, userId) }.getOrNull()
        if (status !in ADMIN_STATUSES) {
            send(message.chat.id, ADMIN_ONLY_MESSAGE)
            return null
        }
        return AuthorizedGroupAdmin(userId, privateChatId)
    }

    private suspend fun authorizeInstallationCommand(
        message: TelegramMessage,
        usageMessage: String,
    ): AuthorizedInstallationCommand? {
        val installationId = parseInstallationId(message.text)
        if (installationId == null) {
            send(message.chat.id, usageMessage)
            return null
        }
        val groupAdmin = authorizeGroupAdmin(message, usageMessage) ?: return null
        val installation = installationRepository.installationAdminContext(installationId)
        if (installation == null || installation.telegramChatId != message.chat.id) {
            send(message.chat.id, WRONG_CHAT_MESSAGE)
            return null
        }
        return AuthorizedInstallationCommand(
            installation = installation,
            actorId = groupAdmin.actorId,
            privateChatId = groupAdmin.privateChatId,
        )
    }

    private fun parseInstallationId(text: String?): UUID? =
        text
            ?.substringAfter(' ', "")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }

    private suspend fun send(chatId: Long, text: String) {
        telegramService.sendMessage(Message(chatId = chatId.toString(), text = text))
    }
}

private fun parseSetupArgumentsValue(text: String?): SetupArguments? {
    val parts = text?.trim()?.split(Regex("\\s+")) ?: return null
    if (parts.size !in SETUP_ARG_COUNT_MIN..SETUP_ARG_COUNT_MAX) return null
    val projectId = parts[SETUP_PROJECT_INDEX].toLongOrNull() ?: return null
    val topicId = parts.getOrNull(SETUP_TOPIC_INDEX)?.toLongOrNull()
    return SetupArguments(parts[SETUP_URL_INDEX], projectId, topicId)
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

private fun ConfigWithSecrets.publicBaseUrl(): String {
    val host = host.removeSuffix("/")
    return when {
        host.startsWith("http://") || host.startsWith("https://") -> host
        host == "localhost" -> "http://$host:$port"
        else -> "https://$host"
    }
}

private fun ConfigWithSecrets.webhookUrl(): String = "${publicBaseUrl()}/webhook"

private fun ConfigWithSecrets.managementUrl(code: String): String = "${publicBaseUrl()}/nuecagram/manage/$code"

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
