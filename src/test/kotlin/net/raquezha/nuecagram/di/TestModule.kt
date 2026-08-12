package net.raquezha.nuecagram.di

import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.telegram.MockTelegramService
import net.raquezha.nuecagram.telegram.TelegramService
import org.koin.dsl.module

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
                platformAdminPassword = "test-admin-password",
                telegramWebhookSecret = "test-telegram-webhook-token",
            )
        }
    }
