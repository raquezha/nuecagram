package net.raquezha.nuecagram

data class ConfigWithSecrets(
    val name: String,
    val env: String,
    val host: String,
    val port: Int,
    val botApi: String,
    val telegramWebhookSecret: String,
    val platformAdminHash: String,
)
