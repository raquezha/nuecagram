package net.raquezha.nuecagram.plugins

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.http.HttpStatusCode.Companion.ServiceUnavailable
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.raquezha.nuecagram.configuredRoute
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.webhook.SkipEventException
import net.raquezha.nuecagram.webhook.WebHookService
import net.raquezha.nuecagram.webhook.WebhookRequestException
import net.raquezha.nuecagram.webhook.WebhookRequestHandler
import org.koin.core.parameter.parametersOf
import org.koin.ktor.ext.inject

private const val QUEUE_RESTART_DELAY_MS = 5000L
private const val CLEANUP_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

private fun Application.healthPath() = configuredRoute("/health")

private fun io.ktor.server.routing.Route.healthRouting(
    healthPath: String,
    databaseFactory: DatabaseFactory,
) {
    get("$healthPath/live") {
        call.respond(OK)
    }
    head("$healthPath/live") {
        call.respond(OK)
    }
    get("$healthPath/ready") {
        call.respond(if (databaseFactory.isReady()) OK else ServiceUnavailable)
    }
    head("$healthPath/ready") {
        call.respond(if (databaseFactory.isReady()) OK else ServiceUnavailable)
    }

    if (healthPath != "/health") {
        get("/health/live") {
            call.respond(OK)
        }
        head("/health/live") {
            call.respond(OK)
        }
        get("/health/ready") {
            call.respond(if (databaseFactory.isReady()) OK else ServiceUnavailable)
        }
        head("/health/ready") {
            call.respond(if (databaseFactory.isReady()) OK else ServiceUnavailable)
        }
    }
}

@Suppress("LongMethod")
fun Application.configureRouting() {
    val databaseFactory by inject<DatabaseFactory>()
    val webhookService by inject<WebHookService>()
    val installationRepository by inject<InstallationRepository>()
    val webhookRequestHandler by inject<WebhookRequestHandler> { parametersOf(this) }
    val logger by inject<KLogger>()

    routing {
        healthRouting(this@configureRouting.healthPath(), databaseFactory)

        get("/") {
            call.respondText("This application is made to receive webhooks request and send telegram notification")
        }

        telegramRouting(this@configureRouting.basePath())
        managementRouting(this@configureRouting.basePath())
        platformAdminRouting(this@configureRouting.basePath())

        post(configuredRoute("/webhook")) {
            try {
                val webhookData = webhookService.handleRequest(call)
                logger.debug {
                    "handling request webhook data: ${webhookData.log()}"
                }

                webhookRequestHandler.enqueue(webhookData)

                call.respond(OK, "Webhook received successfully")
            } catch (skipEx: SkipEventException) {
                call.respond(OK, "Event skipped: not relevant")
            } catch (e: WebhookRequestException) {
                logger.warn { "Rejected webhook request: ${e.message}" }
                call.respond(e.status, e.message)
            } catch (e: Exception) {
                logger.error(e) { "Failed to process webhook request: ${e.message}" }
                call.respond(io.ktor.http.HttpStatusCode.InternalServerError, "Webhook processing failed")
            }
        }

        // Launch queue processor with restart logic on failure
        this@configureRouting.launch {
            while (isActive) {
                try {
                    webhookRequestHandler.processQueue()
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) {
                        logger.debug { "Queue processor stopped" }
                        break
                    }
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
                    installationRepository.cleanupExpiredManagementLinks()
                    installationRepository.cleanupExpiredManagementSessions()
                    installationRepository.cleanupExpiredPlatformAdminSessions()
                    logger.debug { "Periodic cleanup completed" }
                } catch (e: Exception) {
                    logger.error(e) { "Periodic cleanup failed: ${e.message}" }
                }
            }
        }
    }
}
