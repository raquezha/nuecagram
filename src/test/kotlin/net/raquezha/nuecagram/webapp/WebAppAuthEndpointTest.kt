package net.raquezha.nuecagram.webapp

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.BaseEventTestHelper
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.testing.TelegramWebAppTestUtils.buildTestInitData
import org.junit.Test
import org.koin.test.inject

@Serializable
private data class TestAuthUser(
    val id: Long,
    val firstName: String? = null,
    val username: String? = null,
)

@Serializable
private data class TestAuthResponsePayload(
    val success: Boolean,
    val user: TestAuthUser,
    val csrf: String,
    val telegramChatId: Long? = null,
    val telegramTopicId: Long? = null,
)

class WebAppAuthEndpointTest : BaseEventTestHelper() {

    private val testConfig: ConfigWithSecrets by inject()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun webAppRouteReturnsHtmlContainerWithSecurityHeaders() = testApplication {
        configureTestApplication()
        val response = client.get("/nuecagram/webapp")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.headers["Content-Type"]).contains("text/html")
        assertThat(response.headers["Content-Security-Policy"]).contains("script-src 'self' https://telegram.org")
        assertThat(response.headers["Cache-Control"]).contains("no-store")
        val html = response.bodyAsText()
        assertThat(html).contains("https://telegram.org/js/telegram-web-app.js")
    }

    @Test
    fun authEndpointRejectsInvalidInitData() = testApplication {
        configureTestApplication()
        val response = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"invalid_data"}""")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(response.bodyAsText()).contains("Invalid or expired initData signature")
    }

    @Test
    fun authEndpointIssuesSessionCookieAndCsrfForValidInitData() = testApplication {
        configureTestApplication()
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken)
        val response = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val payload = json.decodeFromString<TestAuthResponsePayload>(response.bodyAsText())
        assertThat(payload.success).isTrue()
        assertThat(payload.user.id).isEqualTo(12345L)
        assertThat(payload.csrf).isNotEmpty()

        val setCookie = response.headers.getAll("Set-Cookie").orEmpty().joinToString("; ")
        assertThat(setCookie).contains("nuecagram_webapp_session=")
        assertThat(setCookie).contains("nuecagram_webapp_csrf=")
    }

    @Test
    fun authEndpointConsumesLaunchNonceAndResolvesContext() = testApplication {
        configureTestApplication()
        val nonce = runBlocking {
            installationRepository.issueLaunchNonce(
                telegramChatId = -100123456L,
                telegramTopicId = 42L,
                telegramUserId = 12345L,
                expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            )
        }
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 12345L)

        val response = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData","startParam":"nonce_${nonce.raw}"}""")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val payload1 = json.decodeFromString<TestAuthResponsePayload>(response.bodyAsText())
        assertThat(payload1.telegramChatId).isEqualTo(-100123456L)
        assertThat(payload1.telegramTopicId).isEqualTo(42L)

        // Second attempt with same nonce fails context resolution (consumed)
        val response2 = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData","startParam":"nonce_${nonce.raw}"}""")
        }
        assertThat(response2.status).isEqualTo(HttpStatusCode.OK)
        val payload2 = json.decodeFromString<TestAuthResponsePayload>(response2.bodyAsText())
        assertThat(payload2.telegramChatId).isNull()
    }
}
