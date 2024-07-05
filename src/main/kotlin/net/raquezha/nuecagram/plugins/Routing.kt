package net.raquezha.nuecagram.plugins

import eu.vendeli.tgbot.TelegramBot
import io.github.oshai.kotlinlogging.KLogger
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.webhook.SkipEventException
import net.raquezha.nuecagram.webhook.WebHookService
import net.raquezha.nuecagram.webhook.WebhookMessageFormatter
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val webhookService by inject<WebHookService>()
    val logger by inject<KLogger>()
    val telegramService by inject<TelegramService>()
    val formatter: WebhookMessageFormatter by inject()
    val telegramBot: TelegramBot by inject()

    routing {
        get("/") {
            call.respondText("This application is made to receive webhooks request and send telegram notification")
        }

        post("/nuecagram-bot/webhook") {
            telegramBot.update.parseAndHandle(call.receiveText())
            call.respond(OK)
        }

        post("/webhook") {
            val webhookData =
                try {
                    webhookService.handleRequest(call)
                } catch (e: Exception) {
                    call.respond(BadRequest, e.message.toString())
                    return@post // Exit the coroutine if there's an error
                }

            val event = webhookData.event
            val chatDetails = webhookData.chatDetails()
            logger.debug {
                "handling request webhook data: ${webhookData.log()}"
            }
            coroutineScope {
                val sendMessageJob =
                    async {
                        telegramService.sendMessage(
                            Message(
                                chatId = chatDetails.chatId,
                                threadId = chatDetails.topicId,
                                text = formatter.formatEventMessage(event),
                                parseMode = "HTML",
                                disableWebPagePreview = true,
                            ),
                        )
                    }
                try {
                    sendMessageJob.await() // Ensure sendMessage completes before responding
                    call.respond(OK, "Webhook received successfully")
                } catch (skipEventException: SkipEventException) {
                    call.respond(OK, "Webhook received successfully")
                } catch (exception: Exception) {
                    call.respond(
                        status = BadRequest,
                        message = "Failed to process the request. ${exception.message}",
                    )
                }
            }
        }
    }
}
