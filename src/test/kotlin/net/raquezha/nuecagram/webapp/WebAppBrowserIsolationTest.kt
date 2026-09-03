package net.raquezha.nuecagram.webapp

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import net.raquezha.nuecagram.BaseEventTestHelper
import org.junit.Test

class WebAppBrowserIsolationTest : BaseEventTestHelper() {

    @Test
    fun directBrowserAccessServesWebAppShellHtmlWhichDynamicallyEnforcesAuth() = testApplication {
        configureTestApplication()
        val response = client.get("/nuecagram/webapp")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.headers["Content-Type"]).contains("text/html")
        val body = response.bodyAsText()
        assertThat(body).contains("https://telegram.org/js/telegram-web-app.js")
        assertThat(body).contains("webapp/lottie.min.js")
        assertThat(body).contains("Nuecagram Management")
        assertThat(body).contains("screen-loading")
        assertThat(body).contains("loadingText")
        assertThat(body).contains("lottieContainer")

        val appJsResp = client.get("/nuecagram/webapp/app.js")
        assertThat(appJsResp.status).isEqualTo(HttpStatusCode.OK)
        val jsBody = appJsResp.bodyAsText()
        assertThat(jsBody).contains("webapp/loading.json")
        assertThat(jsBody).contains("lottie.loadAnimation")
    }

    @Test
    fun startAppParamOrValidSessionCookieServesWebAppShellHtml() = testApplication {
        configureTestApplication()

        val withStartApp = client.get("/nuecagram/webapp?startapp=nonce_12345")
        assertThat(withStartApp.status).isEqualTo(HttpStatusCode.OK)
        val startAppBody = withStartApp.bodyAsText()
        assertThat(startAppBody).contains("https://telegram.org/js/telegram-web-app.js")
        assertThat(startAppBody).contains("Nuecagram Management")

        val session = runBlocking {
            installationRepository.issueWebAppSession(
                telegramUserId = 999111L,
                telegramChatId = null,
                telegramTopicId = null,
                username = "testuser",
                firstName = "Test",
                expiresAt = Instant.now().plus(8, ChronoUnit.HOURS),
            )
        }

        val withSession = client.get("/nuecagram/webapp") {
            header(HttpHeaders.Cookie, "nuecagram_webapp_session=${session.raw}")
        }
        assertThat(withSession.status).isEqualTo(HttpStatusCode.OK)
        val sessionBody = withSession.bodyAsText()
        assertThat(sessionBody).contains("https://telegram.org/js/telegram-web-app.js")
        assertThat(sessionBody).contains("Nuecagram Management")
    }

    @Test
    fun telegramWebAppMenuButtonLaunchesServeWebAppShellHtml() = testApplication {
        configureTestApplication()

        val withTgWebAppVersion = client.get("/nuecagram/webapp?tgWebAppVersion=7.10&tgWebAppPlatform=android")
        assertThat(withTgWebAppVersion.status).isEqualTo(HttpStatusCode.OK)
        val bodyVersion = withTgWebAppVersion.bodyAsText()
        assertThat(bodyVersion).contains("https://telegram.org/js/telegram-web-app.js")
        assertThat(bodyVersion).contains("Nuecagram Management")

        val withUserAgent = client.get("/nuecagram/webapp") {
            header(HttpHeaders.UserAgent, "TelegramBot (like TwitterBot)")
        }
        assertThat(withUserAgent.status).isEqualTo(HttpStatusCode.OK)
        val bodyUserAgent = withUserAgent.bodyAsText()
        assertThat(bodyUserAgent).contains("https://telegram.org/js/telegram-web-app.js")
        assertThat(bodyUserAgent).contains("Nuecagram Management")
    }

    @Test
    fun loadingAnimationAssetsAreServedFromWebAppResources() = testApplication {
        configureTestApplication()
        val jsonResp = client.get("/nuecagram/webapp/loading.json")
        assertThat(jsonResp.status).isEqualTo(HttpStatusCode.OK)
        assertThat(jsonResp.headers["Content-Type"]).contains("application/json")
        assertThat(jsonResp.bodyAsText()).contains("Paperplane")

        val jsResp = client.get("/nuecagram/webapp/lottie.min.js")
        assertThat(jsResp.status).isEqualTo(HttpStatusCode.OK)
        assertThat(jsResp.headers["Content-Type"]).contains("text/javascript")
    }

    @Test
    fun apiWebappEndpointsFailClosedWithJson401() = testApplication {
        configureTestApplication()
        val response = client.get("/nuecagram/api/webapp/installations")
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(response.headers["Content-Type"]).contains("application/json")
        assertThat(response.bodyAsText()).contains("Web App session required")
    }

    @Test
    fun platformAdminLoginRemainsNormalBrowserUi() = testApplication {
        configureTestApplication()
        val response = client.get("/nuecagram/admin/login")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.headers["Content-Type"]).contains("text/html")
        val body = response.bodyAsText()
        assertThat(body).contains("Platform Login")
        assertThat(body).contains("Platform Password")
        assertThat(body).doesNotContain("Telegram Access Required")
    }
}
