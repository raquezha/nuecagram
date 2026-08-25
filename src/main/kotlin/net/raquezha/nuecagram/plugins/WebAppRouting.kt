@file:Suppress("TooManyFunctions")

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
import net.raquezha.nuecagram.telegram.isTelegramAdmin
import net.raquezha.nuecagram.configuredPublicUrl
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
    val sessionToken: String? = null,
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

@Serializable
private data class CreateInstallationRequestPayload(
    val gitlabBaseUrl: String,
    val gitlabProjectId: Long,
    val telegramChatId: Long? = null,
    val telegramTopicId: Long? = null,
)

@Serializable
private data class CreateInstallationResponsePayload(
    val installation: InstallationResponsePayload,
    val credential: String,
    val webhookUrl: String,
)

@Serializable
private data class RotateResponsePayload(
    val id: String,
    val credential: String,
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

    get("$basePath/webapp/app.js") {
        call.appendWebAppSecurityHeaders()
        call.respondText(
            webAppJsScript(basePath),
            ContentType.Text.JavaScript,
            HttpStatusCode.OK,
        )
    }

    post("$basePath/api/webapp/auth") {
        call.handleWebAppAuth(installationRepository, config, json, basePath)
    }

    get("$basePath/api/webapp/installations") {
        call.handleGetInstallations(installationRepository, telegramService)
    }

    get("$basePath/api/webapp/installations/{id}") {
        call.handleGetInstallationDetail(installationRepository, telegramService)
    }

    post("$basePath/api/webapp/installations/{id}/mute") {
        call.handleMuteInstallation(installationRepository, telegramService, json)
    }

    post("$basePath/api/webapp/installations/{id}/test") {
        call.handleTestInstallation(installationRepository, telegramService)
    }

    post("$basePath/api/webapp/installations") {
        call.handleCreateInstallation(installationRepository, telegramService, config, json, basePath)
    }

    post("$basePath/api/webapp/installations/{id}/rotate") {
        call.handleRotateInstallation(installationRepository, telegramService, config, basePath)
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

    val startParam = payload.startParam?.takeIf { it.isNotBlank() } ?: verified.startParam
    val (resolvedChatId, resolvedTopicId) = resolveLaunchContext(
        startParam,
        verified.user.id,
        installationRepository,
    )

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

    val sessionTokenValue = session.raw
    appendWebAppSecurityHeaders()
    respond(
        HttpStatusCode.OK,
        AuthResponsePayload(
            true,
            AuthResponseUser(
                id = verified.user.id,
                firstName = verified.user.firstName,
                username = verified.user.username,
            ),
            session.csrf,
            sessionTokenValue,
            resolvedChatId,
            resolvedTopicId,
        ),
    )
}

private fun ApplicationCall.extractSessionToken(): String? =
    request.cookies[WEBAPP_SESSION_COOKIE_NAME]?.takeIf(String::isNotBlank)
        ?: request.headers["X-Session-Token"]?.takeIf(String::isNotBlank)
        ?: request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()?.takeIf(String::isNotBlank)

private suspend fun ApplicationCall.resolveLaunchContext(
    startParam: String?,
    verifiedUserId: Long,
    installationRepository: InstallationRepository,
): Pair<Long?, Long?> {
    val nonceCtx = startParam?.takeIf { it.startsWith("nonce_") }?.let { param ->
        installationRepository.consumeLaunchNonce(param.removePrefix("nonce_"))
    }?.takeIf { it.telegramUserId == verifiedUserId }

    val existingSession = extractSessionToken()
        ?.let { installationRepository.verifyWebAppSession(it) }
        ?.takeIf { it.telegramUserId == verifiedUserId }

    return when {
        nonceCtx != null -> Pair(nonceCtx.telegramChatId, nonceCtx.telegramTopicId)
        existingSession != null -> Pair(existingSession.telegramChatId, existingSession.telegramTopicId)
        else -> Pair(null, null)
    }
}

private suspend fun ApplicationCall.handleGetInstallations(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
) {
    val session = authenticateWebAppSession(installationRepository) ?: return
    if (!verifyAdminStatus(session, telegramService)) return

    val isGroupContext = session.telegramChatId != null && session.telegramChatId < 0
    val items = if (isGroupContext) {
        installationRepository.listInstallationsForContext(session.telegramChatId, session.telegramTopicId)
    } else {
        val adminChatIds = mutableMapOf<Long, Boolean>()
        val recorded = installationRepository.installationsForAdmin(session.telegramUserId)
        val candidates =
            if (recorded.isNotEmpty()) {
                recorded
            } else {
                installationRepository.listInstallationsForContext(null, null)
            }
        candidates.filter { inst ->
            adminChatIds.getOrPut(inst.telegramChatId) {
                val status = runCatching {
                    telegramService.chatMemberStatus(inst.telegramChatId, session.telegramUserId)
                }.getOrNull()
                isTelegramAdmin(status)
            }
        }
    }
    appendWebAppSecurityHeaders()
    respond(HttpStatusCode.OK, items.map { it.toResponsePayload() })
}

private suspend fun ApplicationCall.handleGetInstallationDetail(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
) {
    val session = authenticateWebAppSession(installationRepository) ?: return
    if (!verifyAdminStatus(session, telegramService)) return

    val idParam = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (idParam == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponsePayload("Invalid installation ID"))
        return
    }

    val item = installationRepository.installationAdminContext(idParam)
    if (item == null || !canAccess(session, item, telegramService)) {
        respond(HttpStatusCode.NotFound, ErrorResponsePayload("Installation not found"))
        return
    }

    appendWebAppSecurityHeaders()
    respond(HttpStatusCode.OK, item.toResponsePayload())
}

private suspend fun ApplicationCall.handleMuteInstallation(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
    json: Json,
) {
    val session = authenticateWebAppSession(installationRepository) ?: return
    if (!verifyAdminStatus(session, telegramService)) return
    if (!verifyCsrfHeader(installationRepository, session)) return

    val idParam = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (idParam == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponsePayload("Invalid installation ID"))
        return
    }

    val item = installationRepository.installationAdminContext(idParam)
    if (item == null || !canAccess(session, item, telegramService)) {
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
        actorId = session.telegramUserId.toString(),
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
    if (!verifyAdminStatus(session, telegramService)) return
    if (!verifyCsrfHeader(installationRepository, session)) return

    val idParam = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (idParam == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponsePayload("Invalid installation ID"))
        return
    }

    val item = installationRepository.installationAdminContext(idParam)
    if (item == null || !canAccess(session, item, telegramService)) {
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
        actorId = session.telegramUserId.toString(),
        action = "webapp_test",
    )

    appendWebAppSecurityHeaders()
    respond(HttpStatusCode.OK, TestResponsePayload(success = true, message = "Test delivery dispatched."))
}
private data class WebAppResponseSpec(
    val status: HttpStatusCode,
    val payload: Any,
)

private suspend fun ApplicationCall.handleCreateInstallation(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
    config: ConfigWithSecrets,
    json: Json,
    basePath: String,
) {
    val spec = processCreateInstallation(installationRepository, telegramService, config, json, basePath)
    if (spec != null) {
        appendWebAppSecurityHeaders()
        respond(spec.status, spec.payload)
    }
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    error: String,
): WebAppResponseSpec? {
    respond(status, ErrorResponsePayload(error))
    return null
}

private data class TargetDestination(val chatId: Long?, val topicId: Long?)

private fun resolveTargetDestination(
    session: WebAppSessionContext,
    parsed: CreateInstallationRequestPayload?,
): TargetDestination {
    val isGroup = session.telegramChatId != null && session.telegramChatId < 0
    return TargetDestination(
        chatId = if (isGroup) session.telegramChatId else parsed?.telegramChatId,
        topicId = if (isGroup) session.telegramTopicId else parsed?.telegramTopicId,
    )
}

private suspend fun resolveDmId(
    session: WebAppSessionContext,
    repository: InstallationRepository,
): Long? {
    val isDmSession = session.telegramChatId != null &&
        session.telegramChatId > 0 &&
        session.telegramChatId == session.telegramUserId
    return if (isDmSession) {
        session.telegramUserId
    } else {
        repository.telegramPrivateChatId(session.telegramUserId)
    }
}

private suspend fun isTargetAdmin(
    session: WebAppSessionContext,
    targetChatId: Long?,
    telegramService: TelegramService,
): Boolean {
    if (session.telegramChatId != null && session.telegramChatId < 0) return true
    if (targetChatId == null || targetChatId >= 0) return true
    val status = runCatching { telegramService.chatMemberStatus(targetChatId, session.telegramUserId) }.getOrNull()
    return isTelegramAdmin(status)
}

private suspend fun ApplicationCall.processCreateInstallation(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
    config: ConfigWithSecrets,
    json: Json,
    basePath: String,
): WebAppResponseSpec? {
    val session = authenticateWebAppSession(installationRepository) ?: return null
    if (!verifyAdminStatus(session, telegramService)) return null
    if (!verifyCsrfHeader(installationRepository, session)) return null

    val bodyText = receiveText()
    val parsed = runCatching { json.decodeFromString<CreateInstallationRequestPayload>(bodyText) }.getOrNull()
    val target = resolveTargetDestination(session, parsed)
    val dmId = resolveDmId(session, installationRepository)

    return when {
        dmId == null -> respondError(HttpStatusCode.Forbidden, "DM bootstrap required")
        target.chatId == null || target.chatId >= 0 ->
            respondError(HttpStatusCode.BadRequest, "Valid target Telegram group chat ID required")
        !isTargetAdmin(session, target.chatId, telegramService) ->
            respondError(
                HttpStatusCode.Forbidden,
                "Telegram group administrator permissions required for target group",
            )
        parsed == null || parsed.gitlabBaseUrl.isBlank() ->
            respondError(HttpStatusCode.BadRequest, "Missing or invalid payload")
        !parsed.gitlabBaseUrl.startsWith("https://") ->
            respondError(HttpStatusCode.BadRequest, "gitlabBaseUrl must start with https://")
        else -> createAndRespond(
            installationRepository = installationRepository,
            config = config,
            basePath = basePath,
            parsed = parsed,
            targetChatId = target.chatId,
            targetTopicId = target.topicId,
            telegramUserId = session.telegramUserId,
        )
    }
}

private suspend fun createAndRespond(
    installationRepository: InstallationRepository,
    config: ConfigWithSecrets,
    basePath: String,
    parsed: CreateInstallationRequestPayload,
    targetChatId: Long,
    targetTopicId: Long?,
    telegramUserId: Long,
): WebAppResponseSpec {
    val installation = installationRepository.createInstallation(
        gitlabBaseUrl = parsed.gitlabBaseUrl.trimEnd('/'),
        gitlabProjectId = parsed.gitlabProjectId,
        telegramChatId = targetChatId,
        telegramTopicId = targetTopicId,
    )
    installationRepository.recordInstallationAdmin(installation.id, telegramUserId)
    val tok = installationRepository.issueWebhookSecret(installation.id)
    installationRepository.writeAuditEvent(
        installationId = installation.id,
        actorType = "webapp_session",
        actorId = telegramUserId.toString(),
        action = "webapp_setup",
    )
    return WebAppResponseSpec(
        HttpStatusCode.Created,
        toCreateResponse(installation, tok, config.webhookEndpointUrl(basePath)),
    )
}

private fun toCreateResponse(
    r: net.raquezha.nuecagram.db.InstallationRecord,
    t: net.raquezha.nuecagram.db.IssuedCredential,
    url: String,
): CreateInstallationResponsePayload =
    CreateInstallationResponsePayload(
        installation = r.toAdminContext(muted = false).toResponsePayload(),
        credential = t.raw,
        webhookUrl = url,
    )

private suspend fun ApplicationCall.handleRotateInstallation(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
    config: ConfigWithSecrets,
    basePath: String,
) {
    val spec = processRotateInstallation(installationRepository, telegramService, config, basePath)
    if (spec != null) {
        appendWebAppSecurityHeaders()
        respond(spec.status, spec.payload)
    }
}

private suspend fun ApplicationCall.processRotateInstallation(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
    config: ConfigWithSecrets,
    basePath: String,
): WebAppResponseSpec? {
    val session = authenticateWebAppSession(installationRepository) ?: return null
    if (!verifyAdminStatus(session, telegramService)) return null
    if (!verifyCsrfHeader(installationRepository, session)) return null

    val dmId = resolveDmId(session, installationRepository)
    val idParam = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val item = if (idParam != null) installationRepository.installationAdminContext(idParam) else null

    return when {
        dmId == null -> {
            respond(HttpStatusCode.Forbidden, ErrorResponsePayload("DM bootstrap required"))
            null
        }
        idParam == null -> {
            respond(HttpStatusCode.BadRequest, ErrorResponsePayload("Invalid installation ID"))
            null
        }
        item == null || !canAccess(session, item, telegramService) -> {
            respond(HttpStatusCode.NotFound, ErrorResponsePayload("Installation not found"))
            null
        }
        else -> {
            val tok = installationRepository.rotateWebhookSecret(
                installationId = item.id,
                graceUntil = java.time.Instant.now(),
            )
            installationRepository.writeAuditEvent(
                installationId = item.id,
                actorType = "webapp_session",
                actorId = session.telegramUserId.toString(),
                action = "webapp_rotate",
            )
            WebAppResponseSpec(HttpStatusCode.OK, RotateResponsePayload(id = item.id.toString(), credential = tok.raw))
        }
    }
}

private suspend fun canAccess(
    session: WebAppSessionContext,
    item: InstallationAdminContext,
    telegramService: TelegramService,
): Boolean {
    if (session.telegramChatId != null && session.telegramChatId < 0) {
        if (item.telegramChatId != session.telegramChatId) return false
        if (session.telegramTopicId != null && item.telegramTopicId != session.telegramTopicId) return false
        return true
    }
    val status = runCatching {
        telegramService.chatMemberStatus(item.telegramChatId, session.telegramUserId)
    }.getOrNull()
    return isTelegramAdmin(status)
}

private suspend fun ApplicationCall.verifyAdminStatus(
    session: WebAppSessionContext,
    telegramService: TelegramService,
): Boolean {
    val chatId = session.telegramChatId ?: return true
    if (chatId > 0) return true
    val status = runCatching { telegramService.chatMemberStatus(chatId, session.telegramUserId) }.getOrNull()
    if (!isTelegramAdmin(status)) {
        respond(HttpStatusCode.Forbidden, ErrorResponsePayload("Telegram group administrator permissions required"))
        return false
    }
    return true
}

private suspend fun ApplicationCall.authenticateWebAppSession(
    installationRepository: InstallationRepository,
): WebAppSessionContext? {
    val sessionToken = extractSessionToken()
    if (sessionToken == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponsePayload("Web App session required"))
        return null
    }

    val session = installationRepository.verifyWebAppSession(sessionToken)
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

private fun net.raquezha.nuecagram.db.InstallationRecord.toAdminContext(muted: Boolean) =
    InstallationAdminContext(
        id = id,
        gitlabBaseUrl = gitlabBaseUrl,
        gitlabProjectId = gitlabProjectId,
        telegramChatId = telegramChatId,
        telegramTopicId = telegramTopicId,
        muted = muted,
    )

private fun ConfigWithSecrets.publicBaseUrl(): String = configuredPublicUrl()

private fun ConfigWithSecrets.webhookEndpointUrl(basePath: String): String = "${publicBaseUrl()}$basePath/webhook"

internal fun ApplicationCall.appendWebAppSecurityHeaders() {
    response.headers.append("Cache-Control", "no-store, no-cache, must-revalidate")
    response.headers.append("Pragma", "no-cache")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self' https://telegram.org; " +
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
            "font-src 'self' https://fonts.gstatic.com; img-src 'self' data: https:; " +
            "frame-ancestors 'self' https://web.telegram.org https://*.telegram.org https://telegram.org;",
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
        val sameSite = if (secure) "None" else "Lax"
        val path = if (basePath.isBlank()) "/" else basePath
        append("$name=$value; Path=$path; Max-Age=$maxAge; HttpOnly; SameSite=$sameSite")
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
        --code-text: #8b3a00;
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
      <div id="contextBanner" class="context-banner" style="display:flex;justify-content:space-between;align-items:center;">
        <div><strong>Context:</strong> <span id="contextText">Resolving Telegram context...</span></div>
        <button id="btnAdd" style="display:none;font-size:0.8rem;padding:0.25rem 0.6rem;background:var(--accent-tg);color:#fff;border:none;border-radius:4px;cursor:pointer;">+ Add</button>
      </div>
      <div id="installationsList">
        <div class="card"><p>Loading installations...</p></div>
      </div>
      <div id="screen-wizard" style="display:none;">
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:0.75rem;">
          <strong style="font-family:var(--font-grotesk);">Connect New Project</strong>
          <span id="btnCancel" style="font-size:0.78rem;color:var(--accent-tg);cursor:pointer;">Cancel</span>
        </div>
        <div style="display:flex;flex-direction:column;gap:0.35rem;margin-bottom:0.75rem;">
          <label style="font-family:var(--font-grotesk);font-size:0.82rem;font-weight:600;">GitLab Base URL</label>
          <input id="inUrl" style="font-family:var(--font-mono);padding:0.55rem 0.75rem;border:1px solid var(--card-border);border-radius:0.375rem;background:#fff;color:var(--text-main);font-size:0.85rem;width:100%;box-sizing:border-box;" type="text" value="https://gitlab.com">
          <span id="errUrl" style="font-size:0.78rem;color:var(--danger);min-height:1em;"></span>
        </div>
        <div style="display:flex;flex-direction:column;gap:0.35rem;margin-bottom:0.75rem;">
          <label style="font-family:var(--font-grotesk);font-size:0.82rem;font-weight:600;">GitLab Project ID</label>
          <input id="inPid" style="font-family:var(--font-mono);padding:0.55rem 0.75rem;border:1px solid var(--card-border);border-radius:0.375rem;background:#fff;color:var(--text-main);font-size:0.85rem;width:100%;box-sizing:border-box;" type="number" placeholder="e.g. 12345678">
          <span id="errPid" style="font-size:0.78rem;color:var(--danger);min-height:1em;"></span>
        </div>
        <div style="display:flex;flex-direction:column;gap:0.35rem;margin-bottom:0.75rem;">
          <label style="font-family:var(--font-grotesk);font-size:0.82rem;font-weight:600;">Notification Target</label>
          <input id="inDest" style="font-family:var(--font-mono);padding:0.55rem 0.75rem;border:1px solid var(--card-border);border-radius:0.375rem;background:#fff;color:var(--text-main);font-size:0.85rem;width:100%;box-sizing:border-box;opacity:0.8;" type="text" readonly>
        </div>
        <p style="font-size:0.78rem;color:#6e6154;line-height:1.4;margin-bottom:0.75rem;">Nuecagram generates a unique webhook URL and token for your GitLab settings.</p>
        <div id="wizErr" style="font-size:0.78rem;color:var(--danger);margin-bottom:0.5rem;min-height:1em;"></div>
        <button id="btnCreate" style="font-family:var(--font-grotesk);font-weight:700;font-size:0.9rem;width:100%;padding:0.7rem;border-radius:0.375rem;border:none;background:var(--accent-tg);color:#fff;cursor:pointer;">Create Installation</button>
      </div>
      <div id="screen-reveal" style="display:none;">
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:0.75rem;">
          <strong id="revTitle" style="font-family:var(--font-grotesk);">Credential Issued</strong>
          <span id="btnRevDone" style="font-size:0.78rem;color:var(--accent-tg);cursor:pointer;">Done</span>
        </div>
        <div style="background:#fff5f5;border:1px solid var(--danger);border-radius:0.375rem;padding:0.6rem 0.85rem;font-size:0.8rem;color:var(--danger);font-weight:600;margin-bottom:0.75rem;">Store this token now in GitLab settings. Shown <strong>only once</strong>.</div>
        <div style="background:var(--code-bg);border:1px dashed var(--code-text, #8b3a00);border-radius:0.5rem;padding:0.85rem;margin-bottom:0.75rem;">
          <span style="font-size:0.72rem;text-transform:uppercase;font-weight:700;color:#8b3a00;display:block;margin-bottom:0.35rem;">Webhook Token</span>
          <span id="revSecret" style="font-family:var(--font-mono);color:#8b3a00;font-weight:700;font-size:0.9rem;word-break:break-all;"></span>
        </div>
        <div style="display:flex;flex-direction:column;gap:0.35rem;margin-bottom:0.75rem;">
          <label style="font-family:var(--font-grotesk);font-size:0.82rem;font-weight:600;">Webhook URL</label>
          <input id="revUrl" style="font-family:var(--font-mono);padding:0.55rem 0.75rem;border:1px solid var(--card-border);border-radius:0.375rem;background:#fff;color:var(--text-main);font-size:0.85rem;width:100%;box-sizing:border-box;" type="text" readonly>
        </div>
        <button id="btnCopy" style="font-family:var(--font-grotesk);font-weight:700;font-size:0.9rem;width:100%;padding:0.7rem;border-radius:0.375rem;border:none;background:var(--accent-tg);color:#fff;cursor:pointer;margin-bottom:0.5rem;">Copy Token</button>
      </div>
    </div>
    <script src="${basePath}/webapp/app.js"></script>
  </body>
</html>
""".trimIndent()

@Suppress("LongMethod", "MagicNumber")
private fun webAppJsScript(basePath: String): String = """
let userCsrf = '';
let sToken = '';
let currentContext = {};

function getAuthHeaders(extra) {
  const h = Object.assign({}, extra || {});
  if (userCsrf) h['X-CSRF-Token'] = userCsrf;
  if (sToken) h['X-Session-' + 'Token'] = sToken;
  return h;
}

function extractStartParam() {
  if (window.Telegram && window.Telegram.WebApp && window.Telegram.WebApp.initDataUnsafe && window.Telegram.WebApp.initDataUnsafe.start_param) {
    return window.Telegram.WebApp.initDataUnsafe.start_param;
  }
  const searchParams = new URLSearchParams(window.location.search);
  const fromSearch = searchParams.get('startapp') || searchParams.get('tgWebAppStartParam') || searchParams.get('start_param');
  if (fromSearch) return fromSearch;

  if (window.location.hash && window.location.hash.length > 1) {
    const hashParams = new URLSearchParams(window.location.hash.substring(1));
    const fromHash = hashParams.get('tgWebAppStartParam') || hashParams.get('startapp') || hashParams.get('start_param');
    if (fromHash) return fromHash;
  }
  return '';
}

async function initWebApp() {
  if (window.Telegram && window.Telegram.WebApp) {
    window.Telegram.WebApp.ready();
    window.Telegram.WebApp.expand();
  }
  const tgInitData = (window.Telegram && window.Telegram.WebApp && window.Telegram.WebApp.initData) || '';
  const startParam = extractStartParam();
  try {
    const res = await fetch('${basePath}/api/webapp/auth', {
      method: 'POST',
      headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ initData: tgInitData, startParam: startParam })
    });
    if (res.ok) {
      const data = await res.json();
      userCsrf = data.csrf;
      const st = data.sessionToken;
      if (st) sToken = st;
      currentContext = { chatId: data.telegramChatId, topicId: data.telegramTopicId };
      const btnAdd = document.getElementById('btnAdd');
      if (btnAdd) {
        btnAdd.style.display = (data.telegramChatId != null && data.telegramChatId < 0) ? 'inline-block' : 'none';
      }
      const ctxText = document.getElementById('contextText');
      if (ctxText) ctxText.innerText = data.telegramChatId
        ? 'Chat #' + data.telegramChatId + (data.telegramTopicId ? ' / Topic #' + data.telegramTopicId : '')
        : 'All Accessible Installations';
      const destEl = document.getElementById('inDest');
      if (destEl) destEl.value = data.telegramChatId
        ? (data.telegramTopicId ? 'Topic #' + data.telegramTopicId + ' in Chat #' + data.telegramChatId : 'Chat #' + data.telegramChatId)
        : 'Select destination after launch from a group';
      await loadInstallations();
    } else {
      const ctxText = document.getElementById('contextText');
      if (ctxText) ctxText.innerText = 'Authentication required.';
    }
  } catch (e) {
    const ctxText = document.getElementById('contextText');
    if (ctxText) ctxText.innerText = 'Error connecting to server.';
  }
  setupWizardHandlers();
}

async function loadInstallations() {
  const el = document.getElementById('installationsList');
  try {
    const res = await fetch('${basePath}/api/webapp/installations', { headers: getAuthHeaders() });
    if (!res.ok) {
      if (el) el.innerHTML = '<div class="card"><p style="color:var(--danger)">Failed to load installations (HTTP ' + res.status + ').</p></div>';
      return;
    }
    const items = await res.json();
    if (!el) return;
    if (items.length === 0) {
      const emptyMsg = (currentContext.chatId != null && currentContext.chatId < 0)
        ? 'No installations found. Tap + Add to create one.'
        : 'No accessible installations found. Open this Web App inside a Telegram group or topic to set up notifications.';
      el.innerHTML = '<div class="card"><p>' + emptyMsg + '</p></div>';
      return;
    }
    el.innerHTML = '';
    items.forEach(function(item) {
      const card = document.createElement('div');
      card.className = 'card';
      const statusBadge = item.muted ? '<span class="badge badge-muted">Muted</span>' : '<span class="badge badge-active">Active</span>';
      card.innerHTML = '<div class="card-header"><span class="card-id">' + item.id.substring(0, 8) + '</span>' + statusBadge + '</div>' +
        '<p style="margin: 0.5rem 0 0.25rem; font-size: 0.85rem;">GitLab: ' + item.gitlabBaseUrl + (item.gitlabProjectId ? ' / Project #' + item.gitlabProjectId : '') + '</p>' +
        '<div class="actions">' +
        '<button class="btn-test">Test</button>' +
        '<button class="btn-mute">' + (item.muted ? 'Unmute' : 'Mute') + '</button>' +
        '<button class="btn-rotate">Rotate</button>' +
        '</div>';
      card.querySelector('.btn-test').addEventListener('click', function() { testDelivery(item.id); });
      card.querySelector('.btn-mute').addEventListener('click', function() { toggleMute(item.id, !item.muted); });
      card.querySelector('.btn-rotate').addEventListener('click', function() { rotateInstall(item.id); });
      el.appendChild(card);
    });
  } catch (e) {
    if (el) el.innerHTML = '<div class="card"><p style="color:var(--danger)">Error loading installations.</p></div>';
  }
}

function setupWizardHandlers() {
  const btnAdd = document.getElementById('btnAdd');
  if (btnAdd) btnAdd.addEventListener('click', function() { showScreen('wizard'); });
  const btnCancel = document.getElementById('btnCancel');
  if (btnCancel) btnCancel.addEventListener('click', function() { showScreen('dashboard'); });
  const btnCreate = document.getElementById('btnCreate');
  if (btnCreate) btnCreate.addEventListener('click', createInstallation);
  const btnRevDone = document.getElementById('btnRevDone');
  if (btnRevDone) btnRevDone.addEventListener('click', function() { showScreen('dashboard'); loadInstallations(); });
  const btnCopy = document.getElementById('btnCopy');
  if (btnCopy) btnCopy.addEventListener('click', function() {
    const val = document.getElementById('revSecret').innerText;
    if (navigator.clipboard) navigator.clipboard.writeText(val);
  });
}

function showScreen(name) {
  document.getElementById('installationsList').style.display = name === 'dashboard' ? '' : 'none';
  const wiz = document.getElementById('screen-wizard');
  const rev = document.getElementById('screen-reveal');
  if (wiz) wiz.style.display = name === 'wizard' ? '' : 'none';
  if (rev) rev.style.display = name === 'reveal' ? '' : 'none';
  const btnAdd = document.getElementById('btnAdd');
  if (btnAdd) {
    btnAdd.style.display = (name === 'dashboard' && currentContext.chatId != null && currentContext.chatId < 0) ? 'inline-block' : 'none';
  }
}

async function createInstallation() {
  document.getElementById('errUrl').innerText = '';
  document.getElementById('errPid').innerText = '';
  document.getElementById('wizErr').innerText = '';
  const url = document.getElementById('inUrl').value.trim();
  const pid = parseInt(document.getElementById('inPid').value.trim(), 10);
  let valid = true;
  if (!url.startsWith('https://')) { document.getElementById('errUrl').innerText = 'Must start with https://'; valid = false; }
  if (!pid || pid <= 0) { document.getElementById('errPid').innerText = 'Enter a valid project ID'; valid = false; }
  if (!valid) return;
  document.getElementById('btnCreate').disabled = true;
  document.getElementById('btnCreate').innerText = 'Creating...';
  try {
    const payload = { gitlabBaseUrl: url, gitlabProjectId: pid };
    if (currentContext.chatId != null && currentContext.chatId < 0) {
      payload.telegramChatId = currentContext.chatId;
      if (currentContext.topicId != null) {
        payload.telegramTopicId = currentContext.topicId;
      }
    }
    const res = await fetch('${basePath}/api/webapp/installations', {
      method: 'POST',
      headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(payload)
    });
    if (res.status === 201) {
      const data = await res.json();
      showReveal(data.credential, data.webhookUrl, 'Credential Issued');
    } else {
      const err = await res.json();
      document.getElementById('wizErr').innerText = err.error || 'Failed to create installation';
    }
  } catch (e) {
    document.getElementById('wizErr').innerText = 'Network error';
  } finally {
    document.getElementById('btnCreate').disabled = false;
    document.getElementById('btnCreate').innerText = 'Create Installation';
  }
}

async function rotateInstall(id) {
  if (!confirm('Rotate the credential? The old token stops working immediately.')) return;
  try {
    const res = await fetch('${basePath}/api/webapp/installations/' + id + '/rotate', {
      method: 'POST',
      headers: getAuthHeaders({ 'Content-Type': 'application/json' })
    });
    if (res.ok) {
      const data = await res.json();
      showReveal(data.credential, '', 'Credential Rotated');
    } else {
      const err = await res.json();
      alert(err.error || 'Rotation failed');
    }
  } catch (e) {
    alert('Network error');
  }
}

function showReveal(token, url, title) {
  document.getElementById('revTitle').innerText = title;
  document.getElementById('revSecret').innerText = token;
  document.getElementById('revUrl').value = url;
  showScreen('reveal');
}

async function toggleMute(id, muted) {
  await fetch('${basePath}/api/webapp/installations/' + id + '/mute', {
    method: 'POST',
    headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ muted: muted })
  });
  await loadInstallations();
}

async function testDelivery(id) {
  await fetch('${basePath}/api/webapp/installations/' + id + '/test', {
    method: 'POST',
    headers: getAuthHeaders({ 'Content-Type': 'application/json' })
  });
}

initWebApp();
""".trimIndent()
