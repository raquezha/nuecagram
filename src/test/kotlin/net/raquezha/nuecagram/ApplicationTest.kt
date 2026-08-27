package net.raquezha.nuecagram

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import net.raquezha.nuecagram.webhook.NuecagramHeaders.GITLAB_EVENT
import net.raquezha.nuecagram.webhook.NuecagramHeaders.GITLAB_TOKEN
import net.raquezha.nuecagram.plugins.configureRouting
import net.raquezha.nuecagram.di.testAppModule
import net.raquezha.nuecagram.telegram.MockTelegramService
import net.raquezha.nuecagram.telegram.TelegramService
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.inject
import org.koin.test.KoinTest

class ApplicationTest : KoinTest {
    private val telegramService: TelegramService by inject(TelegramService::class.java)

    @Test
    fun testRoot() =
        testApplication {
            application {
                configureRouting()
            }
            val response = client.get("/")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("This application is made to receive webhooks request and send telegram notification", response.bodyAsText())
        }

    @Test
    fun botCommandMenuExcludesStart() =
        testApplication {
            application {
                configureRouting()
            }
            client.get("/")

            val commands = (telegramService as MockTelegramService).botCommands().map { it.command }
            org.junit.Assert.assertFalse(commands.contains("start"))
            org.junit.Assert.assertTrue(commands.contains("manage"))
            org.junit.Assert.assertTrue(commands.contains("help"))
        }

    @Test
    fun testHealthRoutes() =
        testApplication {
            application {
                configureRouting()
            }
            assertEquals(HttpStatusCode.OK, client.get("/nuecagram/health/live").status)
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
            assertEquals(HttpStatusCode.OK, client.head("/health/live").status)
            val readyStatus = client.get("/health/ready").status
            org.junit.Assert.assertTrue(
                readyStatus == HttpStatusCode.OK || readyStatus == HttpStatusCode.ServiceUnavailable,
            )
        }

    @Test
    fun testOnboardingLandingWithAndWithoutTrailingSlash() =
        testApplication {
            application {
                configureRouting()
            }
            assertEquals(HttpStatusCode.OK, client.get("/nuecagram").status)
            assertEquals(HttpStatusCode.OK, client.get("/nuecagram/").status)
            assertEquals(HttpStatusCode.OK, client.get("/nuecagram/setup").status)
        }

    @Test
    fun rootWebhookRouteIsNotRegistered() =
        testApplication {
            application {
                configureRouting()
            }
            val response =
                client.post("/webhook") {
                    header(GITLAB_EVENT, "Push Hook")
                    header(GITLAB_TOKEN, "unused")
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun configuredPublicUrlRejectsQueryAndFragment() {
        val previous = System.getProperty("nuecagram.publicUrl")
        try {
            System.setProperty("nuecagram.publicUrl", "https://example.com/nuecagram?bad=1")
            kotlin.runCatching { configuredPublicUrl() }
                .exceptionOrNull()
                ?.message
                ?.let { assertEquals("NUECAGRAM_PUBLIC_URL must not include a query", it) }
                ?: throw AssertionError("Expected invalid public URL")

            System.setProperty("nuecagram.publicUrl", "https://example.com/nuecagram#bad")
            kotlin.runCatching { configuredPublicUrl() }
                .exceptionOrNull()
                ?.message
                ?.let { assertEquals("NUECAGRAM_PUBLIC_URL must not include a fragment", it) }
                ?: throw AssertionError("Expected invalid public URL")
        } finally {
            if (previous == null) {
                System.clearProperty("nuecagram.publicUrl")
            } else {
                System.setProperty("nuecagram.publicUrl", previous)
            }
        }
    }

    @Test
    fun publicUrlPathControlsRoutePrefix() {
        val previous = System.getProperty("nuecagram.publicUrl")
        System.setProperty("nuecagram.publicUrl", "https://example.com")
        try {
            testApplication {
                application {
                    configureRouting()
                }
                assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
                assertEquals(HttpStatusCode.OK, client.get("/setup").status)
                assertEquals("", configuredBasePath())
                assertEquals("https://example.com", configuredPublicUrl())
                assertEquals(HttpStatusCode.NotFound, client.get("/nuecagram/health/live").status)
            }
        } finally {
            if (previous == null) {
                System.clearProperty("nuecagram.publicUrl")
            } else {
                System.setProperty("nuecagram.publicUrl", previous)
            }
        }
    }

    @After
    fun clearPublicUrl() {
        System.clearProperty("nuecagram.publicUrl")
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            // Start Koin once per test class

            if (GlobalContext.getOrNull() == null) {
                startKoin {
                    modules(
                        testAppModule(),
                    )
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            // Stop Koin once after all tests in the class
            stopKoin()
        }
    }
}
