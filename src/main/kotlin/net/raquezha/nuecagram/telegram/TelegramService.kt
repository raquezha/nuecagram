package net.raquezha.nuecagram.telegram

interface TelegramService {
    suspend fun getMe(): TelegramUser?

    suspend fun setWebhook(
        url: String,
        headerToken: String? = null,
    ): Boolean

    suspend fun setChatMenuButton(
        menuButton: MenuButton? = null,
    ): Boolean

    suspend fun setMyCommands(commands: List<BotCommand>): Boolean

    suspend fun sendMessage(message: Message): String

    suspend fun chatMemberStatus(
        chatId: Long,
        userId: Long,
    ): String?

    suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false,
    ): Boolean
}
