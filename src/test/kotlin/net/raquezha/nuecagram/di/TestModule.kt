package net.raquezha.nuecagram.di

import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.telegram.MockTelegramService
import net.raquezha.nuecagram.telegram.TelegramService
import org.koin.dsl.module
import org.mindrot.jbcrypt.BCrypt

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
