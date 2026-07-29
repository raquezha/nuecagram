package net.raquezha.nuecagram.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import java.time.temporal.ChronoUnit
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRepository
import org.koin.ktor.ext.inject

private const val SESSION_COOKIE_NAME = "nuecagram_manage_session"
private const val CSRF_COOKIE_NAME = "nuecagram_manage_csrf"
private const val SESSION_TTL_HOURS = 8L
private const val SESSION_TTL_SECONDS = SESSION_TTL_HOURS * 60L * 60L
private const val ROTATION_GRACE_MINUTES = 0L

@Suppress("LongMethod")
fun Route.managementRouting(basePath: String) {
    val installationRepository by inject<InstallationRepository>()

    get(basePath.ifEmpty { "/" }) {
        call.respondManagementHtml(
            title = "Nuecagram setup",
            body = onboardingHtml(basePath),
        )
    }

    get("$basePath/setup") {
        call.respondManagementHtml(
            title = "Nuecagram setup",
            body = onboardingHtml(basePath),
        )
    }

    get("$basePath/manage/{token}") {
        val token = call.parameters["token"]?.trim().orEmpty()
        if (token.isBlank()) {
            call.respondManagementHtml(
                status = HttpStatusCode.NotFound,
                title = "Link unavailable",
                body = recoveryHtml(basePath),
            )
            return@get
        }
        val session =
            installationRepository.exchangeManagementLinkForSession(
                raw = token,
                sessionExpiresAt = managementSessionExpiry(),
            )
        if (session == null) {
            call.respondManagementHtml(
                status = HttpStatusCode.Gone,
                title = "Link expired",
                body = recoveryHtml(basePath),
            )
            return@get
        }
        call.response.headers.append(
            HttpHeaders.SetCookie,
            buildSessionCookie(basePath, session.raw, call.isHttps()),
        )
        call.response.headers.append(
            HttpHeaders.SetCookie,
            buildCsrfCookie(basePath, session.csrf, call.isHttps()),
        )
        call.appendSecurityHeaders()
        call.respondRedirect("$basePath/manage")
    }

    get("$basePath/manage") {
        val session = call.managementSession(installationRepository)
        if (session == null) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Session required",
                body = recoveryHtml(basePath),
            )
            return@get
        }
        val csrf = call.request.cookies[CSRF_COOKIE_NAME].orEmpty()
        if (!installationRepository.verifyManagementCsrf(session, csrf)) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Session required",
                body = recoveryHtml(basePath),
            )
            return@get
        }
        val installation = installationRepository.installationAdminContext(session.installationId)
        if (installation == null) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.NotFound,
                title = "Installation unavailable",
                body = recoveryHtml(basePath),
            )
            return@get
        }
        call.respondManagementHtml(
            title = "Manage installation",
            body = manageHtml(basePath, installation, csrf),
        )
    }

    post("$basePath/manage/rotate") {
        val session = call.managementSession(installationRepository)
        if (session == null) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.Unauthorized,
                title = "Session required",
                body = recoveryHtml(basePath),
            )
            return@post
        }
        val csrf = call.receiveParameters()["csrf"].orEmpty()
        if (!installationRepository.verifyManagementCsrf(session, csrf)) {
            call.respondManagementHtml(
                status = HttpStatusCode.Forbidden,
                title = "Request rejected",
                body = "<h1>Request rejected</h1><p>The CSRF token is invalid.</p>",
            )
            return@post
        }
        val installation = installationRepository.installationAdminContext(session.installationId)
        if (installation == null) {
            call.clearManagementSession(basePath)
            call.respondManagementHtml(
                status = HttpStatusCode.NotFound,
                title = "Installation unavailable",
                body = recoveryHtml(basePath),
            )
            return@post
        }
        val credential =
            installationRepository.rotateWebhookSecret(
                installationId = installation.id,
                graceUntil = Instant.now().plus(ROTATION_GRACE_MINUTES, ChronoUnit.MINUTES),
            )
        installationRepository.writeAuditEvent(
            installationId = installation.id,
            actorType = "management_session",
            actorId = session.sessionId.toString(),
            action = "management_rotate",
        )
        call.respondManagementHtml(
            title = "Credential rotated",
            body = rotatedHtml(basePath, installation, credential.raw),
        )
    }

    post("$basePath/manage/logout") {
        val session = call.managementSession(installationRepository)
        val csrf = call.receiveParameters()["csrf"].orEmpty()
        if (session == null || !installationRepository.verifyManagementCsrf(session, csrf)) {
            call.respondManagementHtml(
                status = HttpStatusCode.Forbidden,
                title = "Request rejected",
                body = "<h1>Request rejected</h1><p>The session or CSRF token is invalid.</p>",
            )
            return@post
        }
        installationRepository.deleteManagementSession(session.sessionId)
        call.clearManagementSession(basePath)
        call.appendSecurityHeaders()
        call.respondRedirect(basePath.ifEmpty { "/" })
    }
}

private suspend fun ApplicationCall.managementSession(
    installationRepository: InstallationRepository,
) =
    request.cookies[SESSION_COOKIE_NAME]
        ?.takeIf(String::isNotBlank)
        ?.let { installationRepository.verifyManagementSession(it) }

private fun ApplicationCall.clearManagementSession(basePath: String) {
    response.headers.append(HttpHeaders.SetCookie, buildExpiredSessionCookie(basePath, isHttps()))
    response.headers.append(HttpHeaders.SetCookie, buildExpiredCsrfCookie(basePath, isHttps()))
}

internal suspend fun ApplicationCall.respondManagementHtml(
    title: String,
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    appendSecurityHeaders()
    respondText(
        managementDocument(title, body),
        ContentType.Text.Html,
        status,
    )
}

internal fun ApplicationCall.appendSecurityHeaders() {
    response.headers.append("Cache-Control", "no-store")
    response.headers.append("Pragma", "no-cache")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    )
    if (isHttps()) {
        response.headers.append(
            "Strict-Transport-Security",
            "max-age=31536000; includeSubDomains",
        )
    }
}

internal fun ApplicationCall.isHttps(): Boolean =
    request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim().equals("https", ignoreCase = true)

private fun buildSessionCookie(
    basePath: String,
    value: String,
    secure: Boolean,
): String =
    buildString {
        append(SESSION_COOKIE_NAME)
        append('=')
        append(value)
        append("; Path=")
        append(basePath)
        append("/manage; Max-Age=")
        append(SESSION_TTL_SECONDS)
        append("; HttpOnly; SameSite=Strict")
        if (secure) append("; Secure")
    }

private fun buildCsrfCookie(
    basePath: String,
    value: String,
    secure: Boolean,
): String = buildCookie(CSRF_COOKIE_NAME, value, basePath, SESSION_TTL_SECONDS, secure)

private fun buildExpiredCsrfCookie(basePath: String, secure: Boolean): String =
    buildCookie(CSRF_COOKIE_NAME, "", basePath, 0, secure)

private fun buildCookie(
    name: String,
    value: String,
    basePath: String,
    maxAge: Long,
    secure: Boolean,
): String =
    buildString {
        append("$name=$value; Path=$basePath/manage; Max-Age=$maxAge; HttpOnly; SameSite=Strict")
        if (secure) append("; Secure")
    }

private fun buildExpiredSessionCookie(basePath: String, secure: Boolean): String =
    buildString {
        append(SESSION_COOKIE_NAME)
        append("=; Path=")
        append(basePath)
        append("/manage; Max-Age=0; HttpOnly; SameSite=Strict")
        if (secure) append("; Secure")
    }

private fun managementSessionExpiry(): Instant = Instant.now().plus(SESSION_TTL_HOURS, ChronoUnit.HOURS)

private fun onboardingHtml(basePath: String): String =
    """
    <h1>Nuecagram onboarding</h1>
    <p>Setup stays in Telegram. Start a private chat with the bot, run <code>/start</code>, then run
    <code>/setup &lt;gitlab-base-url&gt; &lt;project-id&gt; [topic-id]</code> in your group.</p>
    <p>Management links redirect into a short-lived session at
    <code>${(basePath + "/manage").html()}</code>. Credentials are only shown when first issued or rotated.</p>
    """.trimIndent()

private fun recoveryHtml(basePath: String): String =
    """
    <h1>Recovery</h1>
    <p>This link is missing, expired, or already used.</p>
    <p>Use Telegram private <code>/start</code>, then run <code>/manage &lt;installation-id&gt;</code>
    or <code>/rotate &lt;installation-id&gt;</code> in the installation group for a fresh management link.</p>
    <p><a href="${basePath.html()}">Back to setup</a></p>
    """.trimIndent()

private fun manageHtml(
    basePath: String,
    installation: InstallationAdminContext,
    csrf: String,
): String =
    buildString {
        append("<h1>Manage installation</h1>")
        append("<p><strong>Installation:</strong> <code>${installation.id.toString().html()}</code></p>")
        append("<p><strong>GitLab:</strong> ${installation.gitlabBaseUrl.html()}</p>")
        installation.gitlabProjectId?.let {
            append("<p><strong>Project:</strong> $it</p>")
        }
        installation.telegramTopicId?.let {
            append("<p><strong>Topic:</strong> $it</p>")
        }
        append("<p><strong>Muted:</strong> ${if (installation.muted) "yes" else "no"}</p>")
        append("<h2>Rotate credential</h2>")
        append("<p>The new GitLab credential is shown once, on the next page only.</p>")
        append(
            "<form method=\"post\" action=\"${(basePath + "/manage/rotate").html()}\">" +
                "<input type=\"hidden\" name=\"csrf\" value=\"${csrf.html()}\">" +
                "<button type=\"submit\">Rotate credential</button></form>",
        )
        append("<h2>Recovery</h2>")
        append(
            "<p>Lost this session? Use private <code>/start</code>, then <code>/manage " +
                "${installation.id.toString().html()}</code> in the installation group.</p>",
        )
        append(
            "<form method=\"post\" action=\"${(basePath + "/manage/logout").html()}\">" +
                "<input type=\"hidden\" name=\"csrf\" value=\"${csrf.html()}\">" +
                "<button type=\"submit\">Log out</button></form>",
        )
    }

private fun rotatedHtml(
    basePath: String,
    installation: InstallationAdminContext,
    credential: String,
): String =
    buildString {
        append("<h1>Credential rotated</h1>")
        append("<p><strong>Installation:</strong> <code>${installation.id.toString().html()}</code></p>")
        append("<p><strong>GitLab credential:</strong> <code>${credential.html()}</code></p>")
        append("<p>Store it now. This page is the only place the raw credential is shown.</p>")
        append(
            "<p><a href=\"${(basePath + "/manage").html()}\">Return to management</a></p>",
        )
    }

internal fun managementDocument(title: String, body: String): String =
    """
    <!doctype html>
    <html lang="en">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${title.html()}</title>
        <style>
          body { font-family: sans-serif; margin: 2rem auto; max-width: 48rem; line-height: 1.5; padding: 0 1rem; }
          code { background: #f4f4f4; padding: 0.1rem 0.3rem; border-radius: 0.2rem; }
          button { font: inherit; padding: 0.6rem 1rem; }
          form { margin: 1rem 0; }
        </style>
      </head>
      <body>
        $body
      </body>
    </html>
    """.trimIndent()

internal fun String.html(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
