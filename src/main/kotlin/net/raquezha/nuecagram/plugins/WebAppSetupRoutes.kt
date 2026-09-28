package net.raquezha.nuecagram.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.db.DuplicateInstallationException
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.models.ActorType
import net.raquezha.nuecagram.db.models.AuditMetadataPatch
import net.raquezha.nuecagram.db.models.ProvisionInstallationRequest
import net.raquezha.nuecagram.db.models.WebAppSessionContext
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.telegram.isTelegramAdmin
import net.raquezha.nuecagram.telegram.isTelegramMember

internal suspend fun ApplicationCall.handleCreateInstallation(
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

internal suspend fun resolveDmId(
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

private suspend fun checkInstallationTargetAccess(
    userId: Long,
    targetChatId: Long,
    telegramService: TelegramService,
): TargetAccess {
    val userStatus = telegramApiCall { telegramService.chatMemberStatus(targetChatId, userId) }
        .getOrElse { return TargetAccess.UNAVAILABLE }
    if (!isTelegramMember(userStatus)) return TargetAccess.USER_NOT_MEMBER

    return when (telegramBotAdminStatus(targetChatId, telegramService)) {
        BotAdminStatus.ADMIN ->
            if (isTelegramAdmin(userStatus)) {
                TargetAccess.ADMIN_ALLOWED
            } else {
                TargetAccess.MEMBER_ALLOWED
            }
        BotAdminStatus.NOT_ADMIN -> TargetAccess.BOT_NOT_ADMIN
        BotAdminStatus.UNAVAILABLE -> TargetAccess.UNAVAILABLE
    }
}

private suspend fun ApplicationCall.processCreateInstallation(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
    config: ConfigWithSecrets,
    json: Json,
    basePath: String,
): WebAppResponseSpec? {
    val session = authenticateWebAppSession(installationRepository) ?: return null
    if (!verifyCsrfHeader(installationRepository, session)) return null

    val bodyText = receiveText()
    val parsed = runCatching { json.decodeFromString<CreateInstallationRequestPayload>(bodyText) }.getOrNull()
    val target = resolveTargetDestination(session, parsed)
    val dmId = resolveDmId(session, installationRepository)
    val targetAccess = if (parsed != null && dmId != null && target.chatId != null && target.chatId < 0) {
        checkInstallationTargetAccess(session.telegramUserId, target.chatId, telegramService)
    } else {
        null
    }

    if (validateCreateRequest(dmId, parsed, target, targetAccess)) return null
    return createAndRespond(
        installationRepository = installationRepository,
        config = config,
        basePath = basePath,
        parsed = requireNotNull(parsed),
        target = target,
        session = session,
        targetAccess = requireNotNull(targetAccess),
    )
}

private suspend fun ApplicationCall.validateCreateRequest(
    dmId: Long?,
    parsed: CreateInstallationRequestPayload?,
    target: TargetDestination,
    targetAccess: TargetAccess?,
): Boolean {
    val error = createRequestError(dmId, parsed, target, targetAccess) ?: return false
    respondError(error.first, error.second)
    return true
}

private fun createRequestError(
    dmId: Long?,
    parsed: CreateInstallationRequestPayload?,
    target: TargetDestination,
    targetAccess: TargetAccess?,
): Pair<HttpStatusCode, String>? = when {
    dmId == null -> HttpStatusCode.Forbidden to "DM bootstrap required"
    target.chatId == null || target.chatId >= 0 ->
        HttpStatusCode.BadRequest to "Valid target Telegram group chat ID required"
    parsed == null || parsed.gitlabBaseUrl.isBlank() ->
        HttpStatusCode.BadRequest to "Missing or invalid payload"
    targetAccess == null || targetAccess == TargetAccess.UNAVAILABLE ->
        HttpStatusCode.ServiceUnavailable to "Telegram destination access could not be verified"
    targetAccess == TargetAccess.USER_NOT_MEMBER ->
        HttpStatusCode.Forbidden to "You must be a member of the target Telegram group"
    targetAccess == TargetAccess.BOT_NOT_ADMIN ->
        HttpStatusCode.Forbidden to "Nuecagram must be an administrator in the target Telegram group"
    else -> createPayloadError(requireNotNull(parsed))
}

private fun createPayloadError(parsed: CreateInstallationRequestPayload): Pair<HttpStatusCode, String>? = when {
    parsed.repoName.isBlank() || parsed.repoName.trim() == "Unknown Repository" ->
        HttpStatusCode.BadRequest to "repoName must be non-blank and not use the legacy fallback value"
    !parsed.gitlabBaseUrl.startsWith("https://") ->
        HttpStatusCode.BadRequest to "gitlabBaseUrl must start with https://"
    else -> null
}

private suspend fun createAndRespond(
    installationRepository: InstallationRepository,
    config: ConfigWithSecrets,
    basePath: String,
    parsed: CreateInstallationRequestPayload,
    target: TargetDestination,
    session: WebAppSessionContext,
    targetAccess: TargetAccess,
): WebAppResponseSpec {
    val request = ProvisionInstallationRequest(
        repoName = parsed.repoName,
        chatName = parsed.chatName,
        gitlabBaseUrl = parsed.gitlabBaseUrl.trimEnd('/'),
        gitlabProjectId = parsed.gitlabProjectId,
        telegramChatId = target.chatId!!,
        telegramTopicId = target.topicId,
        adminTelegramUserId = if (targetAccess == TargetAccess.ADMIN_ALLOWED) session.telegramUserId else null,
        actorType = ActorType.WEBAPP_SESSION,
        actorId = session.telegramUserId.toString(),
        auditAction = "webapp_setup",
        auditMetadataPatch = AuditMetadataPatch(
            actorUsername = session.username,
            actorFirstName = session.firstName,
        ),
    )
    val provisioned = try {
        installationRepository.provisionInstallation(request)
    } catch (_: DuplicateInstallationException) {
        return WebAppResponseSpec(
            HttpStatusCode.Conflict,
            ErrorResponsePayload("This repository is already connected to this Telegram chat or topic"),
        )
    } catch (e: Exception) {
        return WebAppResponseSpec(
            HttpStatusCode.InternalServerError,
            ErrorResponsePayload("Failed to create installation: ${e.message ?: "Internal error"}"),
        )
    }
    return WebAppResponseSpec(
        HttpStatusCode.Created,
        toCreateResponse(provisioned.installation, provisioned.credential, config.webhookEndpointUrl()),
    )
}

private fun toCreateResponse(
    r: net.raquezha.nuecagram.db.models.InstallationRecord,
    t: net.raquezha.nuecagram.db.models.IssuedCredential,
    url: String,
): CreateInstallationResponsePayload =
    CreateInstallationResponsePayload(
        installation = r.toAdminContext(muted = false).toResponsePayload(),
        credential = t.raw,
        webhookUrl = url,
    )

