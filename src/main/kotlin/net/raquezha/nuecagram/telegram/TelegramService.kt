package net.raquezha.nuecagram.telegram

import java.net.URL

interface TelegramService {
    fun getURLSendMessage(botToken: String): URL = URL("https://api.telegram.org/bot$botToken/sendMessage")

    fun getURLEditMessage(botToken: String): URL = URL("https://api.telegram.org/bot$botToken/editMessageText")

    fun getURLAnswerCallbackQuery(botToken: String): URL =
        URL("https://api.telegram.org/bot$botToken/answerCallbackQuery")

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
