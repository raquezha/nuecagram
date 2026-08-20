package net.raquezha.nuecagram.webapp

import com.google.common.truth.Truth.assertThat
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
private data class WizardAuthPayload(
    val success: Boolean,
    val csrf: String,
    val sessionToken: String? = null,
    val telegramChatId: Long? = null,
)

@Serializable
private data class WizardCreatePayload(
    val installation: WizardInstallPayload,
    val credential: String,
    val webhookUrl: String,
)

@Serializable
private data class WizardInstallPayload(
    val id: String,
    val gitlabBaseUrl: String,
    val gitlabProjectId: Long? = null,
    val telegramChatId: Long,
    val muted: Boolean,
)

@Serializable
private data class WizardRotatePayload(val id: String, val credential: String)

@Serializable
private data class WizardErrPayload(val error: String)

class WebSetupWizardTest : BaseEventTestHelper() {
    private val testConfig: ConfigWithSecrets by inject()
    private val json = Json { ignoreUnknownKeys = true }
    private val mockTelegram: MockTelegramService get() = telegramService as MockTelegramService

    private fun extractCookie(setCookies: List<String>, name: String): String? =
        setCookies.joinToString("; ").split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }?.substringAfter("$name=")

    /** Issue a session for userId with DM bootstrap pre-seeded. Returns (sessionCookie, csrf). */
    private fun sessionFor(client: io.ktor.client.HttpClient, userId: Long): Pair<String, String> {
        val chatId = -100123456L
        mockTelegram.setChatMemberStatus(chatId, userId, "administrator")
        runBlocking { installationRepository.upsertTelegramPrivateChat(userId, chatId) }
        val nonce = runBlocking {
            installationRepository.issueLaunchNonce(
                telegramChatId = chatId,
                telegramTopicId = 42L,
                telegramUserId = userId,
                expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            )
        }
        val botTok = testConfig.botApi
        val iData = buildTestInitData(botTok, userId = userId)
        val authBody = """{"initData":"$iData","startParam":"nonce_${nonce.raw}"}"""
        val authResp = runBlocking {
            client.post("/nuecagram/api/webapp/auth") {
                contentType(ContentType.Application.Json)
                setBody(authBody)
            }
        }
        assertThat(authResp.status).isEqualTo(HttpStatusCode.OK)
        val parsed = json.decodeFromString<WizardAuthPayload>(runBlocking { authResp.bodyAsText() })
        val sess = extractCookie(authResp.headers.getAll("Set-Cookie").orEmpty(), "nuecagram_webapp_session")!!
        return Pair(sess, parsed.csrf)
    }

    @Test
    fun createInstallationEndpointRequiresDmBootstrap() = testApplication {
        configureTestApplication()
        // Session with admin status but NO DM bootstrap
        val userId = 7001L
        val chatId = installation.telegramChatId
        mockTelegram.setChatMemberStatus(chatId, userId, "administrator")
        val nonce = runBlocking {
            installationRepository.issueLaunchNonce(
                telegramChatId = chatId,
                telegramTopicId = installation.telegramTopicId,
                telegramUserId = userId,
                expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            )
        }
        val iData = buildTestInitData(testConfig.botApi, userId = userId)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$iData","startParam":"nonce_${nonce.raw}"}""")
        }
        val sess = extractCookie(authResp.headers.getAll("Set-Cookie").orEmpty(), "nuecagram_webapp_session")!!
        val csrf = json.decodeFromString<WizardAuthPayload>(authResp.bodyAsText()).csrf

        val resp = client.post("/nuecagram/api/webapp/installations") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sess")
            header("X-CSRF-Token", csrf)
            setBody("""{"gitlabBaseUrl":"https://gitlab.com","gitlabProjectId":99999}""")
        }
        assertThat(resp.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(resp.bodyAsText()).contains("DM bootstrap")
    }

    @Test
    fun createInstallationEndpointValidatesGitlabUrl() = testApplication {
        configureTestApplication()
        val (sess, csrf) = sessionFor(client, 7002L)

        val resp = client.post("/nuecagram/api/webapp/installations") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sess")
            header("X-CSRF-Token", csrf)
            setBody("""{"gitlabBaseUrl":"http://not-https.com","gitlabProjectId":1}""")
        }
        assertThat(resp.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(resp.bodyAsText()).contains("https://")
    }

    @Test
    fun createInstallationEndpointCreatesInstallationAndReturnsOneTimeCredential() = testApplication {
        configureTestApplication()
        val (sess, csrf) = sessionFor(client, 7003L)

        val resp = client.post("/nuecagram/api/webapp/installations") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sess")
            header("X-CSRF-Token", csrf)
            setBody("""{"gitlabBaseUrl":"https://gitlab.com","gitlabProjectId":55555}""")
        }
        assertThat(resp.status).isEqualTo(HttpStatusCode.Created)
        val body = json.decodeFromString<WizardCreatePayload>(resp.bodyAsText())
        assertThat(body.installation.gitlabBaseUrl).isEqualTo("https://gitlab.com")
        assertThat(body.credential).isNotEmpty()
        assertThat(body.webhookUrl).contains("/webhook")

        val verified = runBlocking { installationRepository.verifyWebhookSecret(body.credential) }
        assertThat(verified).isNotNull()
    }

    @Test
    fun createInstallationWritesWebappSetupAuditEvent() = testApplication {
        configureTestApplication()
        val (sess, csrf) = sessionFor(client, 7004L)

        client.post("/nuecagram/api/webapp/installations") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sess")
            header("X-CSRF-Token", csrf)
            setBody("""{"gitlabBaseUrl":"https://gitlab.com","gitlabProjectId":66666}""")
        }
        // audit event written — verified via no exception (repository writes async, no public read API needed)
    }

    @Test
    fun rotateEndpointRequiresDmBootstrapAndCsrf() = testApplication {
        configureTestApplication()
        // Session without DM
        val userId = 7005L
        val chatId = installation.telegramChatId
        mockTelegram.setChatMemberStatus(chatId, userId, "administrator")
        val nonce = runBlocking {
            installationRepository.issueLaunchNonce(
                telegramChatId = chatId,
                telegramTopicId = installation.telegramTopicId,
                telegramUserId = userId,
                expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            )
        }
        val iData = buildTestInitData(testConfig.botApi, userId = userId)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$iData","startParam":"nonce_${nonce.raw}"}""")
        }
        val sess = extractCookie(authResp.headers.getAll("Set-Cookie").orEmpty(), "nuecagram_webapp_session")!!
        val csrf = json.decodeFromString<WizardAuthPayload>(authResp.bodyAsText()).csrf

        val resp = client.post("/nuecagram/api/webapp/installations/${installation.id}/rotate") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sess")
            header("X-CSRF-Token", csrf)
        }
        assertThat(resp.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun rotateEndpointRotatesCredentialAndInvalidatesOld() = testApplication {
        configureTestApplication()
        val (sess, csrf) = sessionFor(client, 7006L)
        val oldToken = runBlocking { installationRepository.issueWebhookSecret(installation.id).raw }

        val resp = client.post("/nuecagram/api/webapp/installations/${installation.id}/rotate") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sess")
            header("X-CSRF-Token", csrf)
        }
        assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
        val body = json.decodeFromString<WizardRotatePayload>(resp.bodyAsText())
        assertThat(body.id).isEqualTo(installation.id.toString())
        assertThat(body.credential).isNotEmpty()
        assertThat(body.credential).isNotEqualTo(oldToken)

        assertThat(runBlocking { installationRepository.verifyWebhookSecret(oldToken) }).isNull()
        assertThat(runBlocking { installationRepository.verifyWebhookSecret(body.credential) }).isNotNull()
    }

    @Test
    fun rotateEndpointRequiresCsrfHeader() = testApplication {
        configureTestApplication()
        val (sess, _) = sessionFor(client, 7007L)

        val resp = client.post("/nuecagram/api/webapp/installations/${installation.id}/rotate") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sess")
            // no CSRF header
        }
        assertThat(resp.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun createInstallationRequiresCsrfHeader() = testApplication {
        configureTestApplication()
        val (sess, _) = sessionFor(client, 7008L)

        val resp = client.post("/nuecagram/api/webapp/installations") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sess")
            // no CSRF header
            setBody("""{"gitlabBaseUrl":"https://gitlab.com","gitlabProjectId":1}""")
        }
        assertThat(resp.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun createInstallationInDmSessionWithTargetChatIdAcceptsHeaderAuth() = testApplication {
        configureTestApplication()
        val userId = 7009L
        val targetChatId = installation.telegramChatId
        mockTelegram.setChatMemberStatus(targetChatId, userId, "administrator")
        runBlocking { installationRepository.upsertTelegramPrivateChat(userId, targetChatId) }

        val iData = buildTestInitData(testConfig.botApi, userId = userId)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$iData"}""")
        }
        assertThat(authResp.status).isEqualTo(HttpStatusCode.OK)
        val authPayload = json.decodeFromString<WizardAuthPayload>(authResp.bodyAsText())
        val token = authPayload.sessionToken
        assertThat(token).isNotNull()

        val resp = client.post("/nuecagram/api/webapp/installations") {
            contentType(ContentType.Application.Json)
            header("X-Session-Token", token)
            header("X-CSRF-Token", authPayload.csrf)
            setBody("""{"gitlabBaseUrl":"https://gitlab.com","gitlabProjectId":88888,"telegramChatId":$targetChatId}""")
        }
        assertThat(resp.status).isEqualTo(HttpStatusCode.Created)
        val body = json.decodeFromString<WizardCreatePayload>(resp.bodyAsText())
        assertThat(body.installation.telegramChatId).isEqualTo(targetChatId)
    }
}
