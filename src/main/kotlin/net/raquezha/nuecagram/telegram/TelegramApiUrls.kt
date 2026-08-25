package net.raquezha.nuecagram.telegram

import java.net.URL

object TelegramApiUrls {
    private const val BASE_TELEGRAM_BOT_URL = "https://api.telegram.org/bot"

    fun sendMessageUrl(botToken: String): URL = URL("$BASE_TELEGRAM_BOT_URL$botToken/sendMessage")

    fun editMessageUrl(botToken: String): URL = URL("$BASE_TELEGRAM_BOT_URL$botToken/editMessageText")

    fun answerCallbackQueryUrl(botToken: String): URL = URL("$BASE_TELEGRAM_BOT_URL$botToken/answerCallbackQuery")

    fun getChatMemberUrl(botToken: String, chatId: Long, userId: Long): String =
        "$BASE_TELEGRAM_BOT_URL$botToken/getChatMember?chat_id=$chatId&user_id=$userId"
}
