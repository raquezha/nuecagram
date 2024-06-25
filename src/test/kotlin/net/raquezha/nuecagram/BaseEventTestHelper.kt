package net.raquezha.nuecagram

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.every
import io.mockk.mockkObject
import net.raquezha.nuecagram.di.SystemEnvImpl
import net.raquezha.nuecagram.di.testAppModule
import net.raquezha.nuecagram.plugins.configureRouting
import net.raquezha.nuecagram.webhook.NuecagramHeaders.CHAT_ID
import net.raquezha.nuecagram.webhook.NuecagramHeaders.GITLAB_EVENT
import net.raquezha.nuecagram.webhook.NuecagramHeaders.SECRET_TOKEN
import net.raquezha.nuecagram.webhook.NuecagramHeaders.TOPIC_ID
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.java.KoinJavaComponent.inject
import org.koin.ktor.plugin.koin
import org.koin.test.KoinTest

abstract class BaseEventTestHelper : KoinTest {
    private lateinit var testHeaders: HeadersBuilder
    private val config: ConfigWithSecrets by inject(ConfigWithSecrets::class.java)

    fun ApplicationTestBuilder.configureTestApplication() {
        application {
            configureRouting()
            koin {
                modules(
                    testAppModule(),
                )
            }
        }
    }

    @Before
    fun setUp() {
        testHeaders =
            HeadersBuilder().apply {
                append(HttpHeaders.UserAgent, USER_AGENT)
                append("X-Gitlab-Webhook-UUID", WEBHOOK_UUID)
                append("X-Gitlab-Instance", INSTANCE)
                append("X-Gitlab-Token", TOKEN)
                append("X-Gitlab-Event-UUID", EVENT_UUID)
                append(SECRET_TOKEN, config.secretToken)
            }
    }

    suspend fun ApplicationTestBuilder.postWebhook(
        gitlabEvent: String,
        payload: String,
    ): String {
        return client.post("/webhook") {
            setBody(payload)
            contentType(ContentType.Application.Json)
            headers {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(GITLAB_EVENT, gitlabEvent)
                testHeaders.entries().forEach {
                    header(it.key, it.value)
                }
                header(CHAT_ID, "Test Chat ID")
                header(TOPIC_ID, "TEST Topic ID")
            }
        }.bodyAsText()
    }

    companion object {
        const val USER_AGENT = "GitLab/16.11.2-ee"
        const val WEBHOOK_UUID = "UUID_1234567890"
        const val INSTANCE = "https://gitlab.com"
        const val TOKEN = "TOKEN_1234567890"
        const val EVENT_UUID = "EVENT_UUID_1234567890"
        const val EVENT_PUSH = "Push Hook"
        const val EVENT_TAG = "Tag Push Hook"
        const val EVENT_ISSUE = "Issue Hook"
        const val EVENT_MERGE = "Merge Request Hook"
        const val EVENT_WIKI = "Wiki Page Hook"
        const val EVENT_JOB = "Job Hook"
        const val EVENT_DEPLOYMENT = "Deployment Hook"
        const val EVENT_RELEASE = "Release Hook"

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
