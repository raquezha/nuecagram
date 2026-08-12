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
                body = "<h1>Try again later</h1><p>Too many failed login attempts.</p>",
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
                body = "<h1>Request rejected</h1><p>Reload the login page and try again.</p>",
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
        )
    }

    post("$basePath/admin/logout") {
        val session = call.platformAdminSession(installationRepository)
        val csrf = call.receiveParameters()["csrf"].orEmpty()
        if (session == null || !installationRepository.verifyPlatformAdminCsrf(session, csrf)) {
            call.respondManagementHtml(
                status = HttpStatusCode.Forbidden,
                title = "Request rejected",
                body = "<h1>Request rejected</h1><p>The session or CSRF token is invalid.</p>",
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

private fun adminLoginHtml(basePath: String, csrf: String, failed: Boolean = false): String =
    buildString {
        append("<h1>Platform administration</h1>")
        if (failed) append("<p>Login failed.</p>")
        append("<form method=\"post\" action=\"${(basePath + "/admin/login").html()}\">")
        append("<input type=\"hidden\" name=\"csrf\" value=\"${csrf.html()}\">")
        append("<label>Password <input type=\"password\" name=\"password\" required></label>")
        append("<button type=\"submit\">Log in</button></form>")
    }

private fun adminLoginRequiredHtml(basePath: String): String =
    """
    <h1>Platform login required</h1>
    <p><a href="${(basePath + "/admin/login").html()}">Log in</a></p>
    """.trimIndent()

private fun platformAdminHtml(
    basePath: String,
    csrf: String,
    installations: List<InstallationAdminContext>,
    auditEvents: List<PlatformAdminAuditRecord>,
): String =
    buildString {
        append("<h1>Platform administration</h1>")
        append("<h2>Installations</h2><table><thead><tr>")
        append("<th>ID</th><th>GitLab</th><th>Project</th><th>Muted</th>")
        append("</tr></thead><tbody>")
        installations.forEach { installation ->
            append("<tr><td><code>${installation.id.toString().html()}</code></td>")
            append("<td>${installation.gitlabBaseUrl.redactedUrl().html()}</td>")
            append("<td>${installation.gitlabProjectId?.toString()?.html().orEmpty()}</td>")
            append("<td>${if (installation.muted) "yes" else "no"}</td></tr>")
        }
        append("</tbody></table>")
        append("<h2>Recent audit events</h2><table><thead><tr>")
        append("<th>Time</th><th>Installation</th><th>Action</th>")
        append("</tr></thead><tbody>")
        auditEvents.forEach { event ->
            append("<tr><td>${event.createdAt.toString().html()}</td>")
            append("<td>${event.installationId?.toString()?.html().orEmpty()}</td>")
            append("<td>${event.action.html()}</td></tr>")
        }
        append("</tbody></table>")
        append("<h2>Recovery</h2><p>Credential recovery is delivered only through ")
        append("the verified Telegram administrator flow.</p>")
        append("<form method=\"post\" action=\"${(basePath + "/admin/logout").html()}\">")
        append("<input type=\"hidden\" name=\"csrf\" value=\"${csrf.html()}\">")
        append("<button type=\"submit\">Log out</button></form>")
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
