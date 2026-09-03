@file:Suppress("TooManyFunctions")

package net.raquezha.nuecagram.webapp

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.delete
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val repoName: String,
    val chatName: String? = null,
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

@Serializable
private data class TestCreateInstallationResponsePayload(
    val installation: TestInstallationPayload,
    val credential: String,
    val webhookUrl: String,
)

@Serializable
private data class TestDestinationPayload(
    val id: String,
    val name: String,
    val telegramChatId: Long,
    val telegramTopicId: Long? = null,
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
        val item = items.first { it.id == installation.id.toString() }
        assertThat(item.repoName).isEqualTo(installation.repoName)
        assertThat(item.chatName).isNull()
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
        assertThat(item.repoName).isEqualTo(installation.repoName)
        assertThat(item.chatName).isNull()

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
    fun identityEndpointRequiresCsrfAndUpdatesNames() = testApplication {
        configureTestApplication()
        val (sessionCookie, csrf) = issueSessionWithNonce(
            client,
            userId = 9999L,
            chatId = installation.telegramChatId,
            topicId = installation.telegramTopicId,
        )

        val rejectCsrfResp = client.post("/nuecagram/api/webapp/installations/${installation.id}/identity") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
            setBody("""{"repoName":"renamed","chatName":"Deployments"}""")
        }
        assertThat(rejectCsrfResp.status).isEqualTo(HttpStatusCode.Forbidden)

        val updateResp = client.post("/nuecagram/api/webapp/installations/${installation.id}/identity") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
            header("X-CSRF-Token", csrf)
            setBody("""{"repoName":"  renamed  ","chatName":"   "}""")
        }
        assertThat(updateResp.status).isEqualTo(HttpStatusCode.OK)
        val updatedPayload = json.decodeFromString<TestInstallationPayload>(updateResp.bodyAsText())
        assertThat(updatedPayload.repoName).isEqualTo("renamed")
        assertThat(updatedPayload.chatName).isNull()

        val updatedContext = runBlocking { installationRepository.installationAdminContext(installation.id) }
        assertThat(updatedContext?.repoName).isEqualTo("renamed")
        assertThat(updatedContext?.chatName).isNull()
    }

    @Test
    fun identityEndpointHandlesEmojiUnicodeAndLongStringsGracefully() = testApplication {
        configureTestApplication()
        val (sessionCookie, csrf) = issueSessionWithNonce(
            client,
            userId = 9998L,
            chatId = installation.telegramChatId,
            topicId = installation.telegramTopicId,
        )

        val longName = "A".repeat(300)
        val emojiName = "🚀 Mobile App 📱 Nüeçagrăm <script>alert(1)</script>"

        val updateResp = client.post("/nuecagram/api/webapp/installations/${installation.id}/identity") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
            header("X-CSRF-Token", csrf)
            setBody("""{"repoName":"$longName","chatName":"$emojiName"}""")
        }
        assertThat(updateResp.status).isEqualTo(HttpStatusCode.OK)
        val updatedPayload = json.decodeFromString<TestInstallationPayload>(updateResp.bodyAsText())
        assertThat(updatedPayload.repoName).hasLength(255)
        assertThat(updatedPayload.chatName).isEqualTo(emojiName)
    }

    @Test
    fun identityEndpointRejectsBlankAndLegacyRepoNames() = testApplication {
        configureTestApplication()
        val (sessionCookie, csrf) = issueSessionWithNonce(
            client,
            userId = 9999L,
            chatId = installation.telegramChatId,
            topicId = installation.telegramTopicId,
        )

        listOf("", "Unknown Repository").forEach { repoName ->
            val response = client.post("/nuecagram/api/webapp/installations/${installation.id}/identity") {
                contentType(ContentType.Application.Json)
                header("Cookie", "nuecagram_webapp_session=$sessionCookie")
                header("X-CSRF-Token", csrf)
                setBody("""{"repoName":"$repoName","chatName":"Deployments"}""")
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }
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
        assertThat(delivered.text).contains(installation.repoName)
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

        val tempJsFile = java.io.File.createTempFile("app", ".js")
        try {
            tempJsFile.writeText(js)
            val process = ProcessBuilder("node", "--check", tempJsFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) { "JS Syntax error in app.js:\n$output" }
            assertThat(exitCode).isEqualTo(0)
        } finally {
            tempJsFile.delete()
        }
    }

    @Test
    fun allJsElementReferencesExistInShellHtmlOrJsTemplates() = testApplication {
        configureTestApplication()
        val jsResponse = client.get("/nuecagram/webapp/app.js")
        assertThat(jsResponse.status).isEqualTo(HttpStatusCode.OK)
        val js = jsResponse.bodyAsText()

        val htmlResponse = client.get("/nuecagram/webapp")
        assertThat(htmlResponse.status).isEqualTo(HttpStatusCode.OK)
        val html = htmlResponse.bodyAsText()

        val referencedIds = Regex("""getElementById\('([^']+)'\)""")
            .findAll(js)
            .map { it.groupValues[1] }
            .toSet()

        assertThat(referencedIds).isNotEmpty()

        for (id in referencedIds) {
            val existsInHtml = html.contains("id=\"$id\"")
            val existsInJsTemplates = js.contains("id=\"$id\"")
            check(existsInHtml || existsInJsTemplates) {
                "JS references DOM element id='$id' which does not exist in shell HTML or JS templates"
            }
        }
    }

    @Test
    fun webAppScriptContainsLoadingAndErrorRecoveryGuards() = testApplication {
        configureTestApplication()
        val html = client.get("/nuecagram/webapp").bodyAsText()
        val js = client.get("/nuecagram/webapp/app.js").bodyAsText()

        assertThat(html).contains("#screen-loading { display: none;")
        assertThat(html).contains("#screen-loading.active { display: flex; }")
        assertThat(js).contains("if (name !== 'loading' && loadingTimer)")
        assertThat(js).contains("clearInterval(loadingTimer);")
        assertThat(js).contains("showScreen('list');")
        assertThat(js).contains("document.querySelector('#screen-list .top-actions')")
        assertThat(js).contains("topActions.style.display = 'flex'")
        assertThat(js).contains("catch (e) {")
    }

    @Test
    fun rotateAndDeleteConfirmScreensAreDefinedInHtmlAndJs() = testApplication {
        configureTestApplication()
        val html = client.get("/nuecagram/webapp").bodyAsText()
        val js = client.get("/nuecagram/webapp/app.js").bodyAsText()

        assertThat(html).contains("id=\"screen-rotate-confirm\"")
        assertThat(html).contains("id=\"screen-delete-confirm\"")
        assertThat(html).contains("id=\"rotConfirmTitle\"")
        assertThat(html).contains("id=\"delConfirmTitle\"")
        assertThat(html).contains("id=\"btnConfirmRotate\"")
        assertThat(html).contains("id=\"btnConfirmDelete\"")
        assertThat(html).contains("Make sure both <strong>you</strong>")
        assertThat(html).contains("and <strong>@NuecagramBot</strong> are <strong>Administrators</strong>")
        assertThat(js).contains("openRotateConfirm")
        assertThat(js).contains("confirmRotateInstallation")
        assertThat(js).contains("openDeleteConfirm")
        assertThat(js).contains("confirmDeleteInstallation")
        assertThat(js).contains("updateChatNameFromDestination")
    }

    @Test
    fun nonAdminUserIsRejectedWithForbidden() = testApplication {
        configureTestApplication()
        val groupChatId = -100123456L
        mockTelegramService.setChatMemberStatus(groupChatId, 7777L, "member")
        val nonce = runBlocking {
            installationRepository.issueLaunchNonce(
                telegramChatId = groupChatId,
                telegramTopicId = null,
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

    @Test
    fun createInstallationInDmSessionWithTargetChatId() = testApplication {
        configureTestApplication()
        val targetChatId = -100987654L
        mockTelegramService.setChatMemberStatus(targetChatId, 9999L, "administrator")
        val (sessionCookie, csrf) = issueSessionWithNonce(
            client,
            userId = 9999L,
            chatId = 9999L,
            topicId = null,
        )

        val createResp = client.post("/nuecagram/api/webapp/installations") {
            contentType(ContentType.Application.Json)
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
            header("X-CSRF-Token", csrf)
            setBody(
                """
                {
                    "repoName": "Project #456",
                    "gitlabBaseUrl": "https://gitlab.example.com",
                    "gitlabProjectId": 456,
                    "telegramChatId": $targetChatId
                }
                """.trimIndent(),
            )
        }
        assertThat(createResp.status).isEqualTo(HttpStatusCode.Created)
        val created = json.decodeFromString<TestCreateInstallationResponsePayload>(createResp.bodyAsText())
        assertThat(created.installation.telegramChatId).isEqualTo(targetChatId)
        assertThat(created.installation.repoName).isEqualTo("Project #456")
        assertThat(created.installation.chatName).isNull()
        assertThat(created.installation.gitlabBaseUrl).isEqualTo("https://gitlab.example.com")
    }

    @Test
    fun installationsEndpointAcceptsBearerTokenHeader() = testApplication {
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
            header("Authorization", "Bearer $token")
        }
        assertThat(listResp.status).isEqualTo(HttpStatusCode.OK)
        val items = json.decodeFromString<List<TestInstallationPayload>>(listResp.bodyAsText())
        assertThat(items).isNotEmpty()
    }

    @Test
    fun mutatingEndpointsAcceptSessionHeaderWithoutCookie() = testApplication {
        configureTestApplication()
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 9999L, "administrator")
        val nonce = runBlocking {
            installationRepository.issueLaunchNonce(
                telegramChatId = installation.telegramChatId,
                telegramTopicId = installation.telegramTopicId,
                telegramUserId = 9999L,
                expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            )
        }
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData","startParam":"nonce_${nonce.raw}"}""")
        }
        assertThat(authResp.status).isEqualTo(HttpStatusCode.OK)
        val authPayload = json.decodeFromString<DashboardTestAuthPayload>(authResp.bodyAsText())
        val token = authPayload.sessionToken!!
        val csrf = authPayload.csrf

        // Mute via X-Session-Token
        val muteResp = client.post("/nuecagram/api/webapp/installations/${installation.id}/mute") {
            contentType(ContentType.Application.Json)
            header("X-Session-Token", token)
            header("X-CSRF-Token", csrf)
            setBody("""{"muted":true}""")
        }
        assertThat(muteResp.status).isEqualTo(HttpStatusCode.OK)

        // Test delivery via X-Session-Token
        val testResp = client.post("/nuecagram/api/webapp/installations/${installation.id}/test") {
            contentType(ContentType.Application.Json)
            header("X-Session-Token", token)
            header("X-CSRF-Token", csrf)
        }
        assertThat(testResp.status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun rotateEndpointAcceptsSessionHeaderWithoutCookie() = testApplication {
        configureTestApplication()
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 9999L, "administrator")
        runBlocking { installationRepository.upsertTelegramPrivateChat(9999L, installation.telegramChatId) }
        val nonce = runBlocking {
            installationRepository.issueLaunchNonce(
                telegramChatId = installation.telegramChatId,
                telegramTopicId = installation.telegramTopicId,
                telegramUserId = 9999L,
                expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            )
        }
        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData","startParam":"nonce_${nonce.raw}"}""")
        }
        assertThat(authResp.status).isEqualTo(HttpStatusCode.OK)
        val authPayload = json.decodeFromString<DashboardTestAuthPayload>(authResp.bodyAsText())
        val token = authPayload.sessionToken!!
        val csrf = authPayload.csrf

        val rotateResp = client.post("/nuecagram/api/webapp/installations/${installation.id}/rotate") {
            contentType(ContentType.Application.Json)
            header("X-Session-Token", token)
            header("X-CSRF-Token", csrf)
        }
        assertThat(rotateResp.status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun demotedAdminDoesNotSeeStaleInstallationsInUnscopedWebAppSession() = testApplication {
        configureTestApplication()
        runBlocking { installationRepository.recordInstallationAdmin(installation.id, 9999L) }
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 9999L, "member")

        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }
        val setCookies = authResp.headers.getAll("Set-Cookie").orEmpty()
        val sessionCookie = extractCookie(setCookies, "nuecagram_webapp_session")

        val listResp = client.get("/nuecagram/api/webapp/installations?scope=all") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(listResp.status).isEqualTo(HttpStatusCode.OK)
        val items = json.decodeFromString<List<TestInstallationPayload>>(listResp.bodyAsText())
        assertThat(items).isEmpty()
    }

    @Test
    fun demotedAdminDoesNotSeeStaleDestinationsInWebApp() = testApplication {
        configureTestApplication()
        runBlocking { installationRepository.recordInstallationAdmin(installation.id, 9999L) }
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 9999L, "member")

        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }
        val setCookies = authResp.headers.getAll("Set-Cookie").orEmpty()
        val sessionCookie = extractCookie(setCookies, "nuecagram_webapp_session")

        val destResp = client.get("/nuecagram/api/webapp/destinations") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(destResp.status).isEqualTo(HttpStatusCode.OK)
        val items = json.decodeFromString<List<TestDestinationPayload>>(destResp.bodyAsText())
        assertThat(items.map { it.telegramChatId }).doesNotContain(installation.telegramChatId)
    }

    @Test
    fun demotedBotDoesNotSeeDestinationsInWebApp() = testApplication {
        configureTestApplication()
        runBlocking { installationRepository.recordInstallationAdmin(installation.id, 9999L) }
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 9999L, "administrator")
        // Demote the bot itself in the group chat (bot ID is 10001L)
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 10001L, "member")

        val botToken = testConfig.botApi
        val initData = buildTestInitData(botToken, userId = 9999L)
        val authResp = client.post("/nuecagram/api/webapp/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"initData":"$initData"}""")
        }
        val setCookies = authResp.headers.getAll("Set-Cookie").orEmpty()
        val sessionCookie = extractCookie(setCookies, "nuecagram_webapp_session")

        val destResp = client.get("/nuecagram/api/webapp/destinations") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(destResp.status).isEqualTo(HttpStatusCode.OK)
        val items = json.decodeFromString<List<TestDestinationPayload>>(destResp.bodyAsText())
        assertThat(items.map { it.telegramChatId }).doesNotContain(installation.telegramChatId)
    }

    @Test
    fun groupContextRejectsDemotedBotSession() = testApplication {
        configureTestApplication()
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 9999L, "administrator")
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 10001L, "member")

        val (sessionCookie, _) = issueSessionWithNonce(client, userId = 9999L, chatId = installation.telegramChatId)

        val listResp = client.get("/nuecagram/api/webapp/installations") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(listResp.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun deleteEndpointSoftDeletesInstallationAndEnforcesAuthzAnd410OnWebhook() = testApplication {
        configureTestApplication()
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 9999L, "administrator")
        runBlocking { installationRepository.upsertTelegramPrivateChat(9999L, installation.telegramChatId) }

        val (sessionCookie, csrf) = issueSessionWithNonce(client, userId = 9999L, chatId = installation.telegramChatId)

        // Attempt delete without CSRF header -> Forbidden
        val noCsrfResp = client.delete("/nuecagram/api/webapp/installations/${installation.id}") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(noCsrfResp.status).isEqualTo(HttpStatusCode.Forbidden)

        // Non-admin attempt -> Forbidden
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 8888L, "member")
        val (nonAdminCookie, nonAdminCsrf) = issueSessionWithNonce(
            client,
            userId = 8888L,
            chatId = installation.telegramChatId,
        )
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 8888L, "member")

        val nonAdminResp = client.delete("/nuecagram/api/webapp/installations/${installation.id}") {
            header("Cookie", "nuecagram_webapp_session=$nonAdminCookie")
            header("X-CSRF-Token", nonAdminCsrf)
        }
        assertThat(nonAdminResp.status).isEqualTo(HttpStatusCode.Forbidden)

        // Authorized admin soft-deletes installation
        val deleteResp = client.delete("/nuecagram/api/webapp/installations/${installation.id}") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
            header("X-CSRF-Token", csrf)
        }
        assertThat(deleteResp.status).isEqualTo(HttpStatusCode.NoContent)

        // Repeat delete returns 404 (already deleted)
        val repeatDeleteResp = client.delete("/nuecagram/api/webapp/installations/${installation.id}") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
            header("X-CSRF-Token", csrf)
        }
        assertThat(repeatDeleteResp.status).isEqualTo(HttpStatusCode.NotFound)

        // Detail endpoint returns 404
        val getResp = client.get("/nuecagram/api/webapp/installations/${installation.id}") {
            header("Cookie", "nuecagram_webapp_session=$sessionCookie")
        }
        assertThat(getResp.status).isEqualTo(HttpStatusCode.NotFound)

        // Webhook request targeting the soft-deleted installation returns 410 Gone
        val webhookResp = client.post("/nuecagram/webhook") {
            header("X-Gitlab-Event", "Push Hook")
            header("X-Gitlab-Token", webhookToken)
            setBody(
                """
                {
                  "object_kind": "push",
                  "event_name": "push",
                  "ref": "refs/heads/main",
                  "user_name": "Test User",
                  "project": {"name": "Test Project", "web_url": "https://gitlab.com/test/project"},
                  "commits": []
                }
                """.trimIndent(),
            )
        }
        assertThat(webhookResp.status).isEqualTo(HttpStatusCode.Gone)
    }

    @Test
    fun concurrentDeleteRequestsHandledIdempotently() = testApplication {
        configureTestApplication()
        mockTelegramService.setChatMemberStatus(installation.telegramChatId, 9999L, "administrator")
        runBlocking { installationRepository.upsertTelegramPrivateChat(9999L, installation.telegramChatId) }

        val (sessionCookie, csrf) = issueSessionWithNonce(
            client,
            userId = 9999L,
            chatId = installation.telegramChatId,
        )

        val statuses = runBlocking {
            coroutineScope {
                val req1 = async {
                    client.delete("/nuecagram/api/webapp/installations/${installation.id}") {
                        header("Cookie", "nuecagram_webapp_session=$sessionCookie")
                        header("X-CSRF-Token", csrf)
                    }.status
                }
                val req2 = async {
                    client.delete("/nuecagram/api/webapp/installations/${installation.id}") {
                        header("Cookie", "nuecagram_webapp_session=$sessionCookie")
                        header("X-CSRF-Token", csrf)
                    }.status
                }
                awaitAll(req1, req2)
            }
        }

        assertThat(statuses).containsExactly(HttpStatusCode.NoContent, HttpStatusCode.NotFound)
    }
}
