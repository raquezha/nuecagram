package net.raquezha.nuecagram

import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.di.appModule
import net.raquezha.nuecagram.plugins.configureRouting
import net.raquezha.nuecagram.plugins.configureSerialization
import net.raquezha.nuecagram.webhook.WebhookRequestHandler
import org.koin.core.parameter.parametersOf
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main() {
    // Validate required environment variables early with clear error messages
    validateRequiredEnvironmentVariables()

    val config = config("/application.json")
    val port = System.getenv("PORT")?.toIntOrNull() ?: config.port
    embeddedServer(
        Netty,
        watchPaths = listOf("nuecagram"),
        port = port,
        module = Application::module,
    ).start(true)
}

/**
 * Validates that all required environment variables are set before starting the application.
 * Throws a descriptive error if any are missing.
 */
private fun validateRequiredEnvironmentVariables() {
    val missingVars = mutableListOf<String>()

    if (System.getenv("TELEGRAM_BOT_TOKEN").isNullOrBlank()) {
        missingVars.add("TELEGRAM_BOT_TOKEN")
    }
    if (System.getenv("NUECAGRAM_SECRET_TOKEN").isNullOrBlank()) {
        missingVars.add("NUECAGRAM_SECRET_TOKEN")
    }

    if (missingVars.isNotEmpty()) {
        val message =
            buildString {
                append("Missing required environment variables:\n")
                missingVars.forEach { append("  - $it\n") }
                append("\nPlease set these variables before starting the application.")
            }
        throw IllegalStateException(message)
    }
}

fun config(filename: String): Config {
    val resource = object {}.javaClass.getResource(filename)?.readText() 
        ?: throw IllegalArgumentException("Config file $filename not found in resources")
    return Json.decodeFromString(resource)
}

fun configWithSecrets(
    filename: String,
    botApi: String,
    secretToken: String,
): ConfigWithSecrets {
    val config = config(filename)

    return ConfigWithSecrets(
        name = config.name,
        env = config.env,
        host = config.host,
        port = config.port,
        botApi = botApi,
        secretToken = secretToken,
    )
}

fun Application.module() {
    install(Koin) {
        slf4jLogger()
        modules(appModule())
    }

    // Close resources when application stops to prevent leaks
    val httpClient by inject<HttpClient>()
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        httpClient.close()
    }

    configureSerialization()
    configureRouting()

    // Close the webhook queue on shutdown (must be after configureRouting creates the handler)
    val webhookRequestHandler by inject<WebhookRequestHandler> { parametersOf(this@module) }
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        webhookRequestHandler.close()
    }
}
