package net.raquezha.nuecagram.di

object SystemEnvImpl : SystemEnv {
    override fun getBotApi(): String =
        System.getenv("TELEGRAM_BOT_TOKEN")
            ?: throw IllegalStateException("TELEGRAM_BOT_TOKEN environment variable is not set")

    override fun getSecretToken(): String =
        System.getenv("NUECAGRAM_SECRET_TOKEN")
            ?: throw IllegalStateException("NUECAGRAM_SECRET_TOKEN environment variable is not set")
}
