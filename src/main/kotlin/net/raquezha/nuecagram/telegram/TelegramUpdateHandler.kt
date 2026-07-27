package net.raquezha.nuecagram.telegram

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRepository

private val ADMIN_STATUSES = setOf("creator", "administrator")
private const val PRIVATE_BOOTSTRAP_MESSAGE = "Use /start in a private chat before using admin commands."
private const val ADMIN_ONLY_MESSAGE = "Only Telegram group administrators can use this command."
private const val STATUS_USAGE_MESSAGE = "Usage: /status <installation-id>"
private const val DIGEST_USAGE_MESSAGE = "Usage: /digest <installation-id>"
private const val TEST_USAGE_MESSAGE = "Usage: /test <installation-id>"
private const val MUTE_USAGE_MESSAGE = "Usage: /mute <installation-id>"
private const val UNMUTE_USAGE_MESSAGE = "Usage: /unmute <installation-id>"
private const val WRONG_CHAT_MESSAGE = "Installation not found in this chat."
private const val PRIVATE_COMMAND_MESSAGE = "Run this command in the installation group."

private data class AuthorizedInstallationCommand(
    val installation: InstallationAdminContext,
    val actorId: Long,
)

class TelegramUpdateHandler(
    private val installationRepository: InstallationRepository,
    private val telegramService: TelegramService,
) {
    suspend fun handle(update: TelegramUpdate) {
        if (!installationRepository.recordTelegramUpdate(update.updateId)) return

        val message = update.message ?: return
        val command = message.text?.substringBefore(' ')?.substringBefore('@') ?: return
        when (command) {
            "/start" -> handleStart(message)
            "/hello" -> send(message.chat.id, "Hello. Use /help for available commands.")
            "/help" -> send(message.chat.id, "Use /start in a private chat before group setup.")
            "/status" -> handleStatus(message)
            "/digest" -> handleDigest(message)
            "/test" -> handleDeliveryTest(message)
            "/mute" -> handleMute(message)
            "/unmute" -> handleUnmute(message)
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

    private suspend fun handleStatus(message: TelegramMessage) {
        val authorized = authorizeInstallationCommand(message, STATUS_USAGE_MESSAGE) ?: return
        send(message.chat.id, authorized.installation.statusText())
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

    private suspend fun authorizeInstallationCommand(
        message: TelegramMessage,
        usageMessage: String,
    ): AuthorizedInstallationCommand? {
        var rejectionMessage = PRIVATE_COMMAND_MESSAGE
        var authorized: AuthorizedInstallationCommand? = null

        if (message.chat.type != "private") {
            val installationId =
                message.text
                    ?.substringAfter(' ', "")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }

            if (installationId == null) {
                rejectionMessage = usageMessage
            } else {
                val userId = message.from?.id
                if (userId == null || installationRepository.telegramPrivateChatId(userId) == null) {
                    rejectionMessage = PRIVATE_BOOTSTRAP_MESSAGE
                } else {
                    val status =
                        runCatching { telegramService.chatMemberStatus(message.chat.id, userId) }.getOrNull()
                    if (status !in ADMIN_STATUSES) {
                        rejectionMessage = ADMIN_ONLY_MESSAGE
                    } else {
                        val installation = installationRepository.installationAdminContext(installationId)
                        if (installation == null || installation.telegramChatId != message.chat.id) {
                            rejectionMessage = WRONG_CHAT_MESSAGE
                        } else {
                            authorized =
                                AuthorizedInstallationCommand(
                                    installation = installation,
                                    actorId = userId,
                                )
                        }
                    }
                }
            }
        }

        if (authorized == null) send(message.chat.id, rejectionMessage)
        return authorized
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

    private suspend fun send(chatId: Long, text: String) {
        telegramService.sendMessage(Message(chatId = chatId.toString(), text = text))
    }
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
