package net.raquezha.nuecagram.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.raquezha.nuecagram.db.InstallationRepository

class TelegramUpdateHandler(
    private val installationRepository: InstallationRepository,
    private val telegramService: TelegramService,
) {
    suspend fun handle(update: TelegramUpdate) {
        if (!installationRepository.recordTelegramUpdate(update.updateId)) return

        val message = update.message ?: return
        when (message.text?.substringBefore(' ')?.substringBefore('@')) {
            "/start" -> handleStart(message)
            "/hello" -> send(message.chat.id, "Hello. Use /help for available commands.")
            "/help" -> send(message.chat.id, "Use /start in a private chat before group setup.")
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
