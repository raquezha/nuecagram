package net.raquezha.nuecagram.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.Route
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.configuredBasePath
import net.raquezha.nuecagram.telegram.TelegramUpdate
import net.raquezha.nuecagram.telegram.TelegramUpdateHandler
import org.koin.ktor.ext.inject

private const val MAX_UPDATE_SIZE_BYTES = 1_048_576
private const val TELEGRAM_HEADER = "X-Telegram-Bot-Api-Secret-Token"
private val telegramJson = Json { ignoreUnknownKeys = true }

fun Route.telegramRouting(basePath: String) {
    val config by inject<ConfigWithSecrets>()
    val handler by inject<TelegramUpdateHandler>()

    post("$basePath/telegram/webhook") {
        val supplied = call.request.headers[TELEGRAM_HEADER]
        val authenticated =
            supplied != null &&
                MessageDigest.isEqual(supplied.toByteArray(), config.telegramWebhookSecret.toByteArray())
        if (!authenticated) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0
        if (contentLength > MAX_UPDATE_SIZE_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val body = call.receiveText()
        if (body.length > MAX_UPDATE_SIZE_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val update = runCatching { telegramJson.decodeFromString<TelegramUpdate>(body) }.getOrNull()
        if (update == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        handler.handle(update)
        call.respond(HttpStatusCode.OK)
    }
}

fun Application.basePath(): String = configuredBasePath()
