package net.raquezha.nuecagram

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import net.raquezha.nuecagram.plugins.configureRouting
import io.mockk.every
import io.mockk.mockkObject
import net.raquezha.nuecagram.di.SystemEnvImpl
import net.raquezha.nuecagram.di.testAppModule
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.core.context.startKoin

class ApplicationTest {
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

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            // Start Koin once per test class
            mockkObject(SystemEnvImpl)
            every { SystemEnvImpl.getBotApi() } returns "mock_bot_api"
            every { SystemEnvImpl.getSecretToken() } returns "mock_secret_token"

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
