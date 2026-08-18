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
import org.junit.Test

class PlatformAdminUiTest : BaseEventTestHelper() {
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
            assertThat(body).contains(installation.id.toString())
            assertThat(body).contains(installation.gitlabBaseUrl)
            assertThat(body).contains(installationWithCredentialInUrl.id.toString())
            assertThat(body).contains("https://gitlab.example/group")
            assertThat(body).contains("setup")
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
