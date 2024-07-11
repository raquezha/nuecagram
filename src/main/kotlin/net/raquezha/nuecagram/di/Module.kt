package net.raquezha.nuecagram.di

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.types.internal.LogLvl
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
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.configWithSecrets
import net.raquezha.nuecagram.telegram.MockTelegramService
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.telegram.TelegramServiceImpl
import net.raquezha.nuecagram.telegram.TokenProvider
import net.raquezha.nuecagram.telegram.TokenProviderImpl
import net.raquezha.nuecagram.telegram.TokenProviderImpl.SecretToken
import net.raquezha.nuecagram.telegram.TokenProviderImpl.TelegramBotToken
import net.raquezha.nuecagram.webhook.WebHookService
import net.raquezha.nuecagram.webhook.WebHookServiceImpl
import net.raquezha.nuecagram.webhook.WebhookMessageFormatter
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.inject

fun appModule() =
    listOf(
        provideLogger,
        provideTelegramService,
        provideWebhookModule,
        provideTokenProvider,
        provideHttpClient,
        provideConfigModule,
    )

fun testAppModule() =
    listOf(
        provideLogger,
        provideTelegramService,
        provideWebhookModule,
        provideTokenProvider,
        provideHttpClient,
        provideTelegramBot,
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
    module(createdAtStart = true) {
        configWithSecrets(
            filename = "/application.json",
            botApi = SystemEnvImpl.getBotApi(),
            secretToken = SystemEnvImpl.getSecretToken(),
        ).also { config ->
            single { config }
        }
    }

val provideHttpClient =
    module {
        val logger: KLogger by inject(KLogger::class.java)

        fun logRequestBegin(request: HttpRequestBuilder) {
            logger.debug { "Starting request ${request.url}" }
        }

        // 2. Define a logging function for just after we receive the response
        fun logRequestEnd(response: HttpClientCall) {
            logger.debug { "Finished request ${response.request.url}" }
        }

        // 3. Create the client
        val client =
            HttpClient(CIO) {
                expectSuccess = true
                install(Logging) {
                    level = ALL
                }

                install(ContentNegotiation) {
                    json()
                }
            }

        // 4. Configure logging on the client
        client.plugin(HttpSend).intercept { request ->
            logRequestBegin(request)
            val response = execute(request)
            logRequestEnd(response)
            response
        }

        single<HttpClient> { client }
    }

val provideTokenProvider =
    module {
        val config: ConfigWithSecrets by inject(ConfigWithSecrets::class.java)
        single {
            TelegramBotToken(config.botApi)
        }
        single {
            SecretToken(config.secretToken)
        }
        single<TokenProvider> {
            TokenProviderImpl(get(), get())
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

        // Define WebHookHandlerImpl as a single instance, injecting the secretToken and WebHookListenerBuilder
        single<WebHookService> {
            val tokenProvider: TokenProvider by inject()
            WebHookServiceImpl(tokenProvider.getSecretToken(), get())
        }
    }
