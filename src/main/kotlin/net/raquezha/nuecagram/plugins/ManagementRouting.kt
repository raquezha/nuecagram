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

    val rootPath = basePath.ifEmpty { "/" }
    get(rootPath) {
        call.respondManagementHtml(
            title = "Nuecagram setup",
            body = onboardingHtml(basePath),
        )
    }

    if (basePath.isNotEmpty()) {
        get("$basePath/") {
            call.respondManagementHtml(
                title = "Nuecagram setup",
                body = onboardingHtml(basePath),
            )
        }
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
    <h2>How to Start in 3 Steps</h2>

    <div class="step-card">
      <h3>Step 1: Add Bot to Telegram Group</h3>
      <p>Add <a href="https://t.me/NuecagramBot" target="_blank" rel="noopener"><strong>@NuecagramBot</strong></a> to your destination Telegram group or forum topic and promote it to <strong>Administrator</strong>.</p>
    </div>

    <div class="step-card">
      <h3>Step 2: Start Private Onboarding</h3>
      <p>Open a private chat with <strong>@NuecagramBot</strong> and send <code>/start</code>.</p>
    </div>

    <div class="step-card">
      <h3>Step 3: Connect your GitLab Repository</h3>
      <p>In your Telegram group (or forum topic), run:</p>
      <p><code>/setup &lt;gitlab-base-url&gt; &lt;project-id&gt;</code></p>
      <p>For example: <code>/setup https://gitlab.com 12345678</code></p>
      <p style="margin-bottom: 0;">Nuecagram will send your secret webhook URL and token directly to your private chat.</p>
    </div>

    <h2>Managing your Setup</h2>
    <p>To rotate secrets, check status, or mute notifications, send <code>/manage &lt;installation-id&gt;</code> in your Telegram group to receive a secure, single-use web link at <code>${(basePath + "/manage").html()}</code>.</p>

    <h2>Documentation</h2>
    <p>Read the full operations and integration guide at <a href="${(basePath + "/docs").html()}">${(basePath + "/docs").html()}</a>.</p>
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

internal fun managementDocument(title: String, body: String): String {
    val version = net.raquezha.nuecagram.appVersion()
    return """
    <!doctype html>
    <html lang="en">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${title.html()}</title>
        <link rel="preconnect" href="https://fonts.googleapis.com">
        <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
        <link href="https://fonts.googleapis.com/css2?family=Reddit+Mono:ital,wght@0,200..900;1,200..900&family=Space+Grotesk:wght@300..700&display=swap" rel="stylesheet">
        <style>
          body { font-family: 'Reddit Mono', monospace; margin: 3rem auto; max-width: 48rem; line-height: 1.6; padding: 2.5rem; color: #2c251e; background-color: #f6f2ec; background-image: radial-gradient(ellipse at center, rgba(255,255,255,0.4) 0%, rgba(225,215,200,0.3) 100%), url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='600' height='600'%3E%3Cfilter id='crumpled'%3E%3CfeTurbulence type='turbulence' baseFrequency='0.015 0.035' numOctaves='6' result='noise'/%3E%3CfeDiffuseLighting in='noise' lighting-color='%23ffffff' surfaceScale='14' result='diffuse'%3E%3CfeDistantLight azimuth='45' elevation='35'/%3E%3C/feDiffuseLighting%3E%3CfeSpecularLighting in='noise' surfaceScale='12' specularConstant='1.8' specularExponent='6' lighting-color='%23ffffff' result='specular'%3E%3CfeDistantLight azimuth='45' elevation='35'/%3E%3C/feSpecularLighting%3E%3CfeArithmetic in='diffuse' in2='specular' k1='0' k2='0.8' k3='0.8' k4='0' result='lightMap'/%3E%3CfeComponentTransfer in='lightMap'%3E%3CfeFuncR type='linear' slope='0.85' intercept='0.15'/%3E%3CfeFuncG type='linear' slope='0.82' intercept='0.15'/%3E%3CfeFuncB type='linear' slope='0.75' intercept='0.15'/%3E%3C/feComponentTransfer%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23crumpled)' opacity='0.65'/%3E%3C/svg%3E"); border: 1px solid #dfd5c6; border-radius: 0.5rem; box-shadow: 0 16px 50px rgba(60, 45, 25, 0.1), inset 0 0 100px rgba(195, 175, 150, 0.2); }
          code { font-family: 'Reddit Mono', monospace; background: #eae1d5; color: #8b3a00; padding: 0.15rem 0.4rem; border-radius: 0.25rem; font-size: 0.9em; }
          button { font-family: 'Space Grotesk', sans-serif; font-weight: 600; padding: 0.5rem 1.2rem; background: #2c251e; color: #ffffff; border: none; border-radius: 0.375rem; cursor: pointer; transition: background 0.2s ease; }
          button:hover { background: #0088cc; }
          form { margin: 1rem 0; }
          .site-header { margin-bottom: 1rem; }
          .site-header .header-top { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
          .site-header .title { font-family: 'Space Grotesk', sans-serif; font-size: 2.4rem; font-weight: 700; margin: 0; text-transform: lowercase; letter-spacing: -0.03em; color: #1a1612; }
          .site-header .right-meta { display: flex; align-items: center; gap: 0.5rem; }
          .header-btn { display: inline-flex; align-items: center; gap: 0.4rem; height: 32px; padding: 0 0.75rem; border-radius: 0.375rem; font-family: 'Space Grotesk', sans-serif; font-size: 0.825rem; font-weight: 600; text-decoration: none; box-sizing: border-box; transition: all 0.2s ease; }
          .btn-telegram { background-color: #229ed9; color: #ffffff; border: 1px solid #1c8bc0; }
          .btn-telegram:hover { background-color: #1c8cc3; color: #ffffff; }
          .btn-github { background-color: #2c251e; color: #ffffff; border: 1px solid #1a1612; }
          .btn-github:hover { background-color: #1a1612; color: #ffffff; }
          .site-header .subtitle { text-align: left; font-size: 0.95rem; color: #6e6154; margin-top: 0.4rem; max-width: 28rem; line-height: 1.45; }
          hr { border: 0; border-top: 1px solid #dfd5c6; margin-bottom: 2rem; }
          .step-card { background: rgba(255, 255, 255, 0.75); border: 1px solid #dfd5c6; border-radius: 0.5rem; padding: 1rem 1.25rem; margin-bottom: 1rem; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.02); }
          .step-card h3 { margin-top: 0; margin-bottom: 0.5rem; font-family: 'Space Grotesk', sans-serif; font-size: 1.1rem; color: #1a1612; }
        </style>
      </head>
      <body>
        <header class="site-header">
          <div class="header-top">
            <div class="title">nuecagram</div>
            <div class="right-meta">
              <a href="https://t.me/NuecagramBot" target="_blank" rel="noopener" class="header-btn btn-telegram" aria-label="Open Telegram Bot">
                <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M12 0C5.37 0 0 5.37 0 12s5.37 12 12 12 12-5.37 12-12S18.63 0 12 0zm5.56 8.16l-1.97 9.28c-.15.68-.55.84-1.12.52l-3.01-2.22-1.45 1.4c-.16.16-.3.3-.61.3l.21-3.05 5.56-5.02c.24-.22-.05-.34-.37-.13l-6.87 4.33-2.96-.92c-.64-.2-.65-.64.13-.95l11.57-4.46c.54-.2 1.01.13.89.92z"/></svg>
                <span>@NuecagramBot</span>
              </a>
              <a href="https://github.com/raquezha/nuecagram" target="_blank" rel="noopener" class="header-btn btn-github" aria-label="GitHub Repository">
                <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true" fill="currentColor"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.28.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"></path></svg>
                <span>v${version.html()}</span>
              </a>
            </div>
          </div>
          <div class="subtitle">Instant GitLab alerts in Telegram.</div>
        </header>
        <hr>
        $body
      </body>
    </html>
    """.trimIndent()
}

internal fun String.html(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
