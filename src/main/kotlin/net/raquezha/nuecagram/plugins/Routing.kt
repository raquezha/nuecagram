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
import net.raquezha.nuecagram.telegram.BotCommand
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.webhook.SkipEventException
import net.raquezha.nuecagram.webhook.WebHookService
import net.raquezha.nuecagram.webhook.WebhookRequestException
import net.raquezha.nuecagram.webhook.WebhookRequestHandler
import org.koin.core.parameter.parametersOf
import org.koin.ktor.ext.inject

private const val QUEUE_RESTART_DELAY_MS = 5000L
private const val CLEANUP_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
private val BOT_COMMANDS = listOf(
    BotCommand("manage", "View and manage connected repositories"),
    BotCommand("status", "View repository notification status"),
    BotCommand("test", "Send a test notification"),
    BotCommand("rotate", "Rotate webhook secret token"),
    BotCommand("mute", "Pause notifications"),
    BotCommand("unmute", "Resume notifications"),
    BotCommand("digest", "View summary text"),
    BotCommand("setup", "How to bind a new GitLab repository"),
    BotCommand("help", "View command reference and instructions"),
)

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
    val telegramService by inject<TelegramService>()
    val webhookRequestHandler by inject<WebhookRequestHandler> { parametersOf(this) }
    val logger by inject<KLogger>()

    launch {
        runCatching { telegramService.setMyCommands(BOT_COMMANDS) }
            .onFailure { logger.warn(it) { "Failed to configure Telegram bot commands" } }
    }

    routing {
        healthRouting(this@configureRouting.healthPath(), databaseFactory)

        get("/") {
            call.respondText("This application is made to receive webhooks request and send telegram notification")
        }

        telegramRouting(this@configureRouting.basePath())
        managementRouting(this@configureRouting.basePath())
        platformAdminRouting(this@configureRouting.basePath())
        webAppRouting(this@configureRouting.basePath())


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
                    installationRepository.cleanupExpiredWebAppSessions()

                    logger.debug { "Periodic cleanup completed" }
                } catch (e: Exception) {
                    logger.error(e) { "Periodic cleanup failed: ${e.message}" }
                }
            }
        }
    }
}
