package net.raquezha.nuecagram.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.db.CredentialCodec
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.PlatformAdminAuditRecord
import net.raquezha.nuecagram.db.PlatformAdminSessionContext
import org.koin.ktor.ext.inject

private const val ADMIN_SESSION_COOKIE = "nuecagram_admin_session"
private const val LOGIN_CSRF_COOKIE = "nuecagram_admin_login_csrf"
private const val ADMIN_CSRF_COOKIE = "nuecagram_admin_csrf"
private const val ADMIN_SESSION_HOURS = 8L
private const val SECONDS_PER_MINUTE = 60L
private const val ADMIN_SESSION_SECONDS = ADMIN_SESSION_HOURS * SECONDS_PER_MINUTE * SECONDS_PER_MINUTE
private const val LOGIN_CSRF_SECONDS = 10 * SECONDS_PER_MINUTE
private const val MAX_LOGIN_FAILURES = 5
private const val MAX_PASSWORD_LENGTH = 1024
private const val LOGIN_WINDOW_MINUTES = 15L

@Suppress("LongMethod")
fun Route.platformAdminRouting(basePath: String) {
    val config by inject<ConfigWithSecrets>()
    val installationRepository by inject<InstallationRepository>()
    val loginThrottle = LoginThrottle()

    get("$basePath/admin/login") {
        if (call.platformAdminSession(installationRepository) != null) {
            call.appendSecurityHeaders()
            call.respondRedirect("$basePath/admin")
            return@get
        }
        val csrf = CredentialCodec.issueCredential().first
        call.response.headers.append(HttpHeaders.SetCookie, loginCsrfCookie(basePath, csrf, call.isHttps()))
        call.respondManagementHtml(
            title = "Platform administration",
            body = adminLoginHtml(basePath, csrf),
        )
    }

    post("$basePath/admin/login") {
        val clientId = call.request.origin.remoteHost
        if (loginThrottle.isBlocked(clientId)) {
            call.response.headers.append(
                HttpHeaders.RetryAfter,
                (LOGIN_WINDOW_MINUTES * SECONDS_PER_MINUTE).toString(),
            )
            call.respondManagementHtml(
                status = HttpStatusCode.TooManyRequests,
                title = "Try again later",
                body = authMessageHtml("Try again later", "Too many failed login attempts."),
            )
            return@post
        }
        val form = call.receiveParameters()
        val csrf = form["csrf"].orEmpty()
        val csrfCookie = call.request.cookies[LOGIN_CSRF_COOKIE].orEmpty()
        if (!constantTimeEquals(csrf, csrfCookie)) {
            call.respondManagementHtml(
                status = HttpStatusCode.Forbidden,
                title = "Request rejected",
                body = authMessageHtml("Request rejected", "Reload the login page and try again."),
            )
            return@post
        }
        val password = form["password"].orEmpty()
        val authenticated =
            password.length in 1..MAX_PASSWORD_LENGTH &&
                constantTimeEquals(password, config.platformAdminPassword)
        if (!authenticated) {
            loginThrottle.recordFailure(clientId)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Login failed",
                body = adminLoginHtml(basePath, csrf, failed = true),
            )
            return@post
        }

        loginThrottle.clear(clientId)
        val session =
            installationRepository.issuePlatformAdminSession(
                Instant.now().plus(ADMIN_SESSION_HOURS, ChronoUnit.HOURS),
            )
        call.response.headers.append(
            HttpHeaders.SetCookie,
            adminSessionCookie(basePath, session.raw, call.isHttps()),
        )
        call.response.headers.append(
            HttpHeaders.SetCookie,
            cookie(
                ADMIN_CSRF_COOKIE,
                session.csrf,
                "$basePath/admin",
                ADMIN_SESSION_SECONDS,
                call.isHttps(),
            ),
        )
        call.response.headers.append(
            HttpHeaders.SetCookie,
            expiredCookie(LOGIN_CSRF_COOKIE, "$basePath/admin", call.isHttps()),
        )
        call.appendSecurityHeaders()
        call.respondRedirect("$basePath/admin")
    }

    get("$basePath/admin") {
        val session = call.platformAdminSession(installationRepository)
        if (session == null) {
            call.clearAdminSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Platform login required",
                body = adminLoginRequiredHtml(basePath),
            )
            return@get
        }
        val csrf = call.request.cookies[ADMIN_CSRF_COOKIE].orEmpty()
        if (!installationRepository.verifyPlatformAdminCsrf(session, csrf)) {
            call.clearAdminSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Platform login required",
                body = adminLoginRequiredHtml(basePath),
            )
            return@get
        }
        call.respondManagementHtml(
            title = "Platform administration",
            body =
                platformAdminHtml(
                    basePath = basePath,
                    csrf = csrf,
                    installations = installationRepository.platformAdminInstallations(),
                    auditEvents = installationRepository.platformAdminAuditEvents(),
                ),
            rightHeaderHtml = adminLogoutHeaderButton(basePath, csrf),
        )
    }

    post("$basePath/admin/logout") {
        val session = call.platformAdminSession(installationRepository)
        val csrf = call.receiveParameters()["csrf"].orEmpty()
        if (session == null || !installationRepository.verifyPlatformAdminCsrf(session, csrf)) {
            call.respondManagementHtml(
                status = HttpStatusCode.Forbidden,
                title = "Request rejected",
                body = authMessageHtml("Request rejected", "The session or CSRF token is invalid."),
            )
            return@post
        }
        installationRepository.deletePlatformAdminSession(session.id)
        call.clearAdminSession(basePath)
        call.appendSecurityHeaders()
        call.respondRedirect("$basePath/admin/login")
    }
}

private suspend fun ApplicationCall.platformAdminSession(
    installationRepository: InstallationRepository,
): PlatformAdminSessionContext? =
    request.cookies[ADMIN_SESSION_COOKIE]
        ?.takeIf(String::isNotBlank)
        ?.let { installationRepository.verifyPlatformAdminSession(it) }

private fun ApplicationCall.clearAdminSession(basePath: String) {
    response.headers.append(
        HttpHeaders.SetCookie,
        expiredCookie(ADMIN_SESSION_COOKIE, "$basePath/admin", isHttps()),
    )
    response.headers.append(
        HttpHeaders.SetCookie,
        expiredCookie(ADMIN_CSRF_COOKIE, "$basePath/admin", isHttps()),
    )
}

private fun adminSessionCookie(basePath: String, value: String, secure: Boolean): String =
    cookie(
        name = ADMIN_SESSION_COOKIE,
        value = value,
        path = "$basePath/admin",
        maxAge = ADMIN_SESSION_SECONDS,
        secure = secure,
    )

private fun loginCsrfCookie(basePath: String, value: String, secure: Boolean): String =
    cookie(LOGIN_CSRF_COOKIE, value, "$basePath/admin", LOGIN_CSRF_SECONDS, secure)

private fun expiredCookie(name: String, path: String, secure: Boolean): String =
    cookie(name, "", path, 0, secure)

private fun cookie(
    name: String,
    value: String,
    path: String,
    maxAge: Long,
    secure: Boolean,
): String =
    buildString {
        append("$name=$value; Path=$path; Max-Age=$maxAge; HttpOnly; SameSite=Strict")
        if (secure) append("; Secure")
    }

private fun constantTimeEquals(left: String, right: String): Boolean =
    left.isNotEmpty() &&
        java.security.MessageDigest.isEqual(
            left.toByteArray(Charsets.UTF_8),
            right.toByteArray(Charsets.UTF_8),
        )

private fun adminLogoutHeaderButton(basePath: String, csrf: String): String =
    """
    <form method="post" action="${(basePath + "/admin/logout").html()}" class="header-form">
      <input type="hidden" name="csrf" value="${csrf.html()}">
      <button type="submit" class="header-btn btn-logout" aria-label="Log out">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
        <span>Log out</span>
      </button>
    </form>
    """.trimIndent()

private fun authMessageHtml(title: String, description: String): String =
    """
    <div class="auth-card">
      <h2>${title.html()}</h2>
      <p class="auth-desc">${description.html()}</p>
    </div>
    """.trimIndent()

private fun adminLoginHtml(basePath: String, csrf: String, failed: Boolean = false): String =
    buildString {
        append("<div class=\"auth-card\">")
        append("<h2>Platform Login</h2>")
        append("<p class=\"auth-desc\">Enter admin password to access system installations & audit logs.</p>")
        if (failed) {
            append("<div class=\"auth-error\">Invalid admin password. Please try again.</div>")
        }
        append("<form method=\"post\" action=\"${(basePath + "/admin/login").html()}\" class=\"auth-form\">")
        append("<input type=\"hidden\" name=\"csrf\" value=\"${csrf.html()}\">")
        append("<div class=\"form-group\">")
        append("<label for=\"admin-password\">Platform Password</label>")
        append("<input type=\"password\" id=\"admin-password\" name=\"password\" ")
        append("placeholder=\"Enter admin password\" required class=\"input-text\">")
        append("</div>")
        append("<button type=\"submit\" class=\"btn-primary\">Log in to Platform</button>")
        append("</form></div>")
    }

private fun adminLoginRequiredHtml(basePath: String): String =
    """
    <div class="auth-card" style="text-align: center;">
      <h2>Platform Session Expired</h2>
      <p class="auth-desc">Please log in to continue accessing platform administration.</p>
      <a href="${(basePath + "/admin/login").html()}" class="btn-docs" style="display: inline-flex; justify-content: center; width: 100%;">Log In</a>
    </div>
    """.trimIndent()

@Suppress("LongMethod")
private fun platformAdminHtml(
    basePath: String,
    csrf: String,
    installations: List<InstallationAdminContext>,
    auditEvents: List<PlatformAdminAuditRecord>,
): String =
    buildString {
        append("<section class=\"admin-shell\">")
        append("<div class=\"admin-hero\">")
        append("<span class=\"admin-kicker\">Platform administration</span>")
        append("<h1>Operations dashboard</h1>")
        append("<p>Inspect active installations, review recent audit activity, and sign out when you are done.</p>")
        append("<div class=\"admin-meta\">")
        append("<div class=\"admin-meta-card meta-installations\"><strong>Installations</strong>")
        append("<div class=\"stat-val\">${installations.size}</div></div>")
        append("<div class=\"admin-meta-card meta-audit\"><strong>Audit events</strong>")
        append("<div class=\"stat-val\">${auditEvents.size}</div></div>")
        append("<div class=\"admin-meta-card meta-recovery\"><strong>Recovery</strong>")
        append("<div class=\"stat-val\" style=\"font-size: 0.95rem;\">Telegram verified flow</div></div>")
        append("</div></div>")
        append("<div class=\"admin-panel\"><h3>Installations</h3><div class=\"table-wrapper\"><table><thead><tr>")
        append("<th>ID</th><th>GitLab</th><th>Project</th><th>Status</th>")
        append("</tr></thead><tbody>")
        installations.forEach { installation ->
            val gitlabUrl = installation.gitlabBaseUrl.redactedUrl()
            val gitlabCell =
                if (gitlabUrl.startsWith("http")) {
                    "<a href=\"${gitlabUrl.html()}\" target=\"_blank\" rel=\"noopener\" class=\"table-link\">" +
                        "<span>${gitlabUrl.html()}</span>" +
                        "<svg viewBox=\"0 0 24 24\" width=\"12\" height=\"12\" fill=\"none\" " +
                        "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
                        "stroke-linejoin=\"round\" style=\"margin-left: 0.35rem;\">" +
                        "<path d=\"M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6\"/>" +
                        "<polyline points=\"15 3 21 3 21 9\"/>" +
                        "<line x1=\"10\" y1=\"14\" x2=\"21\" y2=\"3\"/></svg></a>"
                } else {
                    gitlabUrl.html()
                }
            val projectCell =
                installation.gitlabProjectId?.let { "<code>$it</code>" }
                    ?: "<span style=\"color:#6e6154;\">Group-level</span>"
            val statusCell =
                if (installation.muted) {
                    "<span class=\"status-badge status-muted\"><span class=\"status-dot\"></span>Muted</span>"
                } else {
                    "<span class=\"status-badge status-active\"><span class=\"status-dot\"></span>Active</span>"
                }
            append("<tr><td><code>${installation.id.toString().html()}</code></td>")
            append("<td>$gitlabCell</td>")
            append("<td>$projectCell</td>")
            append("<td>$statusCell</td></tr>")
        }
        append("</tbody></table></div></div>")
        append("<div class=\"admin-panel\"><h3>Recent audit events</h3><div class=\"table-wrapper\"><table><thead><tr>")
        append("<th>Time</th><th>Installation</th><th>Action</th>")
        append("</tr></thead><tbody>")
        auditEvents.forEach { event ->
            append("<tr><td>${event.createdAt.toString().html()}</td>")
            append("<td>${event.installationId?.toString()?.html().orEmpty()}</td>")
            append("<td>${event.action.html()}</td></tr>")
        }
        append("</tbody></table></div></div>")
        append("<div class=\"admin-panel\"><h3>Recovery</h3>")
        append("<p>Credential recovery is delivered only through the verified Telegram administrator flow.</p>")
        append("<form method=\"post\" action=\"${(basePath + "/admin/logout").html()}\">")
        append("<input type=\"hidden\" name=\"csrf\" value=\"${csrf.html()}\">")
        append(
            "<button type=\"submit\" class=\"btn-primary\" style=\"max-width: 12rem;\">" +
                "Log out</button></form></div>",
        )
        append("</section>")
    }

private fun String.redactedUrl(): String =
    runCatching {
        val uri = URI(this)
        require(uri.scheme in setOf("http", "https") && uri.host != null)
        URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
    }.getOrDefault("[invalid URL]")

private class LoginThrottle {
    // ponytail: process-local throttle; move to shared storage if the service runs multiple replicas.
    private val failures = mutableMapOf<String, ArrayDeque<Instant>>()

    @Synchronized
    fun isBlocked(clientId: String, now: Instant = Instant.now()): Boolean =
        recentFailures(clientId, now).size >= MAX_LOGIN_FAILURES

    @Synchronized
    fun recordFailure(clientId: String, now: Instant = Instant.now()) {
        recentFailures(clientId, now).addLast(now)
    }

    @Synchronized
    fun clear(clientId: String) {
        failures.remove(clientId)
    }

    private fun recentFailures(clientId: String, now: Instant): ArrayDeque<Instant> {
        val attempts = failures.getOrPut(clientId) { ArrayDeque() }
        val cutoff = now.minus(LOGIN_WINDOW_MINUTES, ChronoUnit.MINUTES)
        while (attempts.firstOrNull()?.isBefore(cutoff) == true) attempts.removeFirst()
        return attempts
    }
}
