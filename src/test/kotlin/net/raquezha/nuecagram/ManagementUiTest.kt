package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
    }

    @After
    fun clearBasePath() {
        System.clearProperty("nuecagram.basePath")
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

            val session = setCookie.substringAfter("nuecagram_manage_session=").substringBefore(';')
            val page =
                client.get("${basePath()}/manage") {
                    header(HttpHeaders.Cookie, "nuecagram_manage_session=$session")
                    header("X-Forwarded-Proto", "https")
                }

            assertThat(page.status).isEqualTo(HttpStatusCode.OK)
            assertThat(page.bodyAsText()).contains(installation.id.toString())
            assertThat(page.bodyAsText()).doesNotContain(link)
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
    fun rotateRevealsCredentialOnceAndKeepsManagePageCredentialFree() =
        testApplication {
            configureTestApplication()
            val oldCredential = runBlocking { installationRepository.issueWebhookSecret(installation.id).raw }
            val session = exchangeSessionCookie(client.config { followRedirects = false })

            val rotate =
                client.post("${basePath()}/manage/rotate") {
                    header(HttpHeaders.Cookie, session)
                    header("X-Forwarded-Proto", "https")
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
    fun setupPageUsesConfiguredBasePath() {
        val previous = System.getProperty("nuecagram.basePath")
        System.setProperty("nuecagram.basePath", "/managed")
        try {
            testApplication {
                configureTestApplication()
                assertThat(client.get("${basePath()}/setup").status).isEqualTo(HttpStatusCode.OK)
                assertThat(client.get("/nuecagram/setup").status).isEqualTo(HttpStatusCode.NotFound)
            }
        } finally {
            if (previous == null) {
                System.clearProperty("nuecagram.basePath")
            } else {
                System.setProperty("nuecagram.basePath", previous)
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
        val session = response.headers[HttpHeaders.SetCookie].orEmpty()
            .substringAfter("nuecagram_manage_session=")
            .substringBefore(';')
        return "nuecagram_manage_session=$session"
    }

    private fun basePath(): String = configuredBasePath()
}
