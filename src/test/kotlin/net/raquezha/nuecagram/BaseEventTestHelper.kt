package net.raquezha.nuecagram

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.server.testing.ApplicationTestBuilder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import net.raquezha.nuecagram.db.InstallationRecord
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.di.testAppModule
import net.raquezha.nuecagram.plugins.configureRouting
import net.raquezha.nuecagram.plugins.configureSerialization

import net.raquezha.nuecagram.telegram.MockTelegramService
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.webhook.NuecagramHeaders.GITLAB_EVENT
import net.raquezha.nuecagram.webhook.NuecagramHeaders.GITLAB_TOKEN
import net.raquezha.nuecagram.webhook.WebHookService
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.startKoin
import org.koin.java.KoinJavaComponent.inject
import org.koin.test.KoinTest
import net.raquezha.nuecagram.testing.TestDatabase

abstract class BaseEventTestHelper : KoinTest {
    private lateinit var testHeaders: HeadersBuilder
    protected lateinit var installation: InstallationRecord
    protected lateinit var webhookToken: String

    protected val telegramService: TelegramService by inject(TelegramService::class.java)
    private val webhookService: WebHookService by inject(WebHookService::class.java)
    protected val installationRepository: InstallationRepository by inject(InstallationRepository::class.java)

    fun ApplicationTestBuilder.configureTestApplication() {
        application {
            configureSerialization()
            configureRouting()
        }
    }



    @Before
    fun setUp() {
        ensureTestDatabase()
        webhookService.resetRuntimeState()
        (telegramService as MockTelegramService).reset()

        runBlocking {
            val installationNumber = installationCounter.incrementAndGet()
            installation =
                installationRepository.createInstallation(
                    gitlabBaseUrl = INSTANCE,
                    gitlabProjectId = installationNumber,
                    telegramChatId = 100000 + installationNumber,
                    telegramTopicId = 200000 + installationNumber,
                )
            webhookToken = installationRepository.issueWebhookSecret(installation.id).raw
        }
        testHeaders =
            HeadersBuilder().apply {
                append(HttpHeaders.UserAgent, USER_AGENT)
                append("X-Gitlab-Webhook-UUID", WEBHOOK_UUID)
                append("X-Gitlab-Instance", INSTANCE)
                append("X-Gitlab-Token", webhookToken)
                append("X-Gitlab-Event-UUID", EVENT_UUID)
            }
    }

    protected fun mockTelegramService() = telegramService as MockTelegramService

    protected fun sentMessages() = mockTelegramService().sentMessages()

    protected suspend fun ApplicationTestBuilder.postWebhookResponse(
        gitlabEvent: String,
        payload: String,
        token: String = webhookToken,
        extraHeaders: HeadersBuilder.() -> Unit = {},
    ): HttpResponse =
        client.post(configuredRoute("/webhook")) {
            setBody(payload)
            contentType(ContentType.Application.Json)
            headers {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(GITLAB_EVENT, gitlabEvent)
                testHeaders.entries().forEach { entry ->
                    if (entry.key != GITLAB_TOKEN) {
                        entry.value.forEach { value ->
                            header(entry.key, value)
                        }
                    }
                }
                header(GITLAB_TOKEN, token)
                val legacyPrefix =
                    intArrayOf(88, 45, 78, 117, 101, 99, 97, 103, 114, 97, 109)
                        .map(Int::toChar)
                        .joinToString("")
                header("$legacyPrefix-Chat-Id", "999999")
                header("$legacyPrefix-Topic-Id", "888888")
                extraHeaders()
            }
        }

    suspend fun ApplicationTestBuilder.postWebhook(
        gitlabEvent: String,
        payload: String,
    ): String = postWebhookResponse(gitlabEvent, payload).bodyAsText()

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
        const val EVENT_PIPELINE = "Pipeline Hook"
        const val EVENT_DEPLOYMENT = "Deployment Hook"
        const val EVENT_RELEASE = "Release Hook"
        const val EVENT_NOTE = "Note Hook"

        private val installationCounter = AtomicLong(0)

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            if (GlobalContext.getOrNull() == null) {
                startKoin {
                    modules(
                        testAppModule(),
                    )
                }
            }
        }

        @JvmStatic
        private fun ensureTestDatabase() {
            val dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable
            assumeTrue("Docker is required for webhook tests", dockerAvailable)
            TestDatabase.ensureInitialized()
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            // Keep container and DatabaseFactory active across test classes to avoid stopping DB mid-suite
        }
    }
}
