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
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.configWithSecrets
import net.raquezha.nuecagram.telegram.MockTelegramService
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.telegram.TelegramServiceImpl
import net.raquezha.nuecagram.telegram.TelegramUpdateHandler
import net.raquezha.nuecagram.webhook.RandomMessageProvider
import net.raquezha.nuecagram.webhook.WebHookService
import net.raquezha.nuecagram.webhook.WebhookMessageFormatter
import net.raquezha.nuecagram.webhook.WebhookRequestHandler
import org.koin.dsl.module
import org.mindrot.jbcrypt.BCrypt

fun appModule() =
    listOf(
        provideLogger,
        provideDatabaseModule,
        provideTelegramService,
        provideTelegramUpdateModule,
        provideWebhookModule,
        provideHttpClient,
        provideConfigModule,
        provideWebhookRequestHandler,
    )

fun testAppModule() =
    listOf(
        provideLogger,
        provideDatabaseModule,
        provideTelegramService,
        provideTelegramUpdateModule,
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
                platformAdminHash = BCrypt.hashpw("test-admin-password", BCrypt.gensalt(4)),
                telegramWebhookSecret = "test-telegram-webhook-token",
            )
        }
    }

val provideConfigModule =
    module {
        single { configWithSecrets(
            filename = "/application.json",
            botApi = System.getenv("TELEGRAM_BOT_TOKEN")
                ?: throw IllegalStateException("TELEGRAM_BOT_TOKEN missing"),
            platformAdminHash = System.getenv("PLATFORM_ADMIN_PASSWORD_HASH")
                ?: throw IllegalStateException("PLATFORM_ADMIN_PASSWORD_HASH missing"),
            telegramWebhookSecret = System.getenv("TELEGRAM_WEBHOOK_SECRET")
                ?: throw IllegalStateException("TELEGRAM_WEBHOOK_SECRET missing"),
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


val provideDatabaseModule =
    module {
        single { DatabaseFactory }
        single { InstallationRepository(get()) }
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

val provideTelegramUpdateModule =
    module {
        single { TelegramUpdateHandler(get(), get(), get()) }
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

        single<WebHookService> {
            WebHookService(get(), get())
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
