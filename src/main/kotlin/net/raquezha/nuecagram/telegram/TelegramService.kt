package net.raquezha.nuecagram.telegram

import java.net.URL

interface TelegramService {
    fun getURLSendMessage(botToken: String): URL = TelegramApiUrls.sendMessageUrl(botToken)

    fun getURLEditMessage(botToken: String): URL = TelegramApiUrls.editMessageUrl(botToken)

    fun getURLAnswerCallbackQuery(botToken: String): URL =
        TelegramApiUrls.answerCallbackQueryUrl(botToken)

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
