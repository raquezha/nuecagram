package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import net.raquezha.nuecagram.db.redactedUrl
import org.junit.Test

@Suppress("TooManyFunctions")
class PlatformAdminUiTest : BaseEventTestHelper() {
    @Test
    fun redactedUrlPreservesPlainNamesAndSanitizesUrls() {
        assertThat("Nuecagram Prod".redactedUrl()).isEqualTo("Nuecagram Prod")
        assertThat("Project #1234".redactedUrl()).isEqualTo("Project #1234")
        assertThat("nuecagram".redactedUrl()).isEqualTo("nuecagram")
        assertThat("🎉 NUECAGRAM 🚀".redactedUrl()).isEqualTo("🎉 NUECAGRAM 🚀")
        assertThat("<script>alert(1)</script>".redactedUrl()).isEqualTo("<script>alert(1)</script>")
        assertThat("".redactedUrl()).isEqualTo("")
        assertThat("https://user:pass@gitlab.com/group?token=abc#frag".redactedUrl())
            .isEqualTo("https://gitlab.com/group")
        assertThat("https://user:pass@gitlab.internal.corp:8443/org/repo?key=val#hash".redactedUrl())
            .isEqualTo("https://gitlab.internal.corp:8443/org/repo")
        assertThat("http://admin:secret@127.0.0.1:8080/path".redactedUrl())
            .isEqualTo("http://127.0.0.1:8080/path")
        assertThat("https://".redactedUrl()).isEqualTo("https://")
    }
    @Test
    fun platformAdminLoginShowsRedactedInstallationAndAuditViews() =
        testApplication {
            configureTestApplication()
            val installationWithCredentialInUrl =
                runBlocking {
                    installationRepository.createInstallation(
                        gitlabBaseUrl = "https://url-user:url-secret@gitlab.example/group?token=url-token",
                        gitlabProjectId = null,
                        telegramChatId = 900001,
                        telegramTopicId = null,
                    )
                }
            runBlocking {
                installationRepository.writeAuditEvent(
                    installationId = installation.id,
                    actorType = "telegram_user",
                    actorId = "sensitive-actor-id",
                    action = "setup",
                    metadataJson = "{\"credential\":\"must-not-appear\"}",
                    metadataPatch = net.raquezha.nuecagram.db.AuditMetadataPatch(
                        actorUsername = "alice",
                    ),
                )
            }
            val noRedirectClient = client.config { followRedirects = false }
            val sessionCookie = login(noRedirectClient)

            val response =
                client.get("${basePath()}/admin") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-Forwarded-Proto", "https")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = response.bodyAsText()
            assertThat(body).contains(installation.id.toString().take(8))
            assertThat(body).contains(installation.gitlabBaseUrl)
            assertThat(body).contains(installationWithCredentialInUrl.id.toString().take(8))
            assertThat(body).contains("https://gitlab.example/group")
            assertThat(body).contains("setup")
            assertThat(body).contains("@alice")
            assertThat(body).contains(installation.repoName)
            assertThat(body).contains("Repository")
            assertThat(body).contains("Notification Label")
            assertThat(body).contains("Active installations")
            assertThat(body).contains("Muted installations")
            assertThat(body).contains("24h Audit events")
            assertThat(body).contains("System status")
            assertThat(body).contains("View all installations →")
            assertThat(body).contains("Explore audit logs →")
            assertThat(body).doesNotContain("sensitive-actor-id")
            assertThat(body).doesNotContain("must-not-appear")
            assertThat(body).doesNotContain("url-user")
            assertThat(body).doesNotContain("url-secret")
            assertThat(body).doesNotContain("url-token")
            assertThat(body).doesNotContain(installation.telegramChatId.toString())
            assertThat(body).doesNotContain("management link")
            assertThat(response.headers["Content-Security-Policy"]).contains("default-src 'none'")
        }

    @Test
    fun platformAdminInstallationsDirectorySupportsSearchFiltersPaginationAndRedaction() =
        testApplication {
            configureTestApplication()
            val suiteTag = "platform-dir"
            seedPlatformInstallations(suiteTag)
            val noRedirectClient = client.config { followRedirects = false }
            val sessionCookie = login(noRedirectClient)

            val pageOne = fetchInstallationsPage(sessionCookie, "search=$suiteTag", secure = true)
            assertThat(pageOne.status).isEqualTo(HttpStatusCode.OK)
            val pageOneBody = pageOne.bodyAsText()
            assertThat(pageOneBody).contains("Installations directory")
            assertThat(pageOneBody).contains("Back to Overview")
            assertThat(pageOneBody).contains("Repository")
            assertThat(pageOneBody).contains("Notification Label")
            assertThat(pageOneBody).contains("1 / 2")
            assertThat(pageOneBody).contains("21 total")
            assertThat(pageOneBody).contains("Showing 1–20 of 21")
            assertThat(pageOneBody).contains("Next ›")
            assertThat(pageOneBody).doesNotContain("910020")
            assertThat(pageOneBody).doesNotContain("920020")

            val pageTwoBody = fetchInstallationsPage(sessionCookie, "search=$suiteTag&page=2").bodyAsText()
            assertThat(pageTwoBody).contains("2 / 2")
            assertThat(pageTwoBody).contains("Showing 21–21 of 21")
            assertThat(pageTwoBody).contains("‹ Prev")
            assertThat(pageTwoBody).contains("https://${suiteTag}-0.example.com/group")
            assertThat(pageTwoBody).doesNotContain("user:secret")
            assertThat(pageTwoBody).doesNotContain("token=hidden")
            assertThat(pageTwoBody).doesNotContain("910020")
            assertThat(pageTwoBody).doesNotContain("920020")

            val mutedOnly = fetchInstallationsPage(sessionCookie, "search=$suiteTag&status=muted")
            val mutedBody = mutedOnly.bodyAsText()
            assertThat(mutedBody).contains("Muted")
            assertThat(mutedBody).contains("1 total")
            assertThat(mutedBody).contains("https://${suiteTag}-target.example.com/group")
            assertThat(mutedBody).doesNotContain("https://${suiteTag}-0.example.com/group")
            assertThat(mutedOnly.headers["Content-Security-Policy"]).contains("default-src 'none'")
        }

    @Test
    fun platformAdminInstallationsDirectoryShowsEmptyState() =
        testApplication {
            configureTestApplication()
            val noRedirectClient = client.config { followRedirects = false }
            val sessionCookie = login(noRedirectClient)

            val response =
                client.get("${basePath()}/admin/installations?search=no-such-installation") {
                    header(HttpHeaders.Cookie, sessionCookie)
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.bodyAsText()).contains("No installations match the current search and filters.")
        }

    @Test
    fun platformAdminAuditExplorerSupportsFiltersPaginationRedactionAndEmptyState() =
        testApplication {
            configureTestApplication()
            val noRedirectClient = client.config { followRedirects = false }
            val sessionCookie = login(noRedirectClient)

            seedAuditExplorerEvents()
            assertAuditExplorerPage(sessionCookie)
            assertRotateAuditFilter(sessionCookie)

            val readRepo = net.raquezha.nuecagram.db.PlatformAdminReadRepository()
            val initialStatusChangeCount =
                readRepo.auditEventsPage(action = "status_change", limit = 1).totalCount

            seedSystemAuditFallback()
            assertSystemAuditFallbacks(sessionCookie)

            if (initialStatusChangeCount == 0L) {
                val emptyFilter =
                    client.get("${basePath()}/admin/audit?action=status_change") {
                        header(HttpHeaders.Cookie, sessionCookie)
                    }
                assertThat(emptyFilter.status).isEqualTo(HttpStatusCode.OK)
                assertThat(emptyFilter.bodyAsText()).contains("No audit events match the current filter.")
            }
        }

    @Test
    fun platformAdminLoginIsThrottledAfterRepeatedFailures() =
        testApplication {
            configureTestApplication()
            val noRedirectClient = client.config { followRedirects = false }
            val loginForm = loginForm(noRedirectClient)

            repeat(5) {
                val response = submitLogin(noRedirectClient, loginForm, "wrong-password")
                assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
            }

            val throttled = submitLogin(noRedirectClient, loginForm, TEST_ADMIN_PASSWORD)
            assertThat(throttled.status).isEqualTo(HttpStatusCode.TooManyRequests)
        }

    @Test
    fun platformAdminLogoutRequiresCsrfAndInvalidatesSession() =
        testApplication {
            configureTestApplication()
            val noRedirectClient = client.config { followRedirects = false }
            val sessionCookie = login(noRedirectClient)
            val page =
                client.get("${basePath()}/admin") {
                    header(HttpHeaders.Cookie, sessionCookie)
                }
            val csrf = hiddenValue(page.bodyAsText(), "csrf")

            val rejected =
                noRedirectClient.post("${basePath()}/admin/logout") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    setBody(FormDataContent(Parameters.build { append("csrf", "invalid") }))
                }
            assertThat(rejected.status).isEqualTo(HttpStatusCode.Forbidden)

            val logout =
                noRedirectClient.post("${basePath()}/admin/logout") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    setBody(FormDataContent(Parameters.build { append("csrf", csrf) }))
                }
            assertThat(logout.status).isEqualTo(HttpStatusCode.Found)
            assertThat(logout.headers[HttpHeaders.SetCookie].orEmpty()).contains("Max-Age=0")

            val afterLogout =
                client.get("${basePath()}/admin") {
                    header(HttpHeaders.Cookie, sessionCookie)
                }
            assertThat(afterLogout.status).isEqualTo(HttpStatusCode.Unauthorized)
        }

    @Test
    fun expiredPlatformAdminSessionIsRejected() =
        testApplication {
            configureTestApplication()
            val expired = runBlocking {
                installationRepository.issuePlatformAdminSession(
                    Instant.now().minus(1, ChronoUnit.MINUTES),
                )
            }

            val response =
                client.get("${basePath()}/admin") {
                    header(HttpHeaders.Cookie, "nuecagram_admin_session=${expired.raw}")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(response.headers[HttpHeaders.SetCookie].orEmpty()).contains("Max-Age=0")
        }

    private suspend fun ApplicationTestBuilder.seedAuditExplorerEvents() {
        runBlocking {
            repeat(21) { index ->
                installationRepository.writeAuditEvent(
                    installationId = installation.id,
                    actorType = "telegram_user",
                    actorId = "sensitive-actor-$index",
                    action = if (index == 20) "telegram_rotate" else "telegram_setup",
                    metadataJson = "{\"secret\":\"do-not-leak-$index\"}",
                    metadataPatch = net.raquezha.nuecagram.db.AuditMetadataPatch(
                        actorUsername = "user$index",
                    ),
                )
            }
        }
    }

    private suspend fun ApplicationTestBuilder.seedSystemAuditFallback() {
        runBlocking {
            installationRepository.writeAuditEvent(
                installationId = null,
                actorType = "system",
                actorId = null,
                action = "system_sync",
            )
        }
    }

    private suspend fun ApplicationTestBuilder.assertAuditExplorerPage(sessionCookie: String) {
        val pageOne =
            client.get("${basePath()}/admin/audit") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
        assertThat(pageOne.status).isEqualTo(HttpStatusCode.OK)
        val pageOneBody = pageOne.bodyAsText()
        assertThat(pageOneBody).contains("Audit log explorer")
        assertThat(pageOneBody).contains("Back to Overview")
        assertThat(pageOneBody).contains("Showing 1–20 of")
        assertThat(pageOneBody).contains("Next ›")
        assertThat(pageOneBody).contains("telegram_setup")
        assertThat(pageOneBody).contains("Repository")
        assertThat(pageOneBody).contains("Actor")
        assertThat(pageOneBody).contains("Chat &amp; Details")
        assertThat(pageOneBody).contains("@user")
        assertThat(pageOneBody).contains(installation.repoName)
        assertThat(pageOneBody).contains(installation.telegramChatId.toString())
        assertThat(pageOneBody).doesNotContain("sensitive-actor")
        assertThat(pageOneBody).doesNotContain("do-not-leak")
        assertThat(pageOne.headers["Content-Security-Policy"]).contains("default-src 'none'")
    }

    private suspend fun ApplicationTestBuilder.assertRotateAuditFilter(sessionCookie: String) {
        val rotateFilter =
            client.get("${basePath()}/admin/audit?action=rotate") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
        assertThat(rotateFilter.status).isEqualTo(HttpStatusCode.OK)
        val rotateBody = rotateFilter.bodyAsText()
        assertThat(rotateBody).contains("total")
        assertThat(rotateBody).contains("telegram_rotate")
        assertThat(rotateBody).doesNotContain("telegram_setup")
    }

    private suspend fun ApplicationTestBuilder.assertSystemAuditFallbacks(sessionCookie: String) {
        val fallbackPage =
            client.get("${basePath()}/admin/audit") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
        val fallbackBody = fallbackPage.bodyAsText()
        assertThat(fallbackBody).contains("System")
        assertThat(fallbackBody).contains("Unknown Actor")
        assertThat(fallbackBody).contains("Unknown Chat")
    }

    private suspend fun ApplicationTestBuilder.seedPlatformInstallations(suiteTag: String) {
        val seeded =
            runBlocking {
                buildList {
                    repeat(21) { index ->
                        add(
                            installationRepository.createInstallation(
                                gitlabBaseUrl =
                                    if (index == 20) {
                                        "https://user:secret@${suiteTag}-target.example.com/group?token=hidden"
                                    } else {
                                        "https://$suiteTag-$index.example.com/group"
                                    },
                                gitlabProjectId = 8000L + index,
                                telegramChatId = 910000L + index,
                                telegramTopicId = 920000L + index,
                            ),
                        )
                    }
                }
            }
        runBlocking {
            installationRepository.setMuted(seeded.last().id, true)
        }
    }

    private suspend fun ApplicationTestBuilder.fetchInstallationsPage(
        sessionCookie: String,
        query: String,
        secure: Boolean = false,
    ): HttpResponse =
        client.get("${basePath()}/admin/installations?$query") {
            header(HttpHeaders.Cookie, sessionCookie)
            if (secure) {
                header("X-Forwarded-Proto", "https")
            }
        }

    private suspend fun ApplicationTestBuilder.login(noRedirectClient: HttpClient): String {
        val form = loginForm(noRedirectClient)
        val response = submitLogin(noRedirectClient, form, TEST_ADMIN_PASSWORD)
        assertThat(response.status).isEqualTo(HttpStatusCode.Found)
        assertThat(response.headers[HttpHeaders.Location]).isEqualTo("${basePath()}/admin")
        val cookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        assertThat(cookies.joinToString()).contains("HttpOnly")
        assertThat(cookies.joinToString()).contains("SameSite=Strict")
        return cookies
            .filterNot { it.startsWith("nuecagram_admin_login_csrf=") }
            .joinToString("; ") { it.substringBefore(';') }
    }

    private suspend fun ApplicationTestBuilder.loginForm(noRedirectClient: HttpClient): LoginForm {
        val response = noRedirectClient.get("${basePath()}/admin/login")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        return LoginForm(
            csrf = hiddenValue(response.bodyAsText(), "csrf"),
            cookie = response.headers[HttpHeaders.SetCookie].orEmpty().substringBefore(';'),
        )
    }

    private suspend fun ApplicationTestBuilder.submitLogin(
        noRedirectClient: HttpClient,
        form: LoginForm,
        password: String,
    ): HttpResponse =
        noRedirectClient.post("${basePath()}/admin/login") {
            header(HttpHeaders.Cookie, form.cookie)
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("csrf", form.csrf)
                        append("password", password)
                    },
                ),
            )
        }

    private fun hiddenValue(body: String, name: String): String =
        body.substringAfter("name=\"$name\" value=\"").substringBefore('"')

    private fun basePath(): String = configuredBasePath()

    private data class LoginForm(val csrf: String, val cookie: String)

    companion object {
        const val TEST_ADMIN_PASSWORD = "test-admin-password"
    }
}
