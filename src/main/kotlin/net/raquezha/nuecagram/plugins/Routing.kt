package net.raquezha.nuecagram.plugins

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.raquezha.nuecagram.webhook.SkipEventException
import net.raquezha.nuecagram.webhook.WebHookService
import net.raquezha.nuecagram.webhook.WebhookRequestHandler
import org.koin.core.parameter.parametersOf
import org.koin.ktor.ext.inject

private const val QUEUE_RESTART_DELAY_MS = 5000L
private const val CLEANUP_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

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
                // Skipped events are valid - return 200 OK (not an error)
                call.respond(OK, "Event skipped: not relevant")
            } catch (e: Exception) {
                logger.error(e) { "Failed to process webhook request: ${e.message}" }
                call.respond(OK, "Webhook received")
            }
        }

        // Launch queue processor with restart logic on failure
        this@configureRouting.launch {
            while (isActive) {
                try {
                    webhookRequestHandler.processQueue()
                } catch (e: Exception) {
                    logger.error(e) { "Queue processor crashed, restarting in ${QUEUE_RESTART_DELAY_MS}ms..." }
                    delay(QUEUE_RESTART_DELAY_MS)
                }
            }
        }

        // Launch periodic cleanup task to prevent memory leaks
        this@configureRouting.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                try {
                    webhookService.cleanupStaleEntries()
                    logger.debug { "Periodic cleanup completed" }
                } catch (e: Exception) {
                    logger.error(e) { "Periodic cleanup failed: ${e.message}" }
                }
            }
        }
    }
}
