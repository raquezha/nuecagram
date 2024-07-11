package net.raquezha.nuecagram.plugins

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.User
import io.ktor.server.application.Application
import kotlinx.coroutines.launch
import net.raquezha.nuecagram.di.SystemEnvImpl

fun Application.configureTelegram() {
    launch {
        val telegramBot = TelegramBot(SystemEnvImpl.getBotApi())
        telegramBot.handleUpdates()
    }
}

@CommandHandler(["/start"])
suspend fun start(
    user: User,
    bot: TelegramBot,
) {
    message {
"""
Hi here! To setup notifications for this chat your GitLab project(repo), open Settings -> Web Hooks and add this URL:
https://nuecagram.raquezha.net

and add this custom headers:
custom_headers": [
    { "key": "X-Nuecagram-Token", "value": "" },
    { "key": "X-Nuecagram-Chat-Id", "value": "Your telegram group chat id" },
    { "key": "X-Nuecagram-Topic-Id", "value": "You telegram topic id" }
]

"X-Nuecagram-Topic-Id is optional. If your group chat enabled topics and want to send the message to a specific topic then add this header"
""".trimIndent()
    }.send(user, bot)
}
