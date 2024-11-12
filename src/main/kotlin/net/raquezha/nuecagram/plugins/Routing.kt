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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import net.raquezha.nuecagram.webhook.EventData
import net.raquezha.nuecagram.webhook.SkipEventException
import net.raquezha.nuecagram.webhook.WebHookService
import net.raquezha.nuecagram.webhook.WebhookRequestHandler
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
            WebhookRequestHandler(this@configureRouting).processQueue(requestQueue)
        }
    }
}
