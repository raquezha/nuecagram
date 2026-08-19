package net.raquezha.nuecagram.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.db.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.WebAppSessionContext
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.telegram.TelegramWebAppAuth
import org.koin.ktor.ext.inject

private const val WEBAPP_SESSION_COOKIE_NAME = "nuecagram_webapp_session"
private const val WEBAPP_CSRF_COOKIE_NAME = "nuecagram_webapp_csrf"
private const val CSRF_HEADER_NAME = "X-CSRF-Token"
private const val SESSION_TTL_HOURS = 8L
private const val SESSION_TTL_SECONDS = SESSION_TTL_HOURS * 60L * 60L
private const val CONTAINER_MAX_WIDTH_PX = 480


@Serializable
private data class AuthRequestPayload(
    val initData: String,
    val startParam: String? = null,
)

@Serializable
private data class AuthResponseUser(
    val id: Long,
    val firstName: String? = null,
    val username: String? = null,
)

@Serializable
private data class AuthResponsePayload(
    val success: Boolean,
    val user: AuthResponseUser,
    val csrf: String,
    val telegramChatId: Long? = null,
    val telegramTopicId: Long? = null,
)

@Serializable
private data class InstallationResponsePayload(
    val id: String,
    val gitlabBaseUrl: String,
    val gitlabProjectId: Long? = null,
    val telegramChatId: Long,
    val telegramTopicId: Long? = null,
    val muted: Boolean,
)

@Serializable
private data class MuteRequestPayload(
    val muted: Boolean,
)

@Serializable
private data class MuteResponsePayload(
    val id: String,
    val muted: Boolean,
)

@Serializable
private data class TestResponsePayload(
    val success: Boolean,
    val message: String,
)

@Serializable
private data class ErrorResponsePayload(
    val error: String,
)

fun Route.webAppRouting(basePath: String) {
    val installationRepository by inject<InstallationRepository>()
    val telegramService by inject<TelegramService>()
    val config by inject<ConfigWithSecrets>()
    val json = Json { ignoreUnknownKeys = true }

    get("$basePath/webapp") {
        call.appendWebAppSecurityHeaders()
        call.respondText(
            webAppShellHtml(basePath),
            ContentType.Text.Html,
            HttpStatusCode.OK,
        )
    }

    post("$basePath/api/webapp/auth") {
        call.handleWebAppAuth(installationRepository, config, json, basePath)
    }

    get("$basePath/api/webapp/installations") {
        call.handleGetInstallations(installationRepository)
    }

    get("$basePath/api/webapp/installations/{id}") {
        call.handleGetInstallationDetail(installationRepository)
    }

    post("$basePath/api/webapp/installations/{id}/mute") {
        call.handleMuteInstallation(installationRepository, json)
    }

    post("$basePath/api/webapp/installations/{id}/test") {
        call.handleTestInstallation(installationRepository, telegramService)
    }
}

@Suppress("LongMethod")
private suspend fun ApplicationCall.handleWebAppAuth(
    installationRepository: InstallationRepository,
    config: ConfigWithSecrets,
    json: Json,
    basePath: String,
) {
    val bodyText = receiveText()
    val payload = runCatching { json.decodeFromString<AuthRequestPayload>(bodyText) }.getOrNull()
    if (payload == null || payload.initData.isBlank()) {
        respond(HttpStatusCode.BadRequest, ErrorResponsePayload("Missing initData payload"))
        return
    }

    val verified = TelegramWebAppAuth.verifyInitData(payload.initData, config.botApi)
    if (verified == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponsePayload("Invalid or expired initData signature"))
        return
    }

    var resolvedChatId: Long? = null
    var resolvedTopicId: Long? = null

    val startParam = payload.startParam ?: verified.startParam
    if (!startParam.isNullOrBlank() && startParam.startsWith("nonce_")) {
        val rawNonce = startParam.removePrefix("nonce_")
        val nonceCtx = installationRepository.consumeLaunchNonce(rawNonce)
        if (nonceCtx != null && nonceCtx.telegramUserId == verified.user.id) {
            resolvedChatId = nonceCtx.telegramChatId
            resolvedTopicId = nonceCtx.telegramTopicId
        }
    }

    val sessionExpiry = Instant.now().plus(SESSION_TTL_HOURS, ChronoUnit.HOURS)
    val session = installationRepository.issueWebAppSession(
        telegramUserId = verified.user.id,
        telegramChatId = resolvedChatId,
        telegramTopicId = resolvedTopicId,
        expiresAt = sessionExpiry,
    )

    response.headers.append(
        HttpHeaders.SetCookie,
        buildCookie(WEBAPP_SESSION_COOKIE_NAME, session.raw, basePath, SESSION_TTL_SECONDS, isHttps()),
    )
    response.headers.append(
        HttpHeaders.SetCookie,
        buildCookie(WEBAPP_CSRF_COOKIE_NAME, session.csrf, basePath, SESSION_TTL_SECONDS, isHttps()),
    )

    appendWebAppSecurityHeaders()
    respond(
        HttpStatusCode.OK,
        AuthResponsePayload(
            success = true,
            user = AuthResponseUser(
                id = verified.user.id,
                firstName = verified.user.firstName,
                username = verified.user.username,
            ),
            csrf = session.csrf,
            telegramChatId = resolvedChatId,
            telegramTopicId = resolvedTopicId,
        ),
    )
}

private suspend fun ApplicationCall.handleGetInstallations(
    installationRepository: InstallationRepository,
) {
    val session = authenticateWebAppSession(installationRepository) ?: return
    val reqChatId = parameters["chatId"]?.toLongOrNull() ?: session.telegramChatId
    val reqTopicId = parameters["topicId"]?.toLongOrNull() ?: session.telegramTopicId

    val items = installationRepository.listInstallationsForContext(reqChatId, reqTopicId)
    appendWebAppSecurityHeaders()
    respond(HttpStatusCode.OK, items.map { it.toResponsePayload() })
}

private suspend fun ApplicationCall.handleGetInstallationDetail(
    installationRepository: InstallationRepository,
) {
    authenticateWebAppSession(installationRepository) ?: return
    val idParam = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (idParam == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponsePayload("Invalid installation ID"))
        return
    }

    val item = installationRepository.installationAdminContext(idParam)
    if (item == null) {
        respond(HttpStatusCode.NotFound, ErrorResponsePayload("Installation not found"))
        return
    }

    appendWebAppSecurityHeaders()
    respond(HttpStatusCode.OK, item.toResponsePayload())
}

private suspend fun ApplicationCall.handleMuteInstallation(
    installationRepository: InstallationRepository,
    json: Json,
) {
    val session = authenticateWebAppSession(installationRepository) ?: return
    if (!verifyCsrfHeader(installationRepository, session)) return

    val idParam = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (idParam == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponsePayload("Invalid installation ID"))
        return
    }

    val item = installationRepository.installationAdminContext(idParam)
    if (item == null) {
        respond(HttpStatusCode.NotFound, ErrorResponsePayload("Installation not found"))
        return
    }

    val bodyText = receiveText()
    val reqPayload = runCatching { json.decodeFromString<MuteRequestPayload>(bodyText) }.getOrNull()
    val targetMuted = reqPayload?.muted ?: !item.muted

    installationRepository.setMuted(item.id, targetMuted)
    installationRepository.writeAuditEvent(
        installationId = item.id,
        actorType = "webapp_session",
        actorId = session.sessionId.toString(),
        action = if (targetMuted) "webapp_mute" else "webapp_unmute",
    )

    appendWebAppSecurityHeaders()
    respond(HttpStatusCode.OK, MuteResponsePayload(id = item.id.toString(), muted = targetMuted))
}

private suspend fun ApplicationCall.handleTestInstallation(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
) {
    val session = authenticateWebAppSession(installationRepository) ?: return
    if (!verifyCsrfHeader(installationRepository, session)) return

    val idParam = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (idParam == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponsePayload("Invalid installation ID"))
        return
    }

    val item = installationRepository.installationAdminContext(idParam)
    if (item == null) {
        respond(HttpStatusCode.NotFound, ErrorResponsePayload("Installation not found"))
        return
    }

    telegramService.sendMessage(
        Message(
            chatId = item.telegramChatId.toString(),
            text = "Nuecagram delivery test for installation ${item.id}.",
            threadId = item.telegramTopicId,
        ),
    )

    installationRepository.writeAuditEvent(
        installationId = item.id,
        actorType = "webapp_session",
        actorId = session.sessionId.toString(),
        action = "webapp_test",
    )

    appendWebAppSecurityHeaders()
    respond(HttpStatusCode.OK, TestResponsePayload(success = true, message = "Test delivery dispatched."))
}

private suspend fun ApplicationCall.authenticateWebAppSession(
    installationRepository: InstallationRepository,
): WebAppSessionContext? {
    val sessionCookie = request.cookies[WEBAPP_SESSION_COOKIE_NAME]?.takeIf(String::isNotBlank)
    if (sessionCookie == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponsePayload("Web App session required"))
        return null
    }

    val session = installationRepository.verifyWebAppSession(sessionCookie)
    if (session == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponsePayload("Session expired or invalid"))
        return null
    }
    return session
}

private suspend fun ApplicationCall.verifyCsrfHeader(
    installationRepository: InstallationRepository,
    session: WebAppSessionContext,
): Boolean {
    val csrfHeader = request.headers[CSRF_HEADER_NAME].orEmpty()
    if (!installationRepository.verifyWebAppCsrf(session, csrfHeader)) {
        respond(HttpStatusCode.Forbidden, ErrorResponsePayload("Invalid CSRF token"))
        return false
    }
    return true
}

private fun InstallationAdminContext.toResponsePayload() = InstallationResponsePayload(
    id = id.toString(),
    gitlabBaseUrl = gitlabBaseUrl,
    gitlabProjectId = gitlabProjectId,
    telegramChatId = telegramChatId,
    telegramTopicId = telegramTopicId,
    muted = muted,
)

internal fun ApplicationCall.appendWebAppSecurityHeaders() {
    response.headers.append("Cache-Control", "no-store, no-cache, must-revalidate")
    response.headers.append("Pragma", "no-cache")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self' https://telegram.org; " +
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
            "font-src 'self' https://fonts.gstatic.com; img-src 'self' data: https:; frame-ancestors 'none'",
    )
    if (isHttps()) {
        response.headers.append(
            "Strict-Transport-Security",
            "max-age=31536000; includeSubDomains",
        )
    }
}

private fun buildCookie(
    name: String,
    value: String,
    basePath: String,
    maxAge: Long,
    secure: Boolean,
): String =
    buildString {
        append("$name=$value; Path=$basePath; Max-Age=$maxAge; HttpOnly; SameSite=Strict")
        if (secure) append("; Secure")
    }

@Suppress("LongMethod", "MagicNumber")
private fun webAppShellHtml(basePath: String): String = """
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Nuecagram Management</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Reddit+Mono:wght@400;600&family=Space+Grotesk:wght@500;700&display=swap" rel="stylesheet">
    <script src="https://telegram.org/js/telegram-web-app.js"></script>
    <style>
      :root {
        --bg-color: #eee4d5;
        --card-bg: rgba(255, 255, 255, 0.92);
        --card-border: #dfd5c6;
        --text-main: #2c251e;
        --accent-tg: #229ed9;
        --success: #2a684d;
        --danger: #a62b1e;
        --code-bg: #eae1d5;
        --font-grotesk: 'Space Grotesk', sans-serif;
        --font-mono: 'Reddit Mono', monospace;
      }
      * { box-sizing: border-box; }
      body {
        margin: 0; padding: 1rem;
        background-color: var(--bg-color);
        font-family: var(--font-mono);
        color: var(--text-main);
      }
      .container { max-width: ${CONTAINER_MAX_WIDTH_PX}px; margin: 0 auto; }
      .context-banner {
        background: var(--code-bg); padding: 0.75rem 1rem; border-radius: 8px;
        margin-bottom: 1rem; border: 1px solid var(--card-border); font-size: 0.85rem;
      }
      .card {
        background: var(--card-bg); border: 1px solid var(--card-border);
        border-radius: 12px; padding: 1rem; margin-bottom: 1rem;
      }
      .card-header { display: flex; justify-content: space-between; align-items: center; }
      .card-id { font-weight: 700; font-family: var(--font-grotesk); }
      .badge { padding: 0.2rem 0.6rem; border-radius: 999px; font-size: 0.75rem; font-weight: 700; }
      .badge-active { background: #e6f4ea; color: var(--success); }
      .badge-muted { background: #fff5f5; color: var(--danger); }
      .actions { display: flex; gap: 0.5rem; margin-top: 0.85rem; }
      button {
        font-family: var(--font-grotesk); font-weight: 600; font-size: 0.85rem;
        padding: 0.4rem 0.8rem; border-radius: 6px; border: 1px solid var(--card-border);
        background: #fff; cursor: pointer;
      }
    </style>
  </head>
  <body>
    <div class="container">
      <div id="contextBanner" class="context-banner">
        <strong>Context:</strong> <span id="contextText">Resolving Telegram context...</span>
      </div>
      <div id="installationsList">
        <div class="card"><p>Loading installations...</p></div>
      </div>
    </div>
    <script>
      let userCsrf = '';

      async function initWebApp() {
        if (window.Telegram && window.Telegram.WebApp) {
          window.Telegram.WebApp.ready();
          window.Telegram.WebApp.expand();
        }
        const tgInitData = (window.Telegram && window.Telegram.WebApp && window.Telegram.WebApp.initData) || '';
        try {
          const res = await fetch('${basePath}/api/webapp/auth', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ initData: tgInitData })
          });
          if (res.ok) {
            const data = await res.json();
            userCsrf = data.csrf;
            document.getElementById('contextText').innerText = data.telegramChatId
              ? 'Chat #' + data.telegramChatId + (data.telegramTopicId ? ' (Topic #' + data.telegramTopicId + ')' : '')
              : 'All Accessible Installations';
            await loadInstallations();
          } else {
            document.getElementById('contextText').innerText = 'Authentication required.';
          }
        } catch (e) {
          document.getElementById('contextText').innerText = 'Error connecting to server.';
        }
      }

      async function loadInstallations() {
        const res = await fetch('${basePath}/api/webapp/installations');
        if (!res.ok) return;
        const items = await res.json();
        const el = document.getElementById('installationsList');
        if (items.length === 0) {
          el.innerHTML = '<div class="card"><p>No installations found for this context.</p></div>';
          return;
        }
        el.innerHTML = items.map(function(item) {
          var statusBadge = item.muted ? '<span class="badge badge-muted">Muted</span>' : '<span class="badge badge-active">Active</span>';
          var muteText = item.muted ? 'Unmute' : 'Mute';
          return '<div class="card">' +
            '<div class="card-header">' +
            '<span class="card-id">' + item.id.substring(0, 8) + '</span>' +
            statusBadge +
            '</div>' +
            '<p style="margin: 0.5rem 0 0.25rem; font-size: 0.85rem;">GitLab: ' + item.gitlabBaseUrl + '</p>' +
            '<div class="actions">' +
            '<button onclick="testDelivery('' + item.id + '')">Test</button>' +
            '<button onclick="toggleMute('' + item.id + '', ' + (!item.muted) + ')">' + muteText + '</button>' +
            '</div>' +
            '</div>';
        }).join('');
      }

      async function toggleMute(id, muted) {
        await fetch('${basePath}/api/webapp/installations/' + id + '/mute', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': userCsrf },
          body: JSON.stringify({ muted: muted })
        });
        await loadInstallations();
      }

      async function testDelivery(id) {
        await fetch('${basePath}/api/webapp/installations/' + id + '/test', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': userCsrf }
        });
      }

      initWebApp();
    </script>
  </body>
</html>
""".trimIndent()