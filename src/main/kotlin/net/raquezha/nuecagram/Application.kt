package net.raquezha.nuecagram

import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.di.appModule
import net.raquezha.nuecagram.plugins.configureRouting
import net.raquezha.nuecagram.plugins.configureSerialization
import net.raquezha.nuecagram.webhook.WebhookRequestHandler
import org.koin.core.parameter.parametersOf
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main() {
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

private fun validateRequiredEnvironmentVariables() {
    if (System.getenv("TELEGRAM_BOT_TOKEN").isNullOrBlank() ||
        System.getenv("TELEGRAM_WEBHOOK_SECRET").isNullOrBlank()
    ) {
        throw IllegalStateException(
            """
            Missing required environment variables:
              - TELEGRAM_BOT_TOKEN
              - TELEGRAM_WEBHOOK_SECRET

            Please set these variables before starting the application.
            """.trimIndent(),
        )
    }
}

fun config(filename: String): Config {
    val resource =
        object {}.javaClass.getResource(filename)?.readText()
            ?: throw IllegalArgumentException("Config file $filename not found in resources")
    return Json.decodeFromString(resource)
}

fun configWithSecrets(
    filename: String,
    botApi: String,
    telegramWebhookSecret: String,
): ConfigWithSecrets {
    val config = config(filename)

    return ConfigWithSecrets(
        name = config.name,
        env = config.env,
        host = config.host,
        port = config.port,
        botApi = botApi,
        telegramWebhookSecret = telegramWebhookSecret,
    )
}

fun Application.module() {
    install(Koin) {
        slf4jLogger()
        modules(appModule())
    }

    val databaseFactory by inject<DatabaseFactory>()
    databaseFactory.install(this)

    val httpClient by inject<HttpClient>()
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        httpClient.close()
    }

    configureSerialization()
    configureRouting()

    val webhookRequestHandler by inject<WebhookRequestHandler> { parametersOf(this@module) }
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        webhookRequestHandler.close()
    }
}
