package net.raquezha.nuecagram.telegram

class TokenProviderImpl(
    private val botToken: TelegramBotToken,
    private val secretToken: SecretToken,
) : TokenProvider {
    class TelegramBotToken(
        val value: String,
    )

    class SecretToken(
        val value: String,
    )

    override fun getBotToken(): String = botToken.value

    override fun getSecretToken(): String = secretToken.value
}
