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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.db.AuditIdentityDelta
import net.raquezha.nuecagram.db.AuditMetadataPatch
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
        call.handleWebAppShell(installationRepository, basePath)
    }

    get("$basePath/webapp/app.js") {
        call.appendWebAppSecurityHeaders()
        call.respondText(
            webAppJsScript(basePath),
            ContentType.Text.JavaScript,
            HttpStatusCode.OK,
        )
    }

    get("$basePath/webapp/avatars/{file}") { call.handleWebAppAvatar() }

    get("$basePath/webapp/loading.json") { call.handleWebAppLoadingJson() }

    get("$basePath/webapp/lottie.min.js") { call.handleWebAppLottieJs() }

    post("$basePath/api/webapp/auth") {
        call.handleWebAppAuth(installationRepository, config, json, basePath)
    }

    get("$basePath/api/webapp/installations") {
        call.handleGetInstallations(installationRepository, telegramService)
    }

    get("$basePath/api/webapp/installations/{id}") {
        call.handleGetInstallationDetail(installationRepository, telegramService)
    }

    get("$basePath/api/webapp/destinations") {
        call.handleGetDestinations(installationRepository, telegramService)
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

    delete("$basePath/api/webapp/installations/{id}") {
        call.handleDeleteInstallation(installationRepository, telegramService)
    }
}

private suspend fun ApplicationCall.handleWebAppShell(
    installationRepository: InstallationRepository,
    basePath: String,
) {
    appendWebAppSecurityHeaders()
    respondText(
        webAppShellHtml(basePath),
        ContentType.Text.Html,
        HttpStatusCode.OK,
    )
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

    val sanitizedBotToken = config.botApi.trim().removePrefix("bot")
    val verified = TelegramWebAppAuth.verifyInitData(payload.initData, sanitizedBotToken)
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

    extractSessionToken()
        ?.let { installationRepository.verifyWebAppSession(it) }
        ?.takeIf { it.telegramUserId == verified.user.id }
        ?.let { installationRepository.deleteWebAppSession(it.sessionId) }

    val sessionExpiry = Instant.now().plus(SESSION_TTL_HOURS, ChronoUnit.HOURS)
    val session = installationRepository.issueWebAppSession(
        telegramUserId = verified.user.id,
        telegramChatId = resolvedChatId,
        telegramTopicId = resolvedTopicId,
        username = verified.user.username,
        firstName = verified.user.firstName,
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
        suspend fun isActiveAdmin(chatId: Long): Boolean {
            val cached = adminChatIds[chatId]
            if (cached != null) return cached
            val status = runCatching {
                telegramService.chatMemberStatus(chatId, session.telegramUserId)
            }.getOrNull()
            val isAdmin = isTelegramAdmin(status)
            adminChatIds[chatId] = isAdmin
            return isAdmin
        }
        val recorded = installationRepository.installationsForAdmin(session.telegramUserId)
            .filter { inst -> isActiveAdmin(inst.telegramChatId) }
        if (recorded.isNotEmpty()) {
            recorded
        } else {
            installationRepository.listInstallationsForContext(null, null).filter { inst ->
                isActiveAdmin(inst.telegramChatId)
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

private suspend fun ApplicationCall.handleGetDestinations(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
) {
    val session = authenticateWebAppSession(installationRepository) ?: return
    val userId = session.telegramUserId
    val botUserId = runCatching { telegramService.getMe()?.id }.getOrNull()
    val adminChatIds = mutableMapOf<Long, Boolean>()
    suspend fun isActiveAdmin(chatId: Long): Boolean {
        val cached = adminChatIds[chatId]
        if (cached != null) return cached
        val userStatus = runCatching {
            telegramService.chatMemberStatus(chatId, userId)
        }.getOrNull()
        val userIsAdmin = isTelegramAdmin(userStatus)
        if (!userIsAdmin) {
            adminChatIds[chatId] = false
            return false
        }
        val botIsAdmin = if (botUserId != null) {
            val botStatus = runCatching {
                telegramService.chatMemberStatus(chatId, botUserId)
            }.getOrNull()
            isTelegramAdmin(botStatus)
        } else {
            true
        }
        adminChatIds[chatId] = botIsAdmin
        return botIsAdmin
    }

    val installed = installationRepository.installationsForAdmin(userId)
        .filter { inst -> isActiveAdmin(inst.telegramChatId) }
        .map { inst ->
            val topicSuffix = inst.telegramTopicId?.let { " / Topic $it" }.orEmpty()
            val label = inst.chatName ?: "Chat #${inst.telegramChatId}$topicSuffix"
            DestinationPayload(
                id = "${inst.telegramChatId}:${inst.telegramTopicId ?: 0}",
                name = label,
                telegramChatId = inst.telegramChatId,
                telegramTopicId = inst.telegramTopicId,
            )
        }

    val known = installationRepository.knownTelegramDestinations().mapNotNull { dest ->
        val topicSuffix = dest.telegramTopicId?.let { " / Topic $it" }.orEmpty()
        val baseTitle = dest.chatTitle?.takeIf(String::isNotBlank) ?: "Chat #${dest.telegramChatId}"
        val label = "$baseTitle$topicSuffix"
        if (isActiveAdmin(dest.telegramChatId)) {
            DestinationPayload(
                id = dest.id,
                name = label,
                telegramChatId = dest.telegramChatId,
                telegramTopicId = dest.telegramTopicId,
            )
        } else {
            null
        }
    }

    val combined = (installed + known).distinctBy { it.id }
    appendWebAppSecurityHeaders()
    respond(HttpStatusCode.OK, combined)
}

@Serializable
private data class DestinationPayload(
    val id: String,
    val name: String,
    val telegramChatId: Long,
    val telegramTopicId: Long? = null,
)

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
        metadataPatch = AuditMetadataPatch(
            actorUsername = session.username,
            actorFirstName = session.firstName,
        ),
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

    val normalizedRepoName = payload.repoName.trim()
    val normalizedChatName = payload.chatName?.trim()?.takeIf(String::isNotBlank)
    installationRepository.updateIdentity(item.id, normalizedRepoName, normalizedChatName)
    installationRepository.writeAuditEvent(
        installationId = item.id,
        actorType = "webapp_session",
        actorId = session.telegramUserId.toString(),
        action = "webapp_identity_update",
        metadataPatch = AuditMetadataPatch(
            actorUsername = session.username,
            actorFirstName = session.firstName,
            identityDelta = AuditIdentityDelta(
                oldRepoName = item.repoName,
                newRepoName = normalizedRepoName,
                oldNickname = item.chatName,
                newNickname = normalizedChatName,
            ),
        ),
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
        metadataPatch = AuditMetadataPatch(
            actorUsername = session.username,
            actorFirstName = session.firstName,
        ),
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
    targetChatId: Long,
    telegramService: TelegramService,
): Boolean {
    if (session.telegramChatId != null && session.telegramChatId < 0) return true
    val userStatus = runCatching { telegramService.chatMemberStatus(targetChatId, session.telegramUserId) }.getOrNull()
    if (!isTelegramAdmin(userStatus)) return false
    val botUserId = runCatching { telegramService.getMe()?.id }.getOrNull() ?: return true
    val botStatus = runCatching { telegramService.chatMemberStatus(targetChatId, botUserId) }.getOrNull()
    return isTelegramAdmin(botStatus)
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
            target = target,
            telegramUserId = session.telegramUserId,
            actorMetadata = AuditMetadataPatch(
                actorUsername = session.username,
                actorFirstName = session.firstName,
            ),
        )
    }
}

private suspend fun createAndRespond(
    installationRepository: InstallationRepository,
    config: ConfigWithSecrets,
    basePath: String,
    parsed: CreateInstallationRequestPayload,
    target: TargetDestination,
    telegramUserId: Long,
    actorMetadata: AuditMetadataPatch,
): WebAppResponseSpec {
    val installation = installationRepository.createInstallation(
        repoName = parsed.repoName,
        chatName = parsed.chatName,
        gitlabBaseUrl = parsed.gitlabBaseUrl.trimEnd('/'),
        gitlabProjectId = parsed.gitlabProjectId,
        telegramChatId = target.chatId!!,
        telegramTopicId = target.topicId,
    )
    installationRepository.recordInstallationAdmin(installation.id, telegramUserId)
    val tok = installationRepository.issueWebhookSecret(installation.id)
    installationRepository.writeAuditEvent(
        installationId = installation.id,
        actorType = "webapp_session",
        actorId = telegramUserId.toString(),
        action = "webapp_setup",
        metadataPatch = actorMetadata,
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

private suspend fun ApplicationCall.handleDeleteInstallation(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
) {
    val session = authenticateWebAppSession(installationRepository) ?: return
    if (!verifyAdminStatus(session, telegramService)) return
    if (!verifyCsrfHeader(installationRepository, session)) return

    val dmId = resolveDmId(session, installationRepository)
    if (dmId == null) {
        respond(HttpStatusCode.Forbidden, ErrorResponsePayload("DM bootstrap required"))
        return
    }

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

    val deleted = installationRepository.softDeleteInstallation(item.id)
    if (!deleted) {
        respond(HttpStatusCode.NotFound, ErrorResponsePayload("Installation not found"))
        return
    }

    installationRepository.writeAuditEvent(
        installationId = item.id,
        actorType = "webapp_session",
        actorId = session.telegramUserId.toString(),
        action = "webapp_delete",
        metadataPatch = AuditMetadataPatch(
            actorUsername = session.username,
            actorFirstName = session.firstName,
        ),
    )

    appendWebAppSecurityHeaders()
    respond(HttpStatusCode.NoContent)
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
                metadataPatch = AuditMetadataPatch(
                    actorUsername = session.username,
                    actorFirstName = session.firstName,
                ),
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

private suspend fun ApplicationCall.handleWebAppAvatar() {
    val file = parameters["file"].orEmpty()
    val bytes = file.takeIf { it.matches(Regex("[a-zA-Z0-9.-]+\\.png")) }
        ?.let { Thread.currentThread().contextClassLoader.getResourceAsStream("webapp/avatars/$it") }
        ?.use { it.readBytes() }
    if (bytes == null) {
        respond(HttpStatusCode.NotFound)
    } else {
        appendWebAppSecurityHeaders()
        respondBytes(bytes, ContentType.Image.PNG, HttpStatusCode.OK)
    }
}

private suspend fun ApplicationCall.handleWebAppLoadingJson() {
    val bytes = Thread.currentThread().contextClassLoader
        .getResourceAsStream("webapp/loading.json")?.use { it.readBytes() }
    if (bytes == null) {
        respond(HttpStatusCode.NotFound)
    } else {
        appendWebAppSecurityHeaders()
        respondBytes(bytes, ContentType.Application.Json, HttpStatusCode.OK)
    }
}

private suspend fun ApplicationCall.handleWebAppLottieJs() {
    val bytes = Thread.currentThread().contextClassLoader
        .getResourceAsStream("webapp/lottie.min.js")?.use { it.readBytes() }
    if (bytes == null) {
        respond(HttpStatusCode.NotFound)
    } else {
        appendWebAppSecurityHeaders()
        respondBytes(bytes, ContentType.Text.JavaScript, HttpStatusCode.OK)
    }
}

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
    <script src="${basePath}/webapp/lottie.min.js"></script>
    <style>
      :root {
        --bg-color: var(--tg-theme-bg-color, #f4f5f8);
        --card-bg: var(--tg-theme-secondary-bg-color, #ffffff);
        --text-main: var(--tg-theme-text-color, #0f172a);
        --hint: var(--tg-theme-hint-color, #64748b);
        --button: var(--tg-theme-button-color, #0284c7);
        --button-text: var(--tg-theme-button-text-color, #ffffff);
        --border: #e2e8f0;
        --input-bg: #f8fafc;
        --success: #16a34a;
        --danger: #dc2626;
      }
      @media (prefers-color-scheme: dark) {
        :root {
          --bg-color: var(--tg-theme-bg-color, #0f172a);
          --card-bg: var(--tg-theme-secondary-bg-color, #1e293b);
          --text-main: var(--tg-theme-text-color, #f8fafc);
          --hint: var(--tg-theme-hint-color, #94a3b8);
          --border: #334155;
          --input-bg: #141c2e;
        }
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
      .card .row { padding: 0; border-top: 0; margin-bottom: 3px; }
      .row:first-child { border-top: 0; }
      .title { font-weight: 800; font-size: 16px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .sub { margin-top: 3px; color: var(--hint); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .meta { margin-top: 7px; color: var(--hint); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .badge { margin-left: auto; border-radius: 999px; padding: 4px 7px; font-size: 11px; font-weight: 800; }
      .helper.ok { color: var(--success); font-weight: 700; }
      .badge-active { background: #dcfce7; color: var(--success); }
      .badge-muted { background: #fee2e2; color: var(--danger); }
      .chev { color: #0284c7; font-size: 22px; font-weight: 800; }
      .panel { display: none; }
      .panel.active { display: block; }
      #screen-loading { display: none; flex-direction: column; align-items: center; justify-content: center; min-height: 80vh; gap: 12px; }
      #screen-loading.active { display: flex; }
      .loading-animation { width: 220px; height: 165px; display: flex; align-items: center; justify-content: center; }
      .loading-animation svg { width: 100% !important; height: 100% !important; display: block; }
      .spinner-label { color: var(--hint); font-size: 14px; text-align: center; max-width: 260px; line-height: 1.4; }
      .box, .group { border-radius: 16px; background: var(--card-bg); box-shadow: inset 0 0 0 1px var(--border); overflow: hidden; }
      .box { padding: 18px; }
      .section { margin: 18px 0; }
      .section > button { width: 100%; }
      .section-title { margin: 18px 4px 8px; font-size: 12px; font-weight: 800; color: var(--hint); text-transform: uppercase; letter-spacing: .06em; }
      .field { padding: 13px 14px; border-top: 1px solid var(--border); margin: 0; }
      .field:first-child { border-top: 0; }
      label { display: block; margin-bottom: 7px; font-weight: 800; }
      #createDestinationMeta { font-size: 12px; font-style: italic; opacity: 0.85; display: inline-block; margin-top: 4px; }
      input, select, .codebox { width: 100%; border: 1px solid var(--border); border-radius: 12px; padding: 12px; background: var(--input-bg); color: var(--text-main); font: 15px inherit; box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.06); }
      input[type="number"]::-webkit-outer-spin-button, input[type="number"]::-webkit-inner-spin-button { -webkit-appearance: none; margin: 0; }
      input[type="number"] { -moz-appearance: textfield; appearance: textfield; }
      input:focus, select:focus { outline: none; border-color: var(--button); box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.06), 0 0 0 3px rgba(2, 132, 199, 0.2); }
      select { cursor: pointer; appearance: none; -webkit-appearance: none; background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%230284c7' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E"); background-repeat: no-repeat; background-position: right 14px center; padding-right: 38px; }
      option { background: var(--card-bg); color: var(--text-main); }
      .codebox { min-height: 44px; display: flex; align-items: center; justify-content: center; text-align: center; word-break: break-all; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
      input[readonly] { color: var(--hint); }
      .split { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; align-items: center; margin-top: 16px; }
      .split > button { width: 100%; height: 44px; display: flex; align-items: center; justify-content: center; }
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
      <section id="screen-loading" class="panel active">
        <div class="loading-animation" id="lottieContainer"></div>
        <span class="spinner-label" id="loadingText"></span>
      </section>

      <section id="screen-list" class="panel">
        <h1 id="listTitle">Repositories</h1>
        <p id="listSubtitle"></p>
        <div class="top-actions">
          <button id="btnAdd" class="primary">+ Add repository</button>
          <button id="btnAll" style="display:none;">View all repositories</button>
        </div>
        <div id="installationsList"></div>
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
        <div class="field"><label>Telegram Group Chat/Topic Label</label><input id="editChatName" autocomplete="off"></div>
        <div id="editErr" class="helper err"></div>
        <div class="split"><button data-screen="detail">Cancel</button><button id="btnSaveIdentity" class="primary">Save</button></div>
      </section>

      <section id="screen-wizard" class="panel">
        <button class="link" data-screen="list">‹ Back to repositories</button>
        <h1>Add repository</h1>
        <p>Notifications will be sent to:<br><strong id="createDestinationName"></strong><br><span id="createDestinationMeta"></span></p>
        <p><a href="https://t.me/NuecagramBot?startgroup=true" class="link" target="_blank" rel="noopener">+ Add bot to a new group or channel</a></p>
        <div class="field" id="fieldDestination">
          <label>Target Telegram destination</label>
          <select id="inDestination"><option value="">Loading destinations...</option></select>
          <span style="font-size: 12px; font-style: italic; opacity: 0.85; display: block; margin-top: 6px; color: var(--hint);">Don't see your group? Make sure both <strong>you</strong> and <strong>@NuecagramBot</strong> are <strong>Administrators</strong> in the group (send a message there if it doesn't appear right away).</span>
        </div>
        <div class="field"><label>GitLab base URL</label><input id="inUrl" value="https://gitlab.com"></div>
        <div class="field"><label>GitLab project ID</label><input id="inPid" type="number" placeholder="123456"></div>
        <div class="field"><label>Repository name</label><input id="inRepoName" placeholder="nuecagram"></div>
        <div class="field"><label>Telegram Group Chat/Topic Label</label><input id="inChatName" placeholder="Android Team / Notifications"></div>
        <div id="wizErr" class="helper err"></div>
        <div class="split"><button data-screen="list">Cancel</button><button id="btnCreate" class="primary">Create</button></div>
      </section>

      <section id="screen-reveal" class="panel">
        <h1 id="revTitle">Webhook token rotated</h1>
        <p id="revSubtitle">Copy this webhook token now. It will only be shown once.</p>
        <div class="group">
          <div class="field">
            <label>Webhook secret</label>
            <div id="revSecret" class="codebox secret hidden" style="cursor:pointer;" onclick="if(!this.classList.contains('hidden')) copyValue(this.innerText, this)"></div>
            <div class="split" style="margin-top:12px;">
              <button id="btnReveal">Reveal</button>
              <button id="btnCopy" class="primary">Copy token</button>
            </div>
          </div>
          <div class="field">
            <label>Webhook URL</label>
            <div id="revUrl" class="codebox" style="margin-top:4px;cursor:pointer;" onclick="copyValue(this.innerText, this)"></div>
          </div>
        </div>
        <div style="margin-top:16px;">
          <button id="btnRevDone" class="primary" style="width:100%;">Done</button>
        </div>
      </section>

      <section id="screen-rotate-confirm" class="panel">
        <button class="link" data-screen="detail">‹ Back to details</button>
        <h1 id="rotConfirmTitle">Rotate webhook token</h1>
        <div class="box" style="margin-top:16px;border-color:var(--danger);">
          <p style="color:var(--danger);font-weight:700;margin-bottom:8px;">⚠️ Warning</p>
          <p style="margin-bottom:0;">The existing secret token will stop working immediately. You will need to update the secret token in GitLab after rotation.</p>
        </div>
        <div id="rotErr" class="helper err"></div>
        <div class="split" style="margin-top:20px;">
          <button data-screen="detail">Cancel</button>
          <button id="btnConfirmRotate" class="danger" style="background:var(--danger);color:#ffffff;box-shadow:none;">Confirm rotate</button>
        </div>
      </section>

      <section id="screen-delete-confirm" class="panel">
        <button class="link" data-screen="detail">‹ Back to details</button>
        <h1 id="delConfirmTitle">Delete repository</h1>
        <div class="box" style="margin-top:16px;border-color:var(--danger);">
          <p style="color:var(--danger);font-weight:700;margin-bottom:8px;">⚠️ Warning</p>
          <p style="margin-bottom:0;">This will permanently stop all GitLab notifications to this group. Remove the webhook from GitLab first to avoid ongoing delivery failures.</p>
        </div>
        <div id="delErr" class="helper err"></div>
        <div class="split" style="margin-top:20px;">
          <button data-screen="detail">Cancel</button>
          <button id="btnConfirmDelete" class="danger" style="background:var(--danger);color:#ffffff;box-shadow:none;">Confirm delete</button>
        </div>
      </section>
    </div>
    <script src="${basePath}/webapp/app.js"></script>
  </body>
</html>
""".trimIndent()

@Suppress("LongMethod", "MagicNumber")
private fun webAppJsScript(basePath: String): String = """
let userCsrf = '';
const LOADING_TEXTS = [
  'Bribing the GitLab servers...',
  'Waking up the paper plane...',
  'Untangling webhook spaghetti...',
  'Asking Telegram nicely...',
  'Counting merge requests...',
  'Herding notifications...',
  'Feeding the CI pipeline...',
  'Pretending to be fast...',
  'Teaching the bot new tricks...',
  'Blaming the intern...',
  'Reticulating splines...',
  'Negotiating with the database...',
  'Almost there (probably)...',
  'Checking if production is on fire...',
  'Locating the missing semicolon...',
  'Convincing CI to cooperate...',
  'Reading the docs so you do not have to...',
  'Converting caffeine into webhooks...',
  'Wiggling the Ethernet cable...',
  'Refactoring the universe...',
  'Buying more RAM...',
  'Polishing the paper plane...',
  'Rebasing reality...',
  'Hunting flaky tests...',
  'Aligning the merge request chakras...',
  'Optimizing optimizations...',
  'Compiling feelings...',
  'Assembling pixels...',
  'Sharpening the admin badge...',
  'Checking the hidden group scope...',
  'Making Telegram behave...',
  'Summoning the webhook spirits...',
  'Dusting off the database indexes...',
  'Resolving merge conflicts diplomatically...',
  'Interpreting GitLab hieroglyphics...',
  'Debugging the inevitable edge case...',
  'Reheating last nights deployment...',
  'Rolling for initiative against latency...',
  'Translating stack traces...',
  'Asking the bot who broke production...',
  'Preparing a strongly worded callback query...',
  'Generating plausible progress...',
  'Performing advanced button science...',
  'Double-checking the secret secrets...',
  'Shuffling repositories into formation...',
  'Reassuring the load balancer...',
  'Charging the rubber duck...',
  'Looking busy for the status page...',
  'Removing one more legacy workaround...',
  'Rehydrating dehydrated JSON...',
  'Inspecting suspiciously round trip times...',
  'Doing cloud things with tiny paper planes...',
];
let loadingTimer = null;
(function() {
  const el = document.getElementById('loadingText');
  if (!el) return;
  let last = -1;
  function nextLoadingText() {
    if (LOADING_TEXTS.length === 1) return LOADING_TEXTS[0];
    let i = Math.floor(Math.random() * LOADING_TEXTS.length);
    while (i === last) i = Math.floor(Math.random() * LOADING_TEXTS.length);
    last = i;
    return LOADING_TEXTS[i];
  }
  el.innerText = nextLoadingText();
  loadingTimer = setInterval(function() {
    el.innerText = nextLoadingText();
  }, 2200);
})();
(function initLottie() {
  const container = document.getElementById('lottieContainer');
  if (!container || typeof lottie === 'undefined') return;
  try {
    lottie.loadAnimation({
      container: container,
      renderer: 'svg',
      loop: true,
      autoplay: true,
      path: '${basePath}/webapp/loading.json'
    });
  } catch (e) {}
})();
let sToken = '';
let currentContext = {};
let items = [];
let currentItem = null;
let listMode = 'scoped';
const avatars = ['01-wildlife-avatar-1-giraffe.png','02-wildlife-avatar-10-tiger.png','03-wildlife-avatar-11-zebra.png','04-wildlife-avatar-12-sloth.png','05-wildlife-avatar-13-yak.png','06-wildlife-avatar-14-impala.png','07-wildlife-avatar-2-elephant.png','08-wildlife-avatar-3-lion.png','09-wildlife-avatar-4-hippo.png','10-wildlife-avatar-5-koala.png','11-wildlife-avatar-6-deer.png','12-wildlife-avatar-7-bear.png','13-wildlife-avatar-8-mouse.png','14-wildlife-avatar-9-rabbit.png','15-animal-avatar-1-giraffe.png','16-animal-avatar-2-panda.png','17-animal-avatar-3-lion.png','18-animal-avatar-4-elephant.png','19-animal-avatar-5-hippo.png','20-animal-avatar-6-tiger.png','21-animal-avatar-7-bear.png','22-baby-jungle-avatar-1-koala.png','23-baby-jungle-avatar-2-giraffe.png','24-baby-jungle-avatar-3-elephant.png','25-baby-jungle-avatar-4-leopard.png','26-baby-jungle-avatar-5-lion.png','27-baby-jungle-avatar-6-bear.png','28-cat-avatar-1.png','29-cat-avatar-2.png','30-cat-avatar-3.png','31-cat-avatar-4.png','32-cat-avatar-5.png','33-doodle-cat-avatar-1.png','34-doodle-cat-avatar-2.png','35-doodle-cat-avatar-3.png','36-doodle-cat-avatar-4.png'];

function getAuthHeaders(extra) {
  const h = Object.assign({}, extra || {});
  if (userCsrf) h['X-CSRF-Token'] = userCsrf;
  if (sToken) h['X-Session-' + 'Token'] = sToken;
  return h;
}

function copyValue(val, el) {
  if (navigator.clipboard) navigator.clipboard.writeText(val);
  if (el.getAttribute('data-copying') === 'true') return;
  el.setAttribute('data-copying', 'true');
  const originalHtml = el.innerHTML;
  const originalColor = el.style.color;
  el.innerText = '✓ Copied';
  el.style.color = 'var(--success)';
  if (el._copyTimer) clearTimeout(el._copyTimer);
  el._copyTimer = setTimeout(function() {
    el.innerHTML = originalHtml;
    el.style.color = originalColor;
    el.removeAttribute('data-copying');
    delete el._copyTimer;
  }, 1800);
}

function escapeHtml(value) {
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

function fallbackAvatarFor(item, index) {
  return avatarFor(item, index + 1);
}

function showScreen(name) {
  if (name !== 'loading' && loadingTimer) {
    clearInterval(loadingTimer);
    loadingTimer = null;
  }
  document.querySelectorAll('.panel').forEach(function(el) { el.classList.remove('active'); });
  const target = document.getElementById('screen-' + name);
  if (target) target.classList.add('active');
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
  showScreen('loading');
  try {
    const res = await fetch('${basePath}/api/webapp/auth', {
      method: 'POST',
      headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ initData: (tg && tg.initData) || '', startParam: extractStartParam() })
    });
    if (!res.ok) {
      if (res.status === 401) {
        renderError('Telegram Access Required', 'This management portal must be opened inside Telegram.\nOpen @NuecagramBot and tap OPEN.');
      } else {
        renderError(res.status === 403 ? 'Admin access required' : 'Authentication required', 'Only Telegram group admins can manage repositories here.');
      }
      return;
    }
    const data = await res.json();
    userCsrf = data.csrf;
    if (data.sessionToken) sToken = data.sessionToken;
    currentContext = { chatId: data.telegramChatId, topicId: data.telegramTopicId };
    listMode = (currentContext.chatId != null && currentContext.chatId < 0) ? 'scoped' : 'all';
    setupHandlers();
    await loadInstallations();
  } catch (e) {
    renderError('Connection error', 'Could not reach Nuecagram. Check your connection and try again.');
  }
}

async function loadInstallations(silent) {
  if (!silent) {
    const el = document.getElementById('installationsList');
    if (el) el.innerHTML = '';
  }
  try {
    const qs = listMode === 'all' ? '?scope=all' : '';
    const res = await fetch('${basePath}/api/webapp/installations' + qs, { headers: getAuthHeaders() });
    if (!res.ok) {
      if (!silent) renderError(res.status === 403 ? 'Admin access required' : 'Nuecagram needs admin access', res.status === 403 ? 'Only Telegram group admins can manage repositories here.' : 'Give the bot admin access in this group to manage repositories.');
      return;
    }
    items = await res.json();
    renderList();
  } catch (e) {
    if (!silent) renderError('Connection error', 'Could not load repositories. Check your connection and try again.');
  }
}

function renderHeader() {
  const scoped = listMode !== 'all' && currentContext.chatId != null && currentContext.chatId < 0;
  document.getElementById('listTitle').innerText = scoped ? 'Repositories' : 'All repositories';
  document.getElementById('listSubtitle').innerText = scoped ? destinationMeta({telegramChatId: currentContext.chatId, telegramTopicId: currentContext.topicId}) + '\nNotifications go to this group/topic.' : 'Repositories you can manage across groups/topics.';
  document.getElementById('btnAll').style.display = scoped ? '' : 'none';
  const topActions = document.querySelector('#screen-list .top-actions');
  if (topActions) topActions.style.display = 'flex';
}

function renderList() {
  renderHeader();
  showScreen('list');
  const el = document.getElementById('installationsList');
  if (!items.length) {
    const scoped = listMode !== 'all' && currentContext.chatId != null && currentContext.chatId < 0;
    el.innerHTML = scoped
      ? '<div class="box"><h1>No repositories here yet.</h1><p>Add a GitLab project to start notifications.</p><button class="primary" id="btnEmptyAdd">+ Add repository</button></div>'
      : '<div class="box"><h1>No repositories found.</h1><p>Tap + Add repository to connect a project to one of your Telegram groups.</p></div>';
    const add = document.getElementById('btnEmptyAdd');
    if (add) add.addEventListener('click', openAdd);
    return;
  }
  el.innerHTML = '';
  items.forEach(function(item, index) {
    const card = document.createElement('button');
    card.className = 'card' + (item.muted ? ' muted' : '');
    card.innerHTML = '<img class="avatar" alt="" aria-hidden="true" src="${basePath}/webapp/avatars/' + avatarFor(item, index) + '" onerror="this.onerror=null;this.src=\'${basePath}/webapp/avatars/' + fallbackAvatarFor(item, index) + '\'">' +
      '<div class="grow"><div class="row"><div class="title">' + escapeHtml(item.repoName) + '</div><span class="badge ' + (item.muted ? 'badge-muted">MUTED' : 'badge-active">ACTIVE') + '</span><span class="chev">›</span></div>' +
      '<div class="sub">' + escapeHtml(destinationLabel(item)) + '</div><div class="meta">' + escapeHtml(repoMeta(item)) + '</div></div>';
    card.addEventListener('click', function() { openDetail(item.id); });
    el.appendChild(card);
  });
}

function renderError(title, body) {
  showScreen('list');
  document.getElementById('listTitle').innerText = title;
  document.getElementById('listSubtitle').innerText = body;
  const topActions = document.querySelector('#screen-list .top-actions');
  if (topActions) topActions.style.display = 'none';
  const el = document.getElementById('installationsList');
  if (title === 'Telegram Access Required') {
    el.innerHTML = '<div class="box" style="text-align:center;padding:28px 18px;">' +
      '<h1 style="font-size:20px;margin-bottom:10px;">Telegram Access Required</h1>' +
      '<p style="margin-bottom:20px;">This management portal must be opened inside Telegram.</p>' +
      '<div class="codebox" style="margin-bottom:18px;font-weight:700;">Open @NuecagramBot and tap OPEN</div>' +
      '<a href="https://t.me/NuecagramBot" class="primary" style="display:block;text-decoration:none;padding:12px;border-radius:12px;text-align:center;" target="_blank" rel="noopener">Open Telegram Bot</a></div>';
  } else {
    el.innerHTML = '<div class="box"><p>' + escapeHtml(body) + '</p></div>';
  }
}

async function openDetail(id) {
  const existing = items.find(function(x) { return x.id === id; });
  if (existing) {
    currentItem = existing;
    renderDetail();
    showScreen('detail');
  }
  try {
    const res = await fetch('${basePath}/api/webapp/installations/' + id, { headers: getAuthHeaders() });
    if (!res.ok) {
      if (!existing) {
        const title = res.status === 404 ? 'Repository not found' : (res.status === 401 || res.status === 403 ? 'Access denied' : 'Could not open repository');
        const body = res.status === 404 ? 'This repository is no longer available or you no longer have access.' : 'Try again in a moment or return to the list.';
        renderError(title, body);
      }
      return;
    }
    currentItem = await res.json();
    items = items.map(function(x) { return x.id === currentItem.id ? currentItem : x; });
    renderDetail();
    if (!existing) showScreen('detail');
  } catch (e) {
    if (!existing) renderError('Connection error', 'Could not load repository details. Check your connection and try again.');
  }
}

function renderDetail() {
  const item = currentItem;
  document.getElementById('detailBody').innerHTML = '<div class="card"><img class="avatar" alt="" aria-hidden="true" src="${basePath}/webapp/avatars/' + avatarFor(item, 0) + '" onerror="this.onerror=null;this.src=\'${basePath}/webapp/avatars/' + fallbackAvatarFor(item, 0) + '\'"><div class="grow"><div class="title">' + escapeHtml(item.repoName) + '</div><div class="sub">' + escapeHtml(destinationLabel(item)) + '</div></div><span class="badge ' + (item.muted ? 'badge-muted">MUTED' : 'badge-active">ACTIVE') + '</span></div>' +
    '<div class="section"><div class="section-title">Repository</div><div class="group"><div class="row" style="cursor:pointer" onclick="copyValue(\'' + escapeHtml(item.gitlabBaseUrl + (item.gitlabProjectId ? '/#' + item.gitlabProjectId : '')) + '\', this.querySelector(\'.meta\'))"><strong>GitLab</strong><span class="meta">' + escapeHtml(item.gitlabBaseUrl + (item.gitlabProjectId ? '/#' + item.gitlabProjectId : '')) + '</span></div><div class="row" style="cursor:pointer" onclick="copyValue(\'' + escapeHtml(item.id) + '\', this.querySelector(\'.meta\'))"><strong>Installation ID</strong><span class="meta">' + escapeHtml(item.id) + '</span></div></div></div>' +
    '<div class="section"><div class="section-title">Destination</div><div class="group"><div class="row"><strong>Telegram</strong><span class="meta">' + escapeHtml(destinationMeta(item)) + '</span></div></div></div>' +
    '<div class="section"><div class="section-title">Actions</div><div class="top-actions"><button id="btnTest">Test notification</button><button id="btnMute">' + (item.muted ? 'Unmute notifications' : 'Mute notifications') + '</button></div><div id="actionHelp" class="helper"></div></div>' +
    '<div class="section"><div class="section-title">Settings</div><button id="btnEdit">Edit names ›</button></div><div class="section"><div class="section-title">Danger zone</div><div style="display:flex;flex-direction:column;gap:10px;"><button id="btnRotate" class="danger">Rotate webhook token ›</button><button id="btnDelete" class="danger">Delete repository ›</button></div></div>';
  document.getElementById('btnTest').addEventListener('click', testDelivery);
  document.getElementById('btnMute').addEventListener('click', toggleMute);
  document.getElementById('btnEdit').addEventListener('click', openEdit);
  document.getElementById('btnRotate').addEventListener('click', openRotateConfirm);
  document.getElementById('btnDelete').addEventListener('click', openDeleteConfirm);
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
  try {
    const res = await fetch('${basePath}/api/webapp/installations/' + currentItem.id + '/identity', {
      method: 'POST', headers: getAuthHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ repoName: repoName, chatName: chatName })
    });
    if (!res.ok) { document.getElementById('editErr').innerText = 'Could not save names.'; return; }
    currentItem = await res.json();
    items = items.map(function(x) { return x.id === currentItem.id ? currentItem : x; });
    renderDetail();
    showScreen('detail');
  } catch (e) {
    document.getElementById('editErr').innerText = 'Could not save names.';
  }
}

function updateChatNameFromDestination() {
  const selectEl = document.getElementById('inDestination');
  const chatNameEl = document.getElementById('inChatName');
  if (!selectEl || !chatNameEl) return;
  const selectedText = selectEl.options[selectEl.selectedIndex] ? selectEl.options[selectEl.selectedIndex].text : '';
  if (selectedText && selectedText.indexOf('Loading') === -1 && selectedText.indexOf('No Telegram groups') === -1 && selectedText.indexOf('Could not load') === -1) {
    if (!chatNameEl.value || chatNameEl.getAttribute('data-autofilled') === 'true') {
      chatNameEl.value = selectedText;
      chatNameEl.setAttribute('data-autofilled', 'true');
    }
  }
}

async function openAdd() {
  const isGroup = currentContext.chatId != null && currentContext.chatId < 0;
  const selectEl = document.getElementById('inDestination');
  document.getElementById('createDestinationName').innerText = isGroup
    ? destinationMeta({telegramChatId: currentContext.chatId, telegramTopicId: currentContext.topicId})
    : 'Target Telegram Destination';
  document.getElementById('createDestinationMeta').innerHTML = isGroup
    ? destinationMeta({telegramChatId: currentContext.chatId, telegramTopicId: currentContext.topicId})
    : 'Select a destination group or topic<br>below to connect your GitLab project.';
  document.getElementById('wizErr').innerText = '';
  const inChatName = document.getElementById('inChatName');
  if (inChatName) {
    inChatName.value = '';
    inChatName.setAttribute('data-autofilled', 'true');
  }

  if (isGroup) {
    document.getElementById('fieldDestination').style.display = 'none';
  } else {
    document.getElementById('fieldDestination').style.display = '';
    selectEl.innerHTML = '<option value="">Loading destinations...</option>';
    try {
      const res = await fetch('${basePath}/api/webapp/destinations', { headers: getAuthHeaders() });
      if (res.ok) {
        const dests = await res.json();
        if (dests.length > 0) {
          selectEl.innerHTML = dests.map(function(d) {
            return '<option value="' + escapeHtml(d.id) + '">' + escapeHtml(d.name) + '</option>';
          }).join('');
          updateChatNameFromDestination();
        } else {
          selectEl.innerHTML = '<option value="">No Telegram groups found — add bot to a group first</option>';
        }
      } else {
        selectEl.innerHTML = '<option value="">Could not load destinations</option>';
      }
    } catch (e) {
      selectEl.innerHTML = '<option value="">Could not load destinations</option>';
    }
  }

  showScreen('wizard');
}

async function createInstallation() {
  const url = document.getElementById('inUrl').value.trim();
  const pid = parseInt(document.getElementById('inPid').value.trim(), 10);
  const repoName = document.getElementById('inRepoName').value.trim();
  const chatName = document.getElementById('inChatName').value.trim();
  if (!url.startsWith('https://') || !pid || !repoName) { document.getElementById('wizErr').innerText = 'Could not create repository. Check the GitLab project ID.'; return; }
  const payload = { repoName: repoName, chatName: chatName, gitlabBaseUrl: url, gitlabProjectId: pid };
  if (currentContext.chatId != null && currentContext.chatId < 0) {
    payload.telegramChatId = currentContext.chatId;
    if (currentContext.topicId != null) payload.telegramTopicId = currentContext.topicId;
  } else {
    const destVal = document.getElementById('inDestination').value;
    if (!destVal) { document.getElementById('wizErr').innerText = 'Target Telegram group destination required.'; return; }
    const parts = destVal.split(':');
    const destChatId = parseInt(parts[0], 10);
    const destTopicId = parts.length > 1 ? parseInt(parts[1], 10) : 0;
    if (!destChatId || destChatId >= 0) { document.getElementById('wizErr').innerText = 'Target Telegram group destination required.'; return; }
    payload.telegramChatId = destChatId;
    if (destTopicId !== 0) payload.telegramTopicId = destTopicId;
  }
  try {
    const res = await fetch('${basePath}/api/webapp/installations', {
      method: 'POST', headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(payload)
    });
    if (res.status !== 201) { document.getElementById('wizErr').innerText = 'Could not create repository. Check the GitLab project ID.'; return; }
    const data = await res.json();
    showReveal(data.credential, data.webhookUrl, 'Repository created', false);
  } catch (e) {
    document.getElementById('wizErr').innerText = 'Could not create repository. Check the GitLab project ID.';
  }
}

function openRotateConfirm() {
  document.getElementById('rotConfirmTitle').innerText = 'Rotate token for ' + (currentItem ? currentItem.repoName : 'repository') + '?';
  document.getElementById('rotErr').innerText = '';
  const btn = document.getElementById('btnConfirmRotate');
  if (btn) btn.disabled = false;
  showScreen('rotate-confirm');
}

async function confirmRotateInstallation() {
  if (!currentItem) return;
  const btn = document.getElementById('btnConfirmRotate');
  if (btn && btn.disabled) return;
  if (btn) btn.disabled = true;
  try {
    const res = await fetch('${basePath}/api/webapp/installations/' + currentItem.id + '/rotate', {
      method: 'POST',
      headers: getAuthHeaders({ 'Content-Type': 'application/json' })
    });
    if (!res.ok) {
      document.getElementById('rotErr').innerText = 'Could not rotate token.';
      if (btn) btn.disabled = false;
      return;
    }
    const data = await res.json();
    showReveal(data.credential, '', 'Webhook token rotated', true);
  } catch (e) {
    document.getElementById('rotErr').innerText = 'Could not rotate token.';
    if (btn) btn.disabled = false;
  }
}

function openDeleteConfirm() {
  document.getElementById('delConfirmTitle').innerText = 'Delete ' + (currentItem ? currentItem.repoName : 'repository') + '?';
  document.getElementById('delErr').innerText = '';
  const btn = document.getElementById('btnConfirmDelete');
  if (btn) btn.disabled = false;
  showScreen('delete-confirm');
}

async function confirmDeleteInstallation() {
  if (!currentItem) return;
  const btn = document.getElementById('btnConfirmDelete');
  if (btn && btn.disabled) return;
  if (btn) btn.disabled = true;
  try {
    const res = await fetch('${basePath}/api/webapp/installations/' + currentItem.id, {
      method: 'DELETE',
      headers: getAuthHeaders({ 'Content-Type': 'application/json' })
    });
    if (!res.ok) {
      document.getElementById('delErr').innerText = 'Could not delete repository.';
      if (btn) btn.disabled = false;
      return;
    }
    items = items.filter(function(x) { return x.id !== currentItem.id; });
    currentItem = null;
    showScreen('list');
    await loadInstallations();
  } catch (e) {
    document.getElementById('delErr').innerText = 'Could not delete repository.';
    if (btn) btn.disabled = false;
  }
}

function showReveal(token, url, title, isRotate) {
  document.getElementById('revTitle').innerText = title;
  const sub = document.getElementById('revSubtitle');
  if (sub) {
    sub.innerText = isRotate
      ? 'Copy this rotated token now. It will only be shown once.'
      : 'Copy this webhook token now. It will only be shown once.';
  }
  const secret = document.getElementById('revSecret');
  secret.innerText = token;
  secret.classList.add('hidden');
  document.getElementById('btnReveal').innerText = 'Reveal';
  const urlEl = document.getElementById('revUrl');
  if (urlEl) {
    const field = urlEl.closest('.field');
    if (field) field.style.display = isRotate ? 'none' : '';
    urlEl.innerText = url || '';
  }
  showScreen('reveal');
}

async function toggleMute() {
  try {
    const res = await fetch('${basePath}/api/webapp/installations/' + currentItem.id + '/mute', { method: 'POST', headers: getAuthHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ muted: !currentItem.muted }) });
    if (!res.ok) { setAction('Could not update notifications.', false); return; }
    currentItem.muted = !currentItem.muted;
    items = items.map(function(x) { return x.id === currentItem.id ? currentItem : x; });
    renderDetail();
    setAction('Notification setting updated.', true);
  } catch (e) {
    setAction('Could not update notifications.', false);
  }
}

async function testDelivery() {
  try {
    const res = await fetch('${basePath}/api/webapp/installations/' + currentItem.id + '/test', { method: 'POST', headers: getAuthHeaders({ 'Content-Type': 'application/json' }) });
    setAction(res.ok ? '✓ Test notification sent.' : 'Could not send test notification.', res.ok);
  } catch (e) {
    setAction('Could not send test notification.', false);
  }
}

function setupHandlers() {
  document.querySelectorAll('[data-screen]').forEach(function(b) {
    b.addEventListener('click', function() {
      const targetScreen = b.getAttribute('data-screen');
      showScreen(targetScreen);
      if (targetScreen === 'list') {
        renderList();
        loadInstallations(true);
      }
    });
  });
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
  document.getElementById('btnCopy').addEventListener('click', function() {
    const secret = document.getElementById('revSecret');
    const val = secret.innerText;
    if (navigator.clipboard) navigator.clipboard.writeText(val);
    copyValue(val, secret);
  });
  document.getElementById('inDestination').addEventListener('change', updateChatNameFromDestination);
  const inChatNameEl = document.getElementById('inChatName');
  if (inChatNameEl) {
    inChatNameEl.addEventListener('input', function() {
      this.removeAttribute('data-autofilled');
    });
  }
  document.getElementById('btnConfirmRotate').addEventListener('click', confirmRotateInstallation);
  document.getElementById('btnConfirmDelete').addEventListener('click', confirmDeleteInstallation);
}

initWebApp();
""".trimIndent()
