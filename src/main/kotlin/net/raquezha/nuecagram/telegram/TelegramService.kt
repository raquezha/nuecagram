package net.raquezha.nuecagram.telegram

import java.net.URL

interface TelegramService {
    fun getURLSendMessage(botToken: String): URL = URL("https://api.telegram.org/bot$botToken/sendMessage")

    suspend fun sendMessage(message: Message)
}
