package net.raquezha.nuecagram.di

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.types.component.LogLvl
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel.ALL
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.configWithSecrets
import net.raquezha.nuecagram.telegram.MockTelegramService
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.telegram.TelegramServiceImpl
import net.raquezha.nuecagram.webhook.RandomMessageProvider
import net.raquezha.nuecagram.webhook.WebHookService
import net.raquezha.nuecagram.webhook.WebhookMessageFormatter
import net.raquezha.nuecagram.webhook.WebhookRequestHandler
import org.koin.dsl.module

fun appModule() =
    listOf(
        provideLogger,
        provideTelegramService,
        provideWebhookModule,
        provideHttpClient,
        provideConfigModule,
        provideWebhookRequestHandler,
    )

fun testAppModule() =
    listOf(
        provideLogger,
        provideTelegramService,
        provideWebhookModule,
        provideHttpClient,
        provideTelegramBot,
        provideWebhookRequestHandler,
        testModule,
    )

val testModule =
    module {
        single<TelegramService> { MockTelegramService() }
        single<ConfigWithSecrets> {
            ConfigWithSecrets(
                name = "TestConfig",
                env = "test",
                host = "localhost",
                port = 8080,
                botApi = "mock_bot_api",
                secretToken = "mock_secret_token",
            )
        }
    }

val provideConfigModule =
    module {
        single { configWithSecrets(
            filename = "/application.json",
            botApi = System.getenv("TELEGRAM_BOT_TOKEN") ?: throw IllegalStateException("TELEGRAM_BOT_TOKEN missing"),
            secretToken = System.getenv("NUECAGRAM_SECRET_TOKEN") ?: throw IllegalStateException("NUECAGRAM_SECRET_TOKEN missing"),
        ) }
    }

val provideHttpClient =
    module {
        single<HttpClient> {
            val logger: KLogger = get()

            fun logRequestBegin(request: HttpRequestBuilder) {
                logger.debug { "Starting request ${request.url}" }
            }

            fun logRequestEnd(response: HttpClientCall) {
                logger.debug { "Finished request ${response.request.url}" }
            }

            HttpClient(CIO) {
                expectSuccess = true
                install(Logging) {
                    level = ALL
                }

                install(ContentNegotiation) {
                    json()
                }
            }.also { client ->
                client.plugin(HttpSend).intercept { request ->
                    logRequestBegin(request)
                    val response = execute(request)
                    logRequestEnd(response)
                    response
                }
            }
        }
    }


val provideLogger =
    module {
        single<KLogger> {
            KotlinLogging.logger { }
        }
    }

val provideTelegramService =
    module {
        single<TelegramService> {
            TelegramServiceImpl(get(), get())
        }
    }

val provideTelegramBot =
    module {
        single<TelegramBot> {
            TelegramBot(
                token = get<ConfigWithSecrets>().botApi,
                botConfiguration = {
                    logging {
                        botLogLevel = LogLvl.ALL
                    }
                },
            )
        }
    }

val provideWebhookModule =
    module {
        single<WebhookMessageFormatter> {
            WebhookMessageFormatter()
        }

        single<RandomMessageProvider> {
            RandomMessageProvider()
        }

        // Define WebHookHandlerImpl as a single instance, injecting the secretToken and WebHookListenerBuilder
        single<WebHookService> {
            val config: net.raquezha.nuecagram.ConfigWithSecrets = get()
            WebHookService(config.secretToken, get())
        }
    }

val provideWebhookRequestHandler =
    module {
        single { params ->
            WebhookRequestHandler(
                application = params.get<Application>(),
                randomMessageProvider = get(),
            )
        }
    }
