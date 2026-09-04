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

@Suppress("TooManyFunctions")
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
            assertThat(body).contains(installation.id.toString().take(8))
            assertThat(body).contains("target=\"_blank\"")
            assertThat(body).contains("rel=\"noopener\"")
            assertThat(body).contains("Log out")
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
            assertThat(response.bodyAsText()).contains("session")
            assertThat(response.bodyAsText()).contains("/manage ${installation.id.toString().take(8)}")
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

    @Suppress("LongMethod")
    @Test
    fun dumpSampleHtmlFiles() =
        testApplication {
            configureTestApplication()
            val session = exchangeSessionCookie(client.config { followRedirects = false })

            val webAppGuidance = client.get("${basePath()}/webapp").bodyAsText()
            val webAppShell = client.get("${basePath()}/webapp?startapp=sample").bodyAsText()
            val setup = client.get("${basePath()}/setup").bodyAsText()
            val manageDashboard =
                client.get("${basePath()}/manage") {
                    header(HttpHeaders.Cookie, session)
                    header("X-Forwarded-Proto", "https")
                }.bodyAsText()
            val manageRecovery = client.get("${basePath()}/manage/invalid-token").bodyAsText()
            val adminLogin = client.get("${basePath()}/admin/login").bodyAsText()

            val noRedirectClient = client.config { followRedirects = false }
            val adminForm = noRedirectClient.get("${basePath()}/admin/login")
            val adminCsrf = hiddenValue(adminForm.bodyAsText(), "csrf")
            val adminLoginResp = noRedirectClient.post("${basePath()}/admin/login") {
                header(HttpHeaders.Cookie, adminForm.headers[HttpHeaders.SetCookie].orEmpty().substringBefore(';'))
                setBody(
                    io.ktor.client.request.forms.FormDataContent(
                        Parameters.build {
                            append("csrf", adminCsrf)
                            append("password", PlatformAdminUiTest.TEST_ADMIN_PASSWORD)
                        },
                    ),
                )
            }
            val adminSession = adminLoginResp.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .filterNot { it.startsWith("nuecagram_admin_login_csrf=") }
                .joinToString("; ") { it.substringBefore(';') }

            val adminDashboard = client.get("${basePath()}/admin") {
                header(HttpHeaders.Cookie, adminSession)
            }.bodyAsText()
            val adminInstallations = client.get("${basePath()}/admin/installations") {
                header(HttpHeaders.Cookie, adminSession)
            }.bodyAsText()
            val adminAudit = client.get("${basePath()}/admin/audit") {
                header(HttpHeaders.Cookie, adminSession)
            }.bodyAsText()

            val webAppJs = client.get("${basePath()}/webapp/app.js").bodyAsText()

            runCatching {
                val dir = java.io.File("samples").apply { mkdirs() }
                java.io.File(dir, "webapp-guidance.html").writeText(webAppGuidance)

                val webAppStandaloneShell = webAppShell.replace(
                    """<script src="${basePath()}/webapp/app.js"></script>""",
                    """<script>$webAppJs</script>""",
                )

                java.io.File(dir, "webapp-shell.html").writeText(webAppStandaloneShell)

                val webAppListMockup = webAppStandaloneShell.replace(
                    """<section id="screen-loading" class="panel active">""",
                    """<section id="screen-loading" class="panel">""",
                ).replace(
                    """<section id="screen-list" class="panel">""",
                    """<section id="screen-list" class="panel active">""",
                ).replace(
                    """<div id="installationsList"></div>""",
                    """
                    <div id="installationsList">
                      <button class="card">
                        <img class="avatar" alt="" src="${basePath()}/webapp/avatars/01-wildlife-avatar-1-giraffe.png">
                        <div class="grow">
                          <div class="row">
                            <div class="title">nuecagram</div>
                            <span class="badge badge-active">ACTIVE</span>
                            <span class="chev">›</span>
                          </div>
                          <div class="sub">Android Team / Notifications</div>
                          <div class="meta">gitlab.com/nuecagram · id 1b179442</div>
                        </div>
                      </button>
                    </div>
                    """.trimIndent(),
                )

                val webAppDetailMockup = webAppStandaloneShell.replace(
                    """<section id="screen-loading" class="panel active">""",
                    """<section id="screen-loading" class="panel">""",
                ).replace(
                    """<section id="screen-detail" class="panel">""",
                    """<section id="screen-detail" class="panel active">""",
                ).replace(
                    """<div id="detailBody"></div>""",
                    """
                    <div id="detailBody">
                      <div class="card">
                        <img class="avatar" alt="" src="${basePath()}/webapp/avatars/01-wildlife-avatar-1-giraffe.png">
                        <div class="grow">
                          <div class="title">nuecagram</div>
                          <div class="sub">Android Team / Notifications</div>
                        </div>
                        <span class="badge badge-active">ACTIVE</span>
                      </div>
                      <div class="section">
                        <div class="section-title">Repository</div>
                        <div class="group">
                          <div class="row" style="cursor:pointer">
                            <strong>GitLab</strong>
                            <div style="display:flex;align-items:center;gap:6px;min-width:0;">
                              <span class="meta">https://gitlab.com/projects/381</span>
                              <a href="https://gitlab.com/projects/381" target="_blank" rel="noopener" style="color:var(--button);font-size:16px;font-weight:700;text-decoration:none;padding:2px 6px;border-radius:6px;background:var(--input-bg);flex-shrink:0;">↗</a>
                            </div>
                          </div>
                          <div class="row" style="cursor:pointer">
                            <strong>Installation ID</strong>
                            <span class="meta">1b179442-8888-4444-9999-1234567890ab</span>
                          </div>
                        </div>
                      </div>
                      <div class="section">
                        <div class="section-title">Destination</div>
                        <div class="group">
                          <div class="row">
                            <div class="grow">
                              <strong>Telegram</strong>
                              <div class="sub" style="font-weight:700;margin-top:2px;">Android Team / Notifications</div>
                              <div class="meta" style="margin-top:2px;">Chat -100123456789 · Topic 2</div>
                            </div>
                          </div>
                        </div>
                      </div>
                      <div class="section">
                        <div class="section-title">Actions</div>
                        <div class="split">
                          <button id="btnTest">Test notification</button>
                          <button id="btnMute">Mute notifications</button>
                        </div>
                        <div id="actionHelp" class="helper"></div>
                      </div>
                      <div class="section">
                        <div class="section-title">Settings</div>
                        <button id="btnEdit">Edit names ›</button>
                      </div>
                      <div class="section">
                        <div class="section-title">Danger zone</div>
                        <div style="display:flex;flex-direction:column;gap:10px;">
                          <button id="btnRotate" class="danger">Rotate webhook token ›</button>
                          <button id="btnDelete" class="danger">Delete repository ›</button>
                        </div>
                      </div>
                    </div>
                    """.trimIndent(),
                )

                val targetRevSecret = """<div id="revSecret" class="codebox secret hidden" style="cursor:pointer;" """ +
                    """onclick="if(!this.classList.contains('hidden')) copyValue(this.innerText, this)"></div>"""
                val replacementRevSecret = """<div id="revSecret" class="codebox secret hidden" """ +
                    """style="cursor:pointer;" onclick="if(!this.classList.contains('hidden')) """ +
                    """copyValue(this.innerText, this)">secret_token_1234567890_nuecagram_sample</div>"""

                val targetRevUrl = """<div id="revUrl" class="codebox" style="margin-top:4px;cursor:pointer;" """ +
                    """onclick="copyValue(this.innerText, this)"></div>"""
                val replacementRevUrl = """<div id="revUrl" class="codebox" style="margin-top:4px;cursor:pointer;">""" +
                    """https://android.nweca.com/nuecagram/webhook</div>"""

                val targetRevHooks = """<a id="btnRevGitlabHooks" href="#" target="_blank" rel="noopener" """ +
                    """class="primary" style="display:none;text-align:center;text-decoration:none;""" +
                    """padding:11px 14px;border-radius:12px;width:100%;">↗ Open GitLab Project</a>"""
                val replacementRevHooks = """<a id="btnRevGitlabHooks" href="https://gitlab.com/projects/381" """ +
                    """target="_blank" rel="noopener" class="primary" style="display:block;""" +
                    """text-align:center;text-decoration:none;padding:11px 14px;border-radius:12px;width:100%;">""" +
                    """↗ Open GitLab Project</a>"""

                val targetGuide = """<div id="revGuideBox" class="box" style="margin-top:14px;""" +
                    """padding:12px 14px;font-size:13px;line-height:1.45;display:none;">"""
                val replacementGuide = """<div id="revGuideBox" class="box" style="margin-top:14px;""" +
                    """padding:12px 14px;font-size:13px;line-height:1.45;display:block;">"""

                val webAppRevealMockup = webAppStandaloneShell.replace(
                    """<section id="screen-loading" class="panel active">""",
                    """<section id="screen-loading" class="panel">""",
                ).replace(
                    """<section id="screen-reveal" class="panel">""",
                    """<section id="screen-reveal" class="panel active">""",
                ).replace(
                    targetRevSecret,
                    replacementRevSecret,
                ).replace(
                    targetRevUrl,
                    replacementRevUrl,
                ).replace(
                    targetRevHooks,
                    replacementRevHooks,
                ).replace(
                    targetGuide,
                    replacementGuide,
                )

                java.io.File(dir, "webapp-repository-list.html").writeText(webAppListMockup)
                java.io.File(dir, "webapp-repository-detail.html").writeText(webAppDetailMockup)
                java.io.File(dir, "webapp-repository-reveal.html").writeText(webAppRevealMockup)
                java.io.File(dir, "manage-onboarding.html").writeText(setup)
                java.io.File(dir, "manage-dashboard.html").writeText(manageDashboard)
                java.io.File(dir, "manage-recovery.html").writeText(manageRecovery)
                java.io.File(dir, "admin-login.html").writeText(adminLogin)
                java.io.File(dir, "admin-dashboard.html").writeText(adminDashboard)
                java.io.File(dir, "admin-installations.html").writeText(adminInstallations)
                java.io.File(dir, "admin-audit.html").writeText(adminAudit)

                val indexHtml = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Nuecagram UI Review Gallery</title>
                  <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 40px 20px; }
                    .container { max-width: 800px; margin: 0 auto; }
                    h1 { font-size: 28px; font-weight: 800; margin-bottom: 8px; color: #38bdf8; }
                    p { color: #94a3b8; font-size: 15px; margin-bottom: 24px; }
                    .card { background: #1e293b; border-radius: 12px; border: 1px solid #334155; padding: 20px; margin-bottom: 16px; display: flex; justify-content: space-between; align-items: center; }
                    .card-title { font-weight: 700; font-size: 16px; margin-bottom: 4px; color: #f1f5f9; }
                    .card-desc { font-size: 13px; color: #94a3b8; }
                    a.btn { background: #0284c7; color: #fff; text-decoration: none; padding: 10px 16px; border-radius: 8px; font-weight: 700; font-size: 14px; }
                    a.btn:hover { background: #0369a1; }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <h1>Nuecagram UI Review Gallery</h1>
                    <p>Click any view to open and review the rendered HTML page in your browser.</p>

                    <div class="card">
                      <div>
                        <div class="card-title">1. WebApp Direct Browser Guidance</div>
                        <div class="card-desc">"Telegram Access Required" guidance page served when accessing /webapp outside Telegram</div>
                      </div>
                      <a class="btn" href="webapp-guidance.html" target="_blank">Preview</a>
                    </div>

                    <div class="card">
                      <div>
                        <div class="card-title">2. WebApp Management Shell</div>
                        <div class="card-desc">User-facing Telegram WebApp card layout and management UI</div>
                      </div>
                      <a class="btn" href="webapp-shell.html" target="_blank">Preview</a>
                    </div>

                    <div class="card">
                      <div>
                        <div class="card-title">3. WebApp Repository List View</div>
                        <div class="card-desc">Populated repository list screen with active/muted badges and avatars</div>
                      </div>
                      <a class="btn" href="webapp-repository-list.html" target="_blank">Preview</a>
                    </div>

                    <div class="card">
                      <div>
                        <div class="card-title">4. WebApp Repository Detail View</div>
                        <div class="card-desc">Repository detail screen with 50/50 action buttons and GitLab permalink link button</div>
                      </div>
                      <a class="btn" href="webapp-repository-detail.html" target="_blank">Preview</a>
                    </div>

                    <div class="card">
                      <div>
                        <div class="card-title">5. WebApp Post-Creation Reveal & Setup Guidance</div>
                        <div class="card-desc">Post-creation reveal screen displaying generated webhook secret, URL, 3-step setup guide, and GitLab project button</div>
                      </div>
                      <a class="btn" href="webapp-repository-reveal.html" target="_blank">Preview</a>
                    </div>

                    <div class="card">
                      <div>
                        <div class="card-title">3. Platform Admin Operations Dashboard</div>
                        <div class="card-desc">/admin operations overview with installations and 24h audit metrics</div>
                      </div>
                      <a class="btn" href="admin-dashboard.html" target="_blank">Preview</a>
                    </div>

                    <div class="card">
                      <div>
                        <div class="card-title">4. Platform Admin Installations Directory</div>
                        <div class="card-desc">/admin/installations showing Repository Name, Notification Label, GitLab, Project, Status</div>
                      </div>
                      <a class="btn" href="admin-installations.html" target="_blank">Preview</a>
                    </div>

                    <div class="card">
                      <div>
                        <div class="card-title">5. Platform Admin Audit Explorer</div>
                        <div class="card-desc">/admin/audit explorer showing trace of repository identity, actor, and action deltas</div>
                      </div>
                      <a class="btn" href="admin-audit.html" target="_blank">Preview</a>
                    </div>

                    <div class="card">
                      <div>
                        <div class="card-title">6. Platform Admin Login Page</div>
                        <div class="card-desc">/admin/login browser authentication interface</div>
                      </div>
                      <a class="btn" href="admin-login.html" target="_blank">Preview</a>
                    </div>
                  </div>
                </body>
                </html>
                """.trimIndent()
                java.io.File(dir, "index.html").writeText(indexHtml)
            }
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
