package net.raquezha.nuecagram.telegram

import java.net.URL

interface TelegramService {
    fun getURLSendMessage(botToken: String): URL = URL("https://api.telegram.org/bot$botToken/sendMessage")

    fun getURLEditMessage(botToken: String): URL = URL("https://api.telegram.org/bot$botToken/editMessageText")

    suspend fun sendMessage(message: Message): String
}
