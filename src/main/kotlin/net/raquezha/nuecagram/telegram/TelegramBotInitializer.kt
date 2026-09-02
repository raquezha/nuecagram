package net.raquezha.nuecagram.telegram

import io.github.oshai.kotlinlogging.KLogger

interface TelegramBotInitializer {
    suspend fun initialize(
        publicUrl: String,
        appHeader: String,
    )
}

class TelegramBotInitializerImpl(
    private val telegramService: TelegramService,
    private val logger: KLogger,
) : TelegramBotInitializer {
    override suspend fun initialize(
        publicUrl: String,
        appHeader: String,
    ) {
        validateBotToken()
        configureBotCommands()
        configureWebhookUrl(publicUrl, appHeader)
        configureChatMenuButton(publicUrl)
    }

    private suspend fun configureBotCommands() = runCatching {
        telegramService.setMyCommands(DEFAULT_BOT_COMMANDS)
    }.onFailure { logger.warn(it) { "Failed to configure Telegram bot commands" } }

    private suspend fun validateBotToken() = runCatching {
        telegramService.getMe()?.let { user ->
            logger.info { "Telegram bot API validated for bot @${user.username ?: user.firstName}" }
        } ?: logger.warn { "Telegram bot API validation returned null" }
    }.onFailure { logger.warn(it) { "Failed to validate Telegram bot API on startup" } }

    private suspend fun configureWebhookUrl(
        publicUrl: String,
        appHeader: String,
    ) = runCatching {
        telegramService.setWebhook(
            "$publicUrl/telegram/webhook",
            appHeader,
        )
    }.onFailure { logger.warn(it) { "Failed to auto-sync Telegram webhook URL on startup" } }

    private suspend fun configureChatMenuButton(publicUrl: String) = runCatching {
        telegramService.setChatMenuButton(
            MenuButton(
                type = "web_app",
                text = "OPEN",
                webApp = WebAppInfo(url = "$publicUrl/webapp"),
            ),
        )
    }.onFailure { logger.warn(it) { "Failed to auto-sync Telegram chat menu button on startup" } }

    companion object {
        private val DEFAULT_BOT_COMMANDS = listOf(
            BotCommand("manage", "View and manage connected repositories"),
            BotCommand("status", "Choose a repository and view status"),
            BotCommand("test", "Choose a repository and send a test"),
            BotCommand("rotate", "Choose a repository and rotate secret"),
            BotCommand("mute", "Choose a repository and pause notifications"),
            BotCommand("unmute", "Choose a repository and resume notifications"),
            BotCommand("digest", "Choose a repository and view summary"),
            BotCommand("setup", "How to bind a new GitLab repository"),
            BotCommand("help", "View command reference and instructions"),
        )
    }
}
