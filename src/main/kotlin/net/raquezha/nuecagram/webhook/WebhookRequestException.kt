package net.raquezha.nuecagram.webhook

import io.ktor.http.HttpStatusCode

class WebhookRequestException(
    val status: HttpStatusCode,
    override val message: String,
) : IllegalStateException(message)
