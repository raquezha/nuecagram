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
import kotlinx.coroutines.runBlocking

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.BaseEventTestHelper
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.testing.TelegramWebAppTestUtils.buildTestInitData
import org.junit.Test
import org.koin.test.inject

@Serializable
private data class DashboardTestAuthPayload(
    val success: Boolean,
    val csrf: String,
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
    private val json = Json { ignoreUnknownKeys = true }

    private fun extractCookie(setCookieHeaders: List<String>, cookieName: String): String? =
        setCookieHeaders.joinToString("; ").split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$cookieName=") }
            ?.substringAfter("$cookieName=")

    @Test
    fun installationsEndpointRejectsUnauthenticatedRequests() = testApplication {
        configureTestApplication()
        val response = client.get("/nuecagram/api/webapp/installations")
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun installationsEndpointReturnsFilteredListForAuthenticatedSession() = testApplication {
        configureTestApplication()
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }
        assertThat(authResp.status).isEqualTo(HttpStatusCode.OK)

        val setCookies = authResp.headers.getAll("Set-Cookie").orEmpty()
        val sessionCookie = extractCookie(setCookies, "nuecagram_webapp_session")
        assertThat(sessionCookie).isNotNull()

        val listResp = client.get("/nuecagram/api/webapp/installations") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(listResp.status).isEqualTo(HttpStatusCode.OK)
        val items = json.decodeFromString<List<TestInstallationPayload>>(listResp.bodyAsText())
        assertThat(items).isNotEmpty()
        assertThat(items.map { it.id }).contains(installation.id.toString())
    }

    @Test
    fun installationDetailEndpointReturnsItemAndHandlesNotFound() = testApplication {
        configureTestApplication()
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }
        val sessionCookie = extractCookie(authResp.headers.getAll("Set-Cookie").orEmpty(), "nuecagram_webapp_session")

        val detailResp = client.get("/nuecagram/api/webapp/installations/${installation.id}") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(detailResp.status).isEqualTo(HttpStatusCode.OK)
        val item = json.decodeFromString<TestInstallationPayload>(detailResp.bodyAsText())
        assertThat(item.id).isEqualTo(installation.id.toString())

        val notFoundResp = client.get("/nuecagram/api/webapp/installations/00000000-0000-0000-0000-000000000000") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(notFoundResp.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun muteEndpointRequiresCsrfAndUpdatesMuteStatus() = testApplication {
        configureTestApplication()
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }
        val setCookies = authResp.headers.getAll("Set-Cookie").orEmpty()
        val sessionCookie = extractCookie(setCookies, "nuecagram_webapp_session")
        val csrfCookie = extractCookie(setCookies, "nuecagram_webapp_csrf")
        val authPayload = json.decodeFromString<DashboardTestAuthPayload>(authResp.bodyAsText())


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
            header("Cookie", "nuecagram_webapp_session=$sessionCookie; nuecagram_webapp_csrf=$csrfCookie")
            header("X-CSRF-Token", authPayload.csrf)
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
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }
        val setCookies = authResp.headers.getAll("Set-Cookie").orEmpty()
        val sessionCookie = extractCookie(setCookies, "nuecagram_webapp_session")
        val csrfCookie = extractCookie(setCookies, "nuecagram_webapp_csrf")
        val authPayload = json.decodeFromString<DashboardTestAuthPayload>(authResp.bodyAsText())


        val testResp = client.post("/nuecagram/api/webapp/installations/${installation.id}/test") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sessionCookie; nuecagram_webapp_csrf=$csrfCookie")
            header("X-CSRF-Token", authPayload.csrf)
        }
        assertThat(testResp.status).isEqualTo(HttpStatusCode.OK)
        val testPayload = json.decodeFromString<TestActionResponsePayload>(testResp.bodyAsText())
        assertThat(testPayload.success).isTrue()

        val delivered = sentMessages().last()
        assertThat(delivered.chatId).isEqualTo(installation.telegramChatId.toString())
        assertThat(delivered.text).contains(installation.id.toString())
    }
}
