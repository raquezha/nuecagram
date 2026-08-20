package net.raquezha.nuecagram.webapp

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import net.raquezha.nuecagram.telegram.MockTelegramService
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
    val sessionToken: String? = null,
    val telegramChatId: Long? = null,
    val telegramTopicId: Long? = null,
)

class WebAppAuthEndpointTest : BaseEventTestHelper() {

    private val testConfig: ConfigWithSecrets by inject()
    private val mockTelegramService: MockTelegramService
        get() = telegramService as MockTelegramService
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun webAppRouteReturnsHtmlContainerWithSecurityHeaders() = testApplication {
        configureTestApplication()
        val response = client.get("/nuecagram/webapp")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.headers["Content-Type"]).contains("text/html")
        val csp = response.headers["Content-Security-Policy"].orEmpty()
        assertThat(csp).contains("script-src 'self' https://telegram.org")
        assertThat(csp).contains(
            "frame-ancestors 'self' https://web.telegram.org https://*.telegram.org https://telegram.org;",
        )
        assertThat(response.headers["X-Frame-Options"]).isNull()
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
        assertThat(payload.sessionToken).isNotNull()

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

    @Test
    fun authEndpointFallsBackToInitDataStartParamWhenPayloadStartParamIsBlank() = testApplication {
        configureTestApplication()
        val nonce = runBlocking {
            installationRepository.issueLaunchNonce(
                telegramChatId = -100987654L,
                telegramTopicId = 99L,
                telegramUserId = 12345L,
                expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            )
        }
        val botToken = testConfig.botApi
        val initData = buildTestInitData(
            botToken,
            userId = 12345L,
            extraParams = mapOf("start_param" to "nonce_${nonce.raw}"),
        )

        // Send payload with blank startParam string: should fall back to verified.startParam
        val response = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData","startParam":""}""")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val payload = json.decodeFromString<TestAuthResponsePayload>(response.bodyAsText())
        assertThat(payload.telegramChatId).isEqualTo(-100987654L)
        assertThat(payload.telegramTopicId).isEqualTo(99L)
    }

    @Test
    fun authEndpointPreservesContextOnReauthWithSessionHeader() = testApplication {
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

        // Step 1: Auth with launch nonce
        val step1Resp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData","startParam":"nonce_${nonce.raw}"}""")
        }
        assertThat(step1Resp.status).isEqualTo(HttpStatusCode.OK)
        val payload1 = json.decodeFromString<TestAuthResponsePayload>(step1Resp.bodyAsText())
        assertThat(payload1.telegramChatId).isEqualTo(-100123456L)
        assertThat(payload1.telegramTopicId).isEqualTo(42L)
        val token1 = payload1.sessionToken
        assertThat(token1).isNotNull()

        // Step 2: Re-auth without launch nonce, sending X-Session-Token header (no cookie)
        val step2Resp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            header("X-Session-Token", token1)
            setBody("""{"initData":"$initData"}""")
        }
        assertThat(step2Resp.status).isEqualTo(HttpStatusCode.OK)
        val payload2 = json.decodeFromString<TestAuthResponsePayload>(step2Resp.bodyAsText())

        // Step 3: Assert context preserved
        assertThat(payload2.telegramChatId).isEqualTo(-100123456L)
        assertThat(payload2.telegramTopicId).isEqualTo(42L)
        val token2 = payload2.sessionToken
        assertThat(token2).isNotNull()

        // Step 4: GET installations with new session token
        mockTelegramService.setChatMemberStatus(-100123456L, 12345L, "administrator")
        val step4Resp = client.get("/nuecagram/api/webapp/installations") {
            header("X-Session-Token", token2)
        }
        assertThat(step4Resp.status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun authEndpointSetsSecureSameSiteNoneCookiesWhenHttps() = testApplication {
        configureTestApplication()
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken)
        val response = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-Proto", "https")
            setBody("""{"initData":"$initData"}""")
        }

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val setCookie = response.headers.getAll("Set-Cookie").orEmpty().joinToString("; ")
        assertThat(setCookie).contains("SameSite=None")
        assertThat(setCookie).contains("Secure")
    }

    @Test
    fun webAppJsScriptServesGetAuthHeadersAndIncludesHeaderInAuthFetch() = testApplication {
        configureTestApplication()
        val response = client.get("/nuecagram/webapp/app.js")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.headers["Content-Type"]).contains("application/javascript")
        val js = response.bodyAsText()
        assertThat(js).contains("function getAuthHeaders(")
        assertThat(js).contains("headers: getAuthHeaders({ 'Content-Type': 'application/json' })")
    }
}
