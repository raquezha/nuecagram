package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class ManagementUiTest : BaseEventTestHelper() {
    @Before
    fun resetBasePath() {
        System.clearProperty("nuecagram.basePath")
        System.clearProperty("nuecagram.publicUrl")
    }

    @After
    fun clearBasePath() {
        System.clearProperty("nuecagram.basePath")
        System.clearProperty("nuecagram.publicUrl")
    }

    @Test
    fun managementLinkExchangesIntoTokenFreeSession() =
        testApplication {
            configureTestApplication()
            val link = runBlocking {
                installationRepository.issueManagementLink(
                    installation.id,
                    Instant.now().plus(30, ChronoUnit.MINUTES),
                ).raw
            }
            val client = client.config { followRedirects = false }

            val response =
                client.get("${basePath()}/manage/$link") {
                    header("X-Forwarded-Proto", "https")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.Found)
            assertThat(response.headers[HttpHeaders.Location]).isEqualTo("${basePath()}/manage")
            assertThat(response.headers[HttpHeaders.Location]).doesNotContain(link)
            val setCookie = response.headers[HttpHeaders.SetCookie].orEmpty()
            assertThat(setCookie).contains("nuecagram_manage_session=")
            assertThat(setCookie).contains("HttpOnly")
            assertThat(setCookie).contains("Secure")
            assertThat(setCookie).contains("SameSite=Strict")
            assertThat(setCookie).contains("Path=${basePath()}/manage")

            val cookies =
                response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                    .joinToString("; ") { it.substringBefore(';') }
            val page =
                client.get("${basePath()}/manage") {
                    header(HttpHeaders.Cookie, cookies)
                    header("X-Forwarded-Proto", "https")
                }

            assertThat(page.status).isEqualTo(HttpStatusCode.OK)
            val body = page.bodyAsText()
            assertThat(body).contains("Installation Workstation")
            assertThat(body).contains(installation.id.toString())
            assertThat(body).contains("target=\"_blank\"")
            assertThat(body).contains("rel=\"noopener\"")
            assertThat(body).doesNotContain(link)
        }

    @Test
    fun expiredManagementLinkShowsRecoveryWithoutSessionCookie() =
        testApplication {
            configureTestApplication()
            val expiredLink = runBlocking {
                installationRepository.issueManagementLink(
                    installation.id,
                    Instant.now().minus(1, ChronoUnit.MINUTES),
                ).raw
            }

            val response = client.get("${basePath()}/manage/$expiredLink")

            assertThat(response.status).isEqualTo(HttpStatusCode.Gone)
            assertThat(response.bodyAsText()).contains("Recovery")
            assertThat(response.headers[HttpHeaders.SetCookie]).isNull()
        }

    @Test
    fun manageRequiresValidSessionAndClearsCookie() =
        testApplication {
            configureTestApplication()

            val response =
                client.get("${basePath()}/manage") {
                    header(HttpHeaders.Cookie, "nuecagram_manage_session=invalid")
                    header("X-Forwarded-Proto", "https")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(response.bodyAsText()).contains("Recovery")
            val setCookie = response.headers[HttpHeaders.SetCookie].orEmpty()
            assertThat(setCookie).contains("Max-Age=0")
            assertThat(setCookie).contains("HttpOnly")
            assertThat(setCookie).contains("Secure")
        }

    @Test
    fun managementPageAddsSecurityHeadersAndRecoveryGuidance() =
        testApplication {
            configureTestApplication()
            val session = exchangeSessionCookie(client.config { followRedirects = false })

            val response =
                client.get("${basePath()}/manage") {
                    header(HttpHeaders.Cookie, session)
                    header("X-Forwarded-Proto", "https")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers["Content-Security-Policy"]).contains("default-src 'none'")
            assertThat(response.headers["Referrer-Policy"]).isEqualTo("no-referrer")
            assertThat(response.headers["X-Frame-Options"]).isEqualTo("DENY")
            assertThat(response.headers["X-Content-Type-Options"]).isEqualTo("nosniff")
            assertThat(
                response.headers["Strict-Transport-Security"],
            ).isEqualTo("max-age=31536000; includeSubDomains")
            assertThat(response.bodyAsText()).contains("Recovery")
            assertThat(response.bodyAsText()).contains("/manage ${installation.id}")
        }

    @Test
    fun muteToggleUpdatesInstallationStateAndRendersBadge() =
        testApplication {
            configureTestApplication()
            val session = exchangeSessionCookie(client.config { followRedirects = false })
            val noRedirectClient = client.config { followRedirects = false }

            val initialPage =
                client.get("${basePath()}/manage") {
                    header(HttpHeaders.Cookie, session)
                }
            val csrf = hiddenValue(initialPage.bodyAsText(), "csrf")

            val muteResponse =
                noRedirectClient.post("${basePath()}/manage/mute") {
                    header(HttpHeaders.Cookie, session)
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                append("csrf", csrf)
                                append("muted", "true")
                            },
                        ),
                    )
                }

            assertThat(muteResponse.status).isEqualTo(HttpStatusCode.Found)
            assertThat(muteResponse.headers[HttpHeaders.Location]).isEqualTo("${basePath()}/manage")

            val mutedPage =
                client.get("${basePath()}/manage") {
                    header(HttpHeaders.Cookie, session)
                }

            val mutedBody = mutedPage.bodyAsText()
            assertThat(mutedBody).contains("Muted")
            assertThat(mutedBody).contains("Unmute notifications")

            val unmuteResponse =
                noRedirectClient.post("${basePath()}/manage/mute") {
                    header(HttpHeaders.Cookie, session)
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                append("csrf", csrf)
                                append("muted", "false")
                            },
                        ),
                    )
                }

            assertThat(unmuteResponse.status).isEqualTo(HttpStatusCode.Found)

            val unmutedPage =
                client.get("${basePath()}/manage") {
                    header(HttpHeaders.Cookie, session)
                }

            val unmutedBody = unmutedPage.bodyAsText()
            assertThat(unmutedBody).contains("Active")
            assertThat(unmutedBody).contains("Mute notifications")
        }

    @Test
    fun rotateRevealsCredentialOnceAndKeepsManagePageCredentialFree() =
        testApplication {
            configureTestApplication()
            val oldCredential = runBlocking { installationRepository.issueWebhookSecret(installation.id).raw }
            val session = exchangeSessionCookie(client.config { followRedirects = false })

            val managePage =
                client.get("${basePath()}/manage") {
                    header(HttpHeaders.Cookie, session)
                }
            val csrf = hiddenValue(managePage.bodyAsText(), "csrf")
            val rotate =
                client.post("${basePath()}/manage/rotate") {
                    header(HttpHeaders.Cookie, session)
                    header("X-Forwarded-Proto", "https")
                    setBody(FormDataContent(Parameters.build { append("csrf", csrf) }))
                }

            assertThat(rotate.status).isEqualTo(HttpStatusCode.OK)
            val rotateBody = rotate.bodyAsText()
            assertThat(rotateBody).contains("GitLab credential:")
            val rotatedCredential =
                rotateBody.substringAfter("GitLab credential:</strong> <code>").substringBefore("</code>")
            assertThat(rotatedCredential).isNotEqualTo(oldCredential)
            assertThat(runBlocking { installationRepository.verifyWebhookSecret(oldCredential) }).isNull()
            assertThat(runBlocking { installationRepository.verifyWebhookSecret(rotatedCredential) }).isNotNull()

            val page =
                client.get("${basePath()}/manage") {
                    header(HttpHeaders.Cookie, session)
                    header("X-Forwarded-Proto", "https")
                }

            assertThat(page.status).isEqualTo(HttpStatusCode.OK)
            assertThat(page.bodyAsText()).doesNotContain(rotatedCredential)
            assertThat(page.bodyAsText()).contains("Rotate credential")
        }

    @Test
    fun httpManagementSessionOmitsSecureAndHsts() =
        testApplication {
            configureTestApplication()
            val link = runBlocking {
                installationRepository.issueManagementLink(
                    installation.id,
                    Instant.now().plus(30, ChronoUnit.MINUTES),
                ).raw
            }
            val noRedirectClient = client.config { followRedirects = false }

            val response = noRedirectClient.get("${basePath()}/manage/$link")

            assertThat(response.status).isEqualTo(HttpStatusCode.Found)
            assertThat(response.headers[HttpHeaders.SetCookie].orEmpty()).doesNotContain("Secure")
            assertThat(response.headers["Strict-Transport-Security"]).isNull()
        }

    @Test
    fun logoutClearsSessionAndRedirectsToSetup() =
        testApplication {
            configureTestApplication()
            val session = exchangeSessionCookie(client.config { followRedirects = false })
            val noRedirectClient = client.config { followRedirects = false }

            val page =
                client.get("${basePath()}/manage") {
                    header(HttpHeaders.Cookie, session)
                }
            val csrf = hiddenValue(page.bodyAsText(), "csrf")
            val rejected =
                noRedirectClient.post("${basePath()}/manage/logout") {
                    header(HttpHeaders.Cookie, session)
                    setBody(FormDataContent(Parameters.build { append("csrf", "invalid") }))
                }
            assertThat(rejected.status).isEqualTo(HttpStatusCode.Forbidden)

            val response =
                noRedirectClient.post("${basePath()}/manage/logout") {
                    header(HttpHeaders.Cookie, session)
                    header("X-Forwarded-Proto", "https")
                    setBody(FormDataContent(Parameters.build { append("csrf", csrf) }))
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.Found)
            assertThat(response.headers[HttpHeaders.Location]).isEqualTo(basePath())
            val setCookie = response.headers[HttpHeaders.SetCookie].orEmpty()
            assertThat(setCookie).contains("Max-Age=0")
            assertThat(setCookie).contains("Secure")
        }

    @Test
    fun setupPageUsesConfiguredBasePath() {
        val previous = System.getProperty("nuecagram.publicUrl")
        System.setProperty("nuecagram.publicUrl", "https://example.com/managed")
        try {
            testApplication {
                configureTestApplication()
                assertThat(client.get("${basePath()}/setup").status).isEqualTo(HttpStatusCode.OK)
                assertThat(client.get("/nuecagram/setup").status).isEqualTo(HttpStatusCode.NotFound)
            }
        } finally {
            if (previous == null) {
                System.clearProperty("nuecagram.publicUrl")
            } else {
                System.setProperty("nuecagram.publicUrl", previous)
            }
        }
    }

    private fun ApplicationTestBuilder.exchangeSessionCookie(
        noRedirectClient: io.ktor.client.HttpClient,
    ): String {
        val link = runBlocking {
            installationRepository.issueManagementLink(
                installation.id,
                Instant.now().plus(30, ChronoUnit.MINUTES),
            ).raw
        }
        val response =
            runBlocking {
                noRedirectClient.get("${basePath()}/manage/$link") {
                    header("X-Forwarded-Proto", "https")
                }
            }
        return response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            .joinToString("; ") { it.substringBefore(';') }
    }

    private fun hiddenValue(body: String, name: String): String =
        body.substringAfter("name=\"$name\" value=\"").substringBefore('"')

    private fun basePath(): String = configuredBasePath()
}
