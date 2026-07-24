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
private const val WRONG_CHAT_MESSAGE = "Installation not found in this chat."
private const val PRIVATE_COMMAND_MESSAGE = "Run this command in the installation group."

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
        if (message.chat.type == "private") {
            send(message.chat.id, PRIVATE_COMMAND_MESSAGE)
            return
        }

        val installationId =
            message.text
                ?.substringAfter(' ', "")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
        if (installationId == null) {
            send(message.chat.id, STATUS_USAGE_MESSAGE)
            return
        }

        val userId = message.from?.id
        if (userId == null || installationRepository.telegramPrivateChatId(userId) == null) {
            send(message.chat.id, PRIVATE_BOOTSTRAP_MESSAGE)
            return
        }

        val status = runCatching { telegramService.chatMemberStatus(message.chat.id, userId) }.getOrNull()
        if (status !in ADMIN_STATUSES) {
            send(message.chat.id, ADMIN_ONLY_MESSAGE)
            return
        }

        val installation = installationRepository.installationAdminContext(installationId)
        if (installation == null || installation.telegramChatId != message.chat.id) {
            send(message.chat.id, WRONG_CHAT_MESSAGE)
            return
        }

        send(message.chat.id, installation.statusText())
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
