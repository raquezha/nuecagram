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

        val dmCommands = mockService.botCommands().map { it.command }
        assertTrue(dmCommands.contains("manage"))
        assertTrue(dmCommands.contains("help"))
        assertTrue(dmCommands.contains("rotate"))

        val groupCommands = mockService.groupBotCommands().map { it.command }
        assertEquals(listOf("help"), groupCommands)
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

    @Test
    fun dmCommandListDoesNotContainSetup() = runBlocking {
        mockService.reset()
        initializer.initialize(publicUrl = "https://example.com", appHeader = "s")
        val dmCommands = mockService.botCommands().map { it.command }
        assertTrue("DM command list must not contain /setup", !dmCommands.contains("setup"))
    }

    @Test
    fun groupCommandListContainsOnlyHelp() = runBlocking {
        mockService.reset()
        initializer.initialize(publicUrl = "https://example.com", appHeader = "s")
        val groupCommands = mockService.groupBotCommands().map { it.command }
        assertEquals("Group must expose exactly one command", 1, groupCommands.size)
        assertEquals("help", groupCommands.single())
    }

    @Test
    fun groupCommandListNeverContainsManagementCommands() = runBlocking {
        mockService.reset()
        initializer.initialize(publicUrl = "https://example.com", appHeader = "s")
        val groupCommands = mockService.groupBotCommands().map { it.command }
        val forbidden = listOf("manage", "status", "test", "rotate", "mute", "unmute", "digest", "setup")
        forbidden.forEach { cmd ->
            assertTrue("Group must not expose /$cmd", !groupCommands.contains(cmd))
        }
    }

    @Test
    fun staleAdminScopeIsDeletedOnStartup() = runBlocking {
        mockService.reset()
        initializer.initialize(publicUrl = "https://example.com", appHeader = "s")
        assertTrue(
            "deleteMyCommands must be called for all_chat_administrators scope",
            mockService.deletedScopes().contains("all_chat_administrators"),
        )
    }

    @Test
    fun groupScopeIsNeverInDeletedScopes() = runBlocking {
        // all_group_chats must be SET not deleted — delete was the old stale-setup workaround
        mockService.reset()
        initializer.initialize(publicUrl = "https://example.com", appHeader = "s")
        assertTrue(
            "all_group_chats must not be deleted, it should be set with /help only",
            !mockService.deletedScopes().contains("all_group_chats"),
        )
    }

    @Test
    fun commandRegistrationSurvivesGetMeFailure() = runBlocking {
        mockService.reset()
        mockService.failGetMe()
        initializer.initialize(publicUrl = "https://example.com", appHeader = "s")
        // Commands must still be registered even if getMe fails
        assertTrue(mockService.botCommands().isNotEmpty())
        assertTrue(mockService.groupBotCommands().isNotEmpty())
    }
}
