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
import net.raquezha.nuecagram.testing.TelegramWebAppTestUtils.buildTestInitData
import net.raquezha.nuecagram.telegram.MockTelegramService
import org.junit.Test
import org.koin.test.inject

@Serializable
private data class DashboardTestAuthPayload(
    val success: Boolean,
    val csrf: String,
    val sessionToken: String? = null,
)

@Serializable
private data class TestInstallationPayload(
    val id: String,
    val gitlabBaseUrl: String,
    val telegramChatId: Long,
    val telegramTopicId: Long? = null,
    val muted: Boolean,
)

@Serializable
private data class TestMuteResponsePayload(
    val id: String,
    val muted: Boolean,
)

@Serializable
private data class TestActionResponsePayload(
    val success: Boolean,
    val message: String,
)

class WebDashboardTest : BaseEventTestHelper() {
    private val testConfig: ConfigWithSecrets by inject()
    private val mockTelegramService: MockTelegramService
        get() = telegramService as MockTelegramService
    private val json = Json { ignoreUnknownKeys = true }

    private fun extractCookie(setCookieHeaders: List<String>, cookieName: String): String? =
        setCookieHeaders.joinToString("; ").split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$cookieName=") }
            ?.substringAfter("$cookieName=")

    private fun issueSessionWithNonce(
        client: io.ktor.client.HttpClient,
        userId: Long = 9999L,
        chatId: Long = -100123456L,
        topicId: Long? = null,
    ): Pair<String, String> {
        mockTelegramService.setChatMemberStatus(chatId, userId, "administrator")
        val nonce = runBlocking {
            installationRepository.issueLaunchNonce(
                telegramChatId = chatId,
                telegramTopicId = topicId,
                telegramUserId = userId,
                expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            )
        }
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = userId)
        val authResp = runBlocking {
            client.post("/nuecagram/api/webapp/auth") {
                contentType(ContentType.Application.Json)
                setBody("""{"initData":"$initData","startParam":"nonce_${nonce.raw}"}""")
            }
        }
        assertThat(authResp.status).isEqualTo(HttpStatusCode.OK)
        val setCookies = authResp.headers.getAll("Set-Cookie").orEmpty()
        val sessionCookie = extractCookie(setCookies, "nuecagram_webapp_session")!!
        val authPayload = json.decodeFromString<DashboardTestAuthPayload>(runBlocking { authResp.bodyAsText() })
        return Pair(sessionCookie, authPayload.csrf)
    }

    @Test
    fun installationsEndpointRejectsUnauthenticatedRequests() = testApplication {
        configureTestApplication()
        val response = client.get("/nuecagram/api/webapp/installations")
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun installationsEndpointAcceptsSessionHeaderWithoutCookie() = testApplication {
        configureTestApplication()
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 9999L, "administrator")
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }
        assertThat(authResp.status).isEqualTo(HttpStatusCode.OK)
        val authPayload = json.decodeFromString<DashboardTestAuthPayload>(authResp.bodyAsText())
        val token = authPayload.sessionToken
        assertThat(token).isNotNull()

        val listResp = client.get("/nuecagram/api/webapp/installations") {
            header("X-Session-Token", token)
        }
        assertThat(listResp.status).isEqualTo(HttpStatusCode.OK)
        val items = json.decodeFromString<List<TestInstallationPayload>>(listResp.bodyAsText())
        assertThat(items).isNotEmpty()
    }

    @Test
    fun installationsEndpointReturnsEmptyListForUnscopedSession() = testApplication {
        configureTestApplication()
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }
        val setCookies = authResp.headers.getAll("Set-Cookie").orEmpty()
        val sessionCookie = extractCookie(setCookies, "nuecagram_webapp_session")

        val listResp = client.get("/nuecagram/api/webapp/installations") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(listResp.status).isEqualTo(HttpStatusCode.OK)
        val items = json.decodeFromString<List<TestInstallationPayload>>(listResp.bodyAsText())
        assertThat(items).isEmpty()
    }

    @Test
    fun installationsEndpointReturnsAccessibleInstallationsForUnscopedSessionWhenUserIsAdmin() = testApplication {
        configureTestApplication()
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 9999L, "administrator")
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }
        val setCookies = authResp.headers.getAll("Set-Cookie").orEmpty()
        val sessionCookie = extractCookie(setCookies, "nuecagram_webapp_session")

        val listResp = client.get("/nuecagram/api/webapp/installations") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(listResp.status).isEqualTo(HttpStatusCode.OK)
        val items = json.decodeFromString<List<TestInstallationPayload>>(listResp.bodyAsText())
        assertThat(items).isNotEmpty()
        assertThat(items.map { it.id }).contains(installation.id.toString())
    }

    @Test
    fun installationsEndpointReturnsFilteredListForScopedSession() = testApplication {
        configureTestApplication()
        val (sessionCookie, _) = issueSessionWithNonce(
            client,
            userId = 9999L,
            chatId = installation.telegramChatId,
            topicId = installation.telegramTopicId,
        )

        val listResp = client.get("/nuecagram/api/webapp/installations") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(listResp.status).isEqualTo(HttpStatusCode.OK)
        val items = json.decodeFromString<List<TestInstallationPayload>>(listResp.bodyAsText())
        assertThat(items).isNotEmpty()
        assertThat(items.map { it.id }).contains(installation.id.toString())
    }

    @Test
    fun installationDetailEndpointEnforcesSessionContextScope() = testApplication {
        configureTestApplication()
        val (sessionCookie, _) = issueSessionWithNonce(
            client,
            userId = 9999L,
            chatId = installation.telegramChatId,
            topicId = installation.telegramTopicId,
        )

        val detailResp = client.get("/nuecagram/api/webapp/installations/${installation.id}") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(detailResp.status).isEqualTo(HttpStatusCode.OK)
        val item = json.decodeFromString<TestInstallationPayload>(detailResp.bodyAsText())
        assertThat(item.id).isEqualTo(installation.id.toString())

        val (wrongContextSession, _) = issueSessionWithNonce(
            client,
            userId = 9999L,
            chatId = -999L,
        )

        val forbiddenDetailResp = client.get("/nuecagram/api/webapp/installations/${installation.id}") {
            header("Cookie", "nuecagram_webapp_session=$wrongContextSession")
        }
        assertThat(forbiddenDetailResp.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun installationDetailEndpointAllowsAccessForUnscopedAdminSession() = testApplication {
        configureTestApplication()
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 9999L, "administrator")
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }
        val setCookies = authResp.headers.getAll("Set-Cookie").orEmpty()
        val sessionCookie = extractCookie(setCookies, "nuecagram_webapp_session")

        val detailResp = client.get("/nuecagram/api/webapp/installations/${installation.id}") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(detailResp.status).isEqualTo(HttpStatusCode.OK)
        val item = json.decodeFromString<TestInstallationPayload>(detailResp.bodyAsText())
        assertThat(item.id).isEqualTo(installation.id.toString())
    }

    @Test
    fun muteEndpointRequiresCsrfAndUpdatesMuteStatus() = testApplication {
        configureTestApplication()
        val (sessionCookie, csrf) = issueSessionWithNonce(
            client,
            userId = 9999L,
            chatId = installation.telegramChatId,
            topicId = installation.telegramTopicId,
        )

        // Reject missing CSRF header
        val rejectCsrfResp = client.post("/nuecagram/api/webapp/installations/${installation.id}/mute") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
            setBody("""{"muted":true}""")
        }
        assertThat(rejectCsrfResp.status).isEqualTo(HttpStatusCode.Forbidden)

        // Accept valid CSRF header and toggle mute
        val muteResp = client.post("/nuecagram/api/webapp/installations/${installation.id}/mute") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
            header("X-CSRF-Token", csrf)
            setBody("""{"muted":true}""")
        }
        assertThat(muteResp.status).isEqualTo(HttpStatusCode.OK)
        val mutePayload = json.decodeFromString<TestMuteResponsePayload>(muteResp.bodyAsText())
        assertThat(mutePayload.muted).isTrue()

        val updatedContext = runBlocking { installationRepository.installationAdminContext(installation.id) }
        assertThat(updatedContext?.muted).isTrue()
    }

    @Test
    fun testEndpointRequiresCsrfAndDispatchesTestMessage() = testApplication {
        configureTestApplication()
        val (sessionCookie, csrf) = issueSessionWithNonce(
            client,
            userId = 9999L,
            chatId = installation.telegramChatId,
            topicId = installation.telegramTopicId,
        )

        val testResp = client.post("/nuecagram/api/webapp/installations/${installation.id}/test") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
            header("X-CSRF-Token", csrf)
        }
        assertThat(testResp.status).isEqualTo(HttpStatusCode.OK)
        val testPayload = json.decodeFromString<TestActionResponsePayload>(testResp.bodyAsText())
        assertThat(testPayload.success).isTrue()

        val delivered = sentMessages().last()
        assertThat(delivered.chatId).isEqualTo(installation.telegramChatId.toString())
        assertThat(delivered.text).contains(installation.id.toString())
    }

    @Test
    fun webAppJsScriptIsServedWithSecurityHeaders() = testApplication {
        configureTestApplication()
        val response = client.get("/nuecagram/webapp/app.js")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.headers["Content-Type"]).contains("javascript")
        assertThat(response.headers["Cache-Control"]).contains("no-store")
        val js = response.bodyAsText()
        assertThat(js).contains("initWebApp()")
        assertThat(js).contains("loadInstallations()")
    }

    @Test
    fun nonAdminUserIsRejectedWithForbidden() = testApplication {
        configureTestApplication()
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 7777L, "member")
        val nonce = runBlocking {
            installationRepository.issueLaunchNonce(
                telegramChatId = installation.telegramChatId,
                telegramTopicId = installation.telegramTopicId,
                telegramUserId = 7777L,
                expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            )
        }
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 7777L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData","startParam":"nonce_${nonce.raw}"}""")
        }
        val sessionCookie = extractCookie(authResp.headers.getAll("Set-Cookie").orEmpty(), "nuecagram_webapp_session")

        val listResp = client.get("/nuecagram/api/webapp/installations") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(listResp.status).isEqualTo(HttpStatusCode.Forbidden)
    }
}
