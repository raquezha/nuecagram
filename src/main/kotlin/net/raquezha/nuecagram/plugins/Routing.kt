package net.raquezha.nuecagram.plugins

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.webhook.EventData
import net.raquezha.nuecagram.webhook.SkipEventException
import net.raquezha.nuecagram.webhook.WebHookService
import net.raquezha.nuecagram.webhook.WebhookMessageFormatter
import org.gitlab4j.api.webhook.BuildEvent
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val webhookService by inject<WebHookService>()
    val logger by inject<KLogger>()

    val requestQueue = Channel<EventData>()

    routing {
        get("/") {
            call.respondText("This application is made to receive webhooks request and send telegram notification")
        }

        post("/webhook") {
            try {
                val webhookData = webhookService.handleRequest(call)
                logger.debug {
                    "handling request webhook data: ${webhookData.log()}"
                }

                requestQueue.send(webhookData)

                call.respond(OK, "Webhook received successfully")
            } catch (skipEx: SkipEventException) {
                call.respond(
                    BadRequest,
                    message = "Request not supported.",
                )
            } catch (e: Exception) {
                call.respond(
                    BadRequest,
                    message = "Failed to process request.\n\n${e.message}",
                )
            }
        }

        this@configureRouting.launch {
            this@configureRouting.processQueue(requestQueue)
        }
    }
}

suspend fun Application.processQueue(queue: Channel<EventData>) {
    val webhookService by inject<WebHookService>()
    val logger by inject<KLogger>()
    val telegramService by inject<TelegramService>()
    val formatter: WebhookMessageFormatter by inject()

    logger.debug { "processing queue" }
    for (data in queue) {
        try {
            val event = data.event
            val chatDetails = data.chatDetails()

            coroutineScope {
                val sendMessageJob =
                    async {
                        telegramService.sendMessage(
                            Message(
                                chatId = chatDetails.chatId,
                                threadId = chatDetails.topicId,
                                messageId =
                                    if (event is BuildEvent) {
                                        webhookService.getMessageIdOfEvent(event.buildId)
                                    } else {
                                        null
                                    },
                                text = formatter.formatEventMessage(event),
                                parseMode = "HTML",
                                disableWebPagePreview = true,
                            ),
                        )
                    }
                val messageId = sendMessageJob.await() // Ensure sendMessage completes before responding
                logger.debug { "sent message $messageId" }

                if (event is BuildEvent) {
                    when {
                        event.buildStatus in listOf("success", "canceled", "failed") -> {
                            webhookService.clearMessageIdOfEvent(event.buildId)
                            logger.debug { "build #${event.buildId} finished, removed saved message id" }
                        }
                        else -> {
                            webhookService.setMessageIdOfEvent(event.buildId, messageId)
                            logger.debug { "saved build #${event.buildId}'s message id $messageId" }
                        }
                    }
                }
            }
        } catch (skipEx: SkipEventException) {
            logger.debug { "skipped event" }
        } catch (e: Exception) {
            logger.error { "Error processing webhook data.\n${e.message}" }
        } finally {
            // delay a bit for the next request
            // delay(300)
        }
    }
    logger.debug { "stopped processing queue" }
}
