package net.raquezha.nuecagram.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBotInitializerTest {
    private val mockService = MockTelegramService()
    private val logger = KotlinLogging.logger {}
    private val initializer = TelegramBotInitializerImpl(mockService, logger)

    @Test
    fun initializeConfiguresCommandsWebhookAndMenuButton() = runBlocking {
        mockService.reset()

        initializer.initialize(
            publicUrl = "https://example.com/nuecagram",
            appHeader = "test-secret-token",
        )

        val commands = mockService.botCommands().map { it.command }
        assertTrue(commands.contains("manage"))
        assertTrue(commands.contains("help"))
        assertEquals("https://example.com/nuecagram/telegram/webhook", mockService.configuredWebhookUrl())
        assertEquals("test-secret-token", mockService.configuredWebhookHeader())

        val menu = mockService.configuredMenuButton()
        assertNotNull(menu)
        assertEquals("web_app", menu?.type)
        assertEquals("OPEN", menu?.text)
        assertEquals("https://example.com/nuecagram/webapp", menu?.webApp?.url)
    }

    @Test
    fun initializeHandlesFailuresGracefully() = runBlocking {
        mockService.reset()
        mockService.failGetMe()

        // Should not throw exception even if getMe fails
        initializer.initialize(
            publicUrl = "https://example.com/nuecagram",
            appHeader = "test-secret-token",
        )

        assertEquals("https://example.com/nuecagram/telegram/webhook", mockService.configuredWebhookUrl())
    }

    @Test
    fun initializeNormalizesPublicUrlTrailingSlash() = runBlocking {
        mockService.reset()

        initializer.initialize(
            publicUrl = "https://example.com/nuecagram/",
            appHeader = "test-secret-token",
        )

        assertEquals("https://example.com/nuecagram/telegram/webhook", mockService.configuredWebhookUrl())
        val menu = mockService.configuredMenuButton()
        assertEquals("https://example.com/nuecagram/webapp", menu?.webApp?.url)
    }
}
