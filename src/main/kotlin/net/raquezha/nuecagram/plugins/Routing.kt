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
import kotlinx.coroutines.launch
import net.raquezha.nuecagram.webhook.SkipEventException
import net.raquezha.nuecagram.webhook.WebHookService
import net.raquezha.nuecagram.webhook.WebhookRequestHandler
import org.koin.core.parameter.parametersOf
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val webhookService by inject<WebHookService>()
    val webhookRequestHandler by inject<WebhookRequestHandler> { parametersOf(this) }
    val logger by inject<KLogger>()

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

                webhookRequestHandler.enqueue(webhookData)

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
            webhookRequestHandler.processQueue()
        }
    }
}
