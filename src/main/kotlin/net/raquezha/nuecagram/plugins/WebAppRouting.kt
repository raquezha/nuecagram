@file:Suppress("TooManyFunctions")

package net.raquezha.nuecagram.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
    val repoName: String,
    val chatName: String? = null,
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
private data class IdentityRequestPayload(
    val repoName: String,
    val chatName: String? = null,
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
    val repoName: String,
    val chatName: String? = null,
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

    get("$basePath/webapp/avatars/{file}") {
        val file = call.parameters["file"].orEmpty()
        val bytes = file.takeIf { it.matches(Regex("[a-zA-Z0-9.-]+\\.png")) }
            ?.let { Thread.currentThread().contextClassLoader.getResourceAsStream("webapp/avatars/$it") }
            ?.use { it.readBytes() }
        if (bytes == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.appendWebAppSecurityHeaders()
            call.respondBytes(bytes, ContentType.Image.PNG, HttpStatusCode.OK)
        }
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

    post("$basePath/api/webapp/installations/{id}/identity") {
        call.handleUpdateIdentity(installationRepository, telegramService, json)
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
        installationRepository.consumeLaunchNonce(param.removePrefix("nonce_"), verifiedUserId)
    }

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
    val scopedOnly = request.queryParameters["scope"] != "all"
    val items = if (isGroupContext && scopedOnly) {
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

private suspend fun ApplicationCall.handleUpdateIdentity(
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

    val payload = runCatching { json.decodeFromString<IdentityRequestPayload>(receiveText()) }.getOrNull()
    if (payload == null || payload.repoName.isBlank() || payload.repoName.trim() == "Unknown Repository") {
        respond(
            HttpStatusCode.BadRequest,
            ErrorResponsePayload("repoName must be non-blank and not use the legacy fallback value"),
        )
        return
    }

    installationRepository.updateIdentity(item.id, payload.repoName, payload.chatName)
    installationRepository.writeAuditEvent(
        installationId = item.id,
        actorType = "webapp_session",
        actorId = session.telegramUserId.toString(),
        action = "webapp_identity_update",
    )

    appendWebAppSecurityHeaders()
    respond(HttpStatusCode.OK, installationRepository.installationAdminContext(item.id)!!.toResponsePayload())
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
            text = "Nuecagram notification test for ${item.repositoryButtonLabel()}.",
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
        parsed.repoName.isBlank() || parsed.repoName.trim() == "Unknown Repository" ->
            respondError(HttpStatusCode.BadRequest, "repoName must be non-blank and not use the legacy fallback value")
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
        repoName = parsed.repoName,
        chatName = parsed.chatName,
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
    repoName = repoName,
    chatName = chatName,
    gitlabBaseUrl = gitlabBaseUrl,
    gitlabProjectId = gitlabProjectId,
    telegramChatId = telegramChatId,
    telegramTopicId = telegramTopicId,
    muted = muted,
)

private fun net.raquezha.nuecagram.db.InstallationRecord.toAdminContext(muted: Boolean) =
    InstallationAdminContext(
        id = id,
        repoName = repoName,
        chatName = chatName,
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
    <script src="https://telegram.org/js/telegram-web-app.js"></script>
    <style>
      :root {
        --bg-color: var(--tg-theme-bg-color, #f2f3f5);
        --card-bg: var(--tg-theme-secondary-bg-color, #ffffff);
        --text-main: var(--tg-theme-text-color, #111827);
        --hint: var(--tg-theme-hint-color, #6b7280);
        --button: var(--tg-theme-button-color, #229ed9);
        --button-text: var(--tg-theme-button-text-color, #ffffff);
        --border: #e5e7eb;
        --success: #12805c;
        --danger: #be123c;
      }
      * { box-sizing: border-box; }
      body { margin: 0; background: var(--bg-color); color: var(--text-main); font: 15px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
      .container { max-width: ${CONTAINER_MAX_WIDTH_PX}px; margin: 0 auto; padding: 18px 14px 28px; }
      h1 { margin: 0 0 6px; font-size: 24px; line-height: 1.1; letter-spacing: -0.03em; }
      h2 { margin: 18px 4px 8px; font-size: 12px; color: var(--hint); text-transform: uppercase; letter-spacing: .06em; }
      p { margin: 0 0 14px; color: var(--hint); line-height: 1.45; }
      button { border: 0; border-radius: 12px; background: var(--card-bg); color: var(--text-main); padding: 11px 14px; font: 700 14px inherit; box-shadow: inset 0 0 0 1px var(--border); cursor: pointer; }
      button.primary { background: var(--button); color: var(--button-text); box-shadow: none; }
      button.danger { color: var(--danger); }
      button.link { background: transparent; color: var(--button); box-shadow: none; padding: 8px 2px; }
      .top-actions { display: flex; gap: 10px; margin: 16px 0 18px; flex-wrap: wrap; }
      .card { width: 100%; display: flex; gap: 14px; align-items: center; text-align: left; margin: 0 0 10px; padding: 14px; border-radius: 18px; background: var(--card-bg); box-shadow: inset 0 0 0 1px var(--border); }
      .card.muted { opacity: .72; filter: grayscale(.25); }
      .avatar { flex: 0 0 56px; width: 56px; height: 56px; border-radius: 50%; object-fit: cover; background: #eef0f3; }
      .grow { min-width: 0; flex: 1; }
      .row { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-width: 0; padding: 13px 14px; border-top: 1px solid var(--border); }
      .row:first-child { border-top: 0; }
      .title { font-weight: 800; font-size: 16px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .sub { margin-top: 3px; color: var(--hint); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .meta { margin-top: 7px; color: var(--hint); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .badge { margin-left: auto; border-radius: 999px; padding: 4px 7px; font-size: 11px; font-weight: 800; }
      .badge-active { background: #e8f7ef; color: var(--success); }
      .badge-muted { background: #fff1f2; color: var(--danger); }
      .chev { color: #9ca3af; font-size: 22px; }
      .panel { display: none; }
      .panel.active { display: block; }
      .box, .group { border-radius: 16px; background: var(--card-bg); box-shadow: inset 0 0 0 1px var(--border); overflow: hidden; }
      .box { padding: 18px; }
      .section { margin: 18px 0; }
      .section > button { width: 100%; }
      .section-title { margin: 18px 4px 8px; font-size: 12px; font-weight: 800; color: var(--hint); text-transform: uppercase; letter-spacing: .06em; }
      .field { padding: 13px 14px; border-top: 1px solid var(--border); margin: 0; }
      .field:first-child { border-top: 0; }
      label { display: block; margin-bottom: 7px; font-weight: 800; }
      input, .codebox { width: 100%; border: 1px solid var(--border); border-radius: 12px; padding: 12px; background: #fafafa; color: var(--text-main); font: 15px inherit; }
      .codebox { min-height: 44px; overflow-wrap: anywhere; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
      input[readonly] { color: var(--hint); }
      .split { display: flex; justify-content: space-between; gap: 10px; align-items: center; margin-top: 16px; }
      .helper { min-height: 18px; margin-top: 8px; font-size: 12px; color: var(--hint); }
      .ok { color: var(--success); }
      .err { color: var(--danger); }
      .secret { display: block; margin-top: 8px; }
      .secret.hidden { filter: blur(6px); user-select: none; }
      .footer { margin-top: 22px; border-top: 1px solid var(--border); padding-top: 14px; font-size: 12px; }
      a { color: var(--button); }
    </style>
  </head>
  <body>
    <div class="container">
      <section id="screen-list" class="panel active">
        <h1 id="listTitle">Repositories</h1>
        <p id="listSubtitle">Loading repositories...</p>
        <div class="top-actions">
          <button id="btnAdd" class="primary">+ Add</button>
          <button id="btnAll" style="display:none;">View all repositories</button>
        </div>
        <div id="installationsList"><div class="box">Loading repositories...</div></div>
      </section>

      <section id="screen-detail" class="panel">
        <button class="link" data-screen="list">‹ Back to repositories</button>
        <div id="detailBody"></div>
        <p class="footer"><a href="https://www.vecteezy.com/free-vector/animal" target="_blank" rel="noopener">Vectors by Vecteezy</a></p>
      </section>

      <section id="screen-edit" class="panel">
        <button class="link" data-screen="detail">‹ Back to details</button>
        <h1>Edit names</h1>
        <div class="field"><label>Repository name</label><input id="editRepoName" autocomplete="off"></div>
        <div class="field"><label>Notification label</label><input id="editChatName" autocomplete="off"></div>
        <div id="editErr" class="helper err"></div>
        <div class="split"><button data-screen="detail">Cancel</button><button id="btnSaveIdentity" class="primary">Save</button></div>
      </section>

      <section id="screen-add-info" class="panel">
        <button class="link" data-screen="list">‹ Back to repositories</button>
        <h1>Add repository</h1>
        <p>Repositories must be added from the Telegram group or topic that will receive notifications.</p>
        <div class="box"><p>How to add one:</p><p>1. Go to the target Telegram group or topic.<br>2. Open the group's Nuecagram menu and tap Management.<br>3. Tap + Add.</p><p>You can also run /setup in that group or topic.</p><p>No chat ID typing needed -- Nuecagram fills the destination automatically.</p></div>
        <div class="top-actions"><button class="primary" data-screen="list">Got it</button></div>
      </section>

      <section id="screen-wizard" class="panel">
        <button class="link" data-screen="list">‹ Back to repositories</button>
        <h1>Add repository</h1>
        <p>Notifications will be sent to:<br><strong id="createDestinationName"></strong><br><span id="createDestinationMeta"></span></p>
        <div class="field"><label>GitLab base URL</label><input id="inUrl" value="https://gitlab.com"></div>
        <div class="field"><label>GitLab project ID</label><input id="inPid" type="number" placeholder="123456"></div>
        <div class="field"><label>Repository name</label><input id="inRepoName" placeholder="nuecagram"></div>
        <div class="field"><label>Notification label</label><input id="inChatName" placeholder="Android Team / Notifications"></div>
        <div id="wizErr" class="helper err"></div>
        <div class="split"><button data-screen="list">Cancel</button><button id="btnCreate" class="primary">Create</button></div>
      </section>

      <section id="screen-reveal" class="panel">
        <h1 id="revTitle">Repository created</h1>
        <p>Copy this webhook token now. It will only be shown once.</p>
        <div class="box"><label>Webhook secret</label><div><span id="revSecret" class="secret hidden"></span> <button id="btnReveal">Reveal</button></div><div class="top-actions"><button id="btnCopy" class="primary">Copy token</button></div><div id="copyHelp" class="helper"></div></div>
        <div class="field"><label>Webhook URL</label><div id="revUrl" class="codebox"></div></div>
        <div class="top-actions"><button id="btnRevDone" class="primary">Done</button></div>
      </section>
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
let items = [];
let currentItem = null;
let listMode = 'scoped';
const avatars = ['01-giraffe.png','02-elephant.png','03-lion.png','04-koala.png','05-bear.png','06-tiger.png'];

function getAuthHeaders(extra) {
  const h = Object.assign({}, extra || {});
  if (userCsrf) h['X-CSRF-Token'] = userCsrf;
  if (sToken) h['X-Session-' + 'Token'] = sToken;
  return h;
}

function copyValue(val, el) {
  if (navigator.clipboard) navigator.clipboard.writeText(val);
  const old = el.innerText;
  el.innerText = '✓ Copied';
  setTimeout(function() { el.innerText = old; }, 1800);
}
  return String(value == null ? '' : value).replace(/[&<>"']/g, function(c) {
    return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
  });
}

function destinationLabel(item) {
  if (item.chatName && item.chatName.trim()) return item.chatName;
  return 'Chat #' + item.telegramChatId + (item.telegramTopicId ? ' / Topic ' + item.telegramTopicId : '');
}

function destinationMeta(item) {
  return 'Chat ' + item.telegramChatId + (item.telegramTopicId ? ' · Topic ' + item.telegramTopicId : '');
}

function repoMeta(item) {
  const host = item.gitlabBaseUrl.replace(/^https?:\/\//, '');
  return host + (item.gitlabProjectId ? '/#' + item.gitlabProjectId : '') + ' · id ' + item.id.substring(0, 8);
}

function avatarFor(item, index) {
  let n = 0;
  for (let i = 0; i < item.id.length; i++) n = (n + item.id.charCodeAt(i)) % avatars.length;
  return avatars[(n + index) % avatars.length];
}

function showScreen(name) {
  document.querySelectorAll('.panel').forEach(function(el) { el.classList.remove('active'); });
  document.getElementById('screen-' + name).classList.add('active');
}

function extractStartParam() {
  const tg = window.Telegram && window.Telegram.WebApp;
  if (tg && tg.initDataUnsafe && tg.initDataUnsafe.start_param) return tg.initDataUnsafe.start_param;
  const q = new URLSearchParams(window.location.search);
  return q.get('startapp') || q.get('tgWebAppStartParam') || q.get('start_param') || '';
}

async function initWebApp() {
  const tg = window.Telegram && window.Telegram.WebApp;
  if (tg) { tg.ready(); tg.expand(); }
  try {
    const res = await fetch('${basePath}/api/webapp/auth', {
      method: 'POST',
      headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ initData: (tg && tg.initData) || '', startParam: extractStartParam() })
    });
    if (!res.ok) { renderError(res.status === 403 ? 'Admin access required' : 'Authentication required', 'Only Telegram group admins can manage repositories here.'); return; }
    const data = await res.json();
    userCsrf = data.csrf;
    if (data.sessionToken) sToken = data.sessionToken;
    currentContext = { chatId: data.telegramChatId, topicId: data.telegramTopicId };
    listMode = (currentContext.chatId != null && currentContext.chatId < 0) ? 'scoped' : 'all';
    setupHandlers();
    await loadInstallations();
  } catch (e) {
    renderError('Nuecagram needs admin access', 'Give the bot admin access in this group to manage repositories.');
  }
}

async function loadInstallations() {
  showScreen('list');
  renderHeader();
  const el = document.getElementById('installationsList');
  el.innerHTML = '<div class="box">Loading repositories...</div>';
  try {
    const qs = listMode === 'all' ? '?scope=all' : '';
    const res = await fetch('${basePath}/api/webapp/installations' + qs, { headers: getAuthHeaders() });
    if (!res.ok) { renderError(res.status === 403 ? 'Admin access required' : 'Nuecagram needs admin access', res.status === 403 ? 'Only Telegram group admins can manage repositories here.' : 'Give the bot admin access in this group to manage repositories.'); return; }
    items = await res.json();
    renderList();
  } catch (e) {
    renderError('Nuecagram needs admin access', 'Give the bot admin access in this group to manage repositories.');
  }
}

function renderHeader() {
  const scoped = listMode !== 'all' && currentContext.chatId != null && currentContext.chatId < 0;
  document.getElementById('listTitle').innerText = scoped ? 'Repositories' : 'All repositories';
  document.getElementById('listSubtitle').innerText = scoped ? destinationMeta({telegramChatId: currentContext.chatId, telegramTopicId: currentContext.topicId}) + '\nNotifications go to this group/topic.' : 'Repositories you can manage across groups/topics.';
  document.getElementById('btnAll').style.display = scoped ? '' : 'none';
}

function renderList() {
  const el = document.getElementById('installationsList');
  if (!items.length) {
    const scoped = listMode !== 'all' && currentContext.chatId != null && currentContext.chatId < 0;
    el.innerHTML = scoped
      ? '<div class="box"><h1>No repositories here yet.</h1><p>Add a GitLab project to start notifications.</p><button class="primary" id="btnEmptyAdd">+ Add repository</button></div>'
      : '<div class="box"><h1>No repositories found.</h1><p>Open Nuecagram from a Telegram group/topic or run /setup there.</p></div>';
    const add = document.getElementById('btnEmptyAdd');
    if (add) add.addEventListener('click', openAdd);
    return;
  }
  el.innerHTML = '';
  items.forEach(function(item, index) {
    const card = document.createElement('button');
    card.className = 'card' + (item.muted ? ' muted' : '');
    card.innerHTML = '<img class="avatar" alt="" aria-hidden="true" src="${basePath}/webapp/avatars/' + avatarFor(item, index) + '">' +
      '<div class="grow"><div class="row"><div class="title">' + escapeHtml(item.repoName) + '</div><span class="badge ' + (item.muted ? 'badge-muted">MUTED' : 'badge-active">ACTIVE') + '</span><span class="chev">›</span></div>' +
      '<div class="sub">' + escapeHtml(destinationLabel(item)) + '</div><div class="meta">' + escapeHtml(repoMeta(item)) + '</div></div>';
    card.addEventListener('click', function() { openDetail(item.id); });
    el.appendChild(card);
  });
}

function renderError(title, body) {
  document.getElementById('listTitle').innerText = title;
  document.getElementById('listSubtitle').innerText = body;
  document.getElementById('installationsList').innerHTML = '';
}

async function openDetail(id) {
  const res = await fetch('${basePath}/api/webapp/installations/' + id, { headers: getAuthHeaders() });
  if (!res.ok) return;
  currentItem = await res.json();
  renderDetail();
  showScreen('detail');
}

function renderDetail() {
  const item = currentItem;
  document.getElementById('detailBody').innerHTML = '<div class="card"><img class="avatar" alt="" aria-hidden="true" src="${basePath}/webapp/avatars/' + avatarFor(item, 0) + '"><div class="grow"><div class="title">' + escapeHtml(item.repoName) + '</div><div class="sub">' + escapeHtml(destinationLabel(item)) + '</div></div><span class="badge ' + (item.muted ? 'badge-muted">MUTED' : 'badge-active">ACTIVE') + '</span></div>' +
    '<div class="section"><div class="section-title">Repository</div><div class="group"><div class="row" style="cursor:pointer" onclick="copyValue(\'' + escapeHtml(item.gitlabBaseUrl + (item.gitlabProjectId ? '/#' + item.gitlabProjectId : '')) + '\', this.querySelector(\'.meta\'))"><strong>GitLab</strong><span class="meta">' + escapeHtml(item.gitlabBaseUrl + (item.gitlabProjectId ? '/#' + item.gitlabProjectId : '')) + '</span></div><div class="row" style="cursor:pointer" onclick="copyValue(\'' + escapeHtml(item.id) + '\', this.querySelector(\'.meta\'))"><strong>Installation ID</strong><span class="meta">' + escapeHtml(item.id) + '</span></div></div></div>' +
    '<div class="section"><div class="section-title">Destination</div><div class="group"><div class="row"><strong>Telegram</strong><span class="meta">' + escapeHtml(destinationMeta(item)) + '</span></div></div></div>' +
    '<div class="section"><div class="section-title">Actions</div><div class="top-actions"><button id="btnTest">Test notification</button><button id="btnMute">' + (item.muted ? 'Unmute notifications' : 'Mute notifications') + '</button></div><div id="actionHelp" class="helper"></div></div>' +
    '<div class="section"><div class="section-title">Settings</div><button id="btnEdit">Edit names ›</button></div><div class="section"><div class="section-title">Danger zone</div><button id="btnRotate" class="danger">Rotate webhook token ›</button></div>';
  document.getElementById('btnTest').addEventListener('click', testDelivery);
  document.getElementById('btnMute').addEventListener('click', toggleMute);
  document.getElementById('btnEdit').addEventListener('click', openEdit);
  document.getElementById('btnRotate').addEventListener('click', rotateInstall);
}

function setAction(text, ok) {
  const el = document.getElementById('actionHelp');
  el.className = 'helper ' + (ok ? 'ok' : 'err');
  el.innerText = text;
  setTimeout(function() { el.innerText = ''; }, 2500);
}

function openEdit() {
  document.getElementById('editRepoName').value = currentItem.repoName || '';
  document.getElementById('editChatName').value = currentItem.chatName || '';
  document.getElementById('editErr').innerText = '';
  showScreen('edit');
}

async function saveIdentity() {
  const repoName = document.getElementById('editRepoName').value.trim();
  const chatName = document.getElementById('editChatName').value.trim();
  if (!repoName) { document.getElementById('editErr').innerText = 'Repository name required.'; return; }
  const res = await fetch('${basePath}/api/webapp/installations/' + currentItem.id + '/identity', {
    method: 'POST', headers: getAuthHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ repoName: repoName, chatName: chatName })
  });
  if (!res.ok) { document.getElementById('editErr').innerText = 'Could not save names.'; return; }
  currentItem = await res.json();
  items = items.map(function(x) { return x.id === currentItem.id ? currentItem : x; });
  renderDetail();
  showScreen('detail');
}

function openAdd() {
  if (!(currentContext.chatId != null && currentContext.chatId < 0) || listMode === 'all') { showScreen('add-info'); return; }
  document.getElementById('createDestinationName').innerText = destinationMeta({telegramChatId: currentContext.chatId, telegramTopicId: currentContext.topicId});
  document.getElementById('createDestinationMeta').innerText = destinationMeta({telegramChatId: currentContext.chatId, telegramTopicId: currentContext.topicId});
  document.getElementById('wizErr').innerText = '';
  showScreen('wizard');
}

async function createInstallation() {
  const url = document.getElementById('inUrl').value.trim();
  const pid = parseInt(document.getElementById('inPid').value.trim(), 10);
  const repoName = document.getElementById('inRepoName').value.trim();
  const chatName = document.getElementById('inChatName').value.trim();
  if (!url.startsWith('https://') || !pid || !repoName) { document.getElementById('wizErr').innerText = 'Could not create repository. Check the GitLab project ID.'; return; }
  const res = await fetch('${basePath}/api/webapp/installations', {
    method: 'POST', headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ repoName: repoName, chatName: chatName, gitlabBaseUrl: url, gitlabProjectId: pid })
  });
  if (res.status !== 201) { document.getElementById('wizErr').innerText = 'Could not create repository. Check the GitLab project ID.'; return; }
  const data = await res.json();
  showReveal(data.credential, data.webhookUrl, 'Repository created');
}

function confirmRotate(callback) {
  const tg = window.Telegram && window.Telegram.WebApp;
  if (tg && tg.showConfirm) tg.showConfirm('Rotate webhook token?', callback); else callback(confirm('Rotate webhook token?'));
}

function rotateInstall() {
  confirmRotate(async function(ok) {
    if (!ok) return;
    const res = await fetch('${basePath}/api/webapp/installations/' + currentItem.id + '/rotate', { method: 'POST', headers: getAuthHeaders({ 'Content-Type': 'application/json' }) });
    if (!res.ok) { setAction('Could not rotate token.', false); return; }
    const data = await res.json();
    showReveal(data.credential, '', 'Webhook token rotated');
  });
}

function showReveal(token, url, title) {
  document.getElementById('revTitle').innerText = title;
  const secret = document.getElementById('revSecret');
  secret.innerText = token;
  secret.classList.add('hidden');
  document.getElementById('btnReveal').innerText = 'Reveal';
  document.getElementById('revUrl').innerText = url || '';
  document.getElementById('copyHelp').innerText = '';
  showScreen('reveal');
}

async function toggleMute() {
  const res = await fetch('${basePath}/api/webapp/installations/' + currentItem.id + '/mute', { method: 'POST', headers: getAuthHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ muted: !currentItem.muted }) });
  if (!res.ok) { setAction('Could not update notifications.', false); return; }
  currentItem.muted = !currentItem.muted;
  items = items.map(function(x) { return x.id === currentItem.id ? currentItem : x; });
  renderDetail();
  setAction('Notification setting updated.', true);
}

async function testDelivery() {
  const res = await fetch('${basePath}/api/webapp/installations/' + currentItem.id + '/test', { method: 'POST', headers: getAuthHeaders({ 'Content-Type': 'application/json' }) });
  setAction(res.ok ? '✓ Test notification sent.' : 'Could not send test notification.', res.ok);
}

function setupHandlers() {
  document.querySelectorAll('[data-screen]').forEach(function(b) { b.addEventListener('click', function() { showScreen(b.getAttribute('data-screen')); }); });
  document.getElementById('btnAdd').addEventListener('click', openAdd);
  document.getElementById('btnAll').addEventListener('click', function() { listMode = 'all'; loadInstallations(); });
  document.getElementById('btnSaveIdentity').addEventListener('click', saveIdentity);
  document.getElementById('btnCreate').addEventListener('click', createInstallation);
  document.getElementById('btnRevDone').addEventListener('click', loadInstallations);
  document.getElementById('btnReveal').addEventListener('click', function() {
    const secret = document.getElementById('revSecret');
    const hidden = secret.classList.toggle('hidden');
    document.getElementById('btnReveal').innerText = hidden ? 'Reveal' : 'Hide';
  });
  let copyTimer = null;
  document.getElementById('btnCopy').addEventListener('click', function() {
    const val = document.getElementById('revSecret').innerText;
    if (navigator.clipboard) navigator.clipboard.writeText(val);
    const help = document.getElementById('copyHelp');
    help.innerText = '✓ Copied';
    if (copyTimer) clearTimeout(copyTimer);
    copyTimer = setTimeout(function() { help.innerText = ''; }, 2500);
  });
}

initWebApp();
""".trimIndent()
