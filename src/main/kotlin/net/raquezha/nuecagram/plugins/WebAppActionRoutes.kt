package net.raquezha.nuecagram.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.db.DuplicateInstallationException
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.models.ActorType
import net.raquezha.nuecagram.db.models.AuditIdentityDelta
import net.raquezha.nuecagram.db.models.DestinationUpdateResult
import net.raquezha.nuecagram.db.models.AuditMetadataPatch
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.telegram.isTelegramAdmin
import net.raquezha.nuecagram.telegram.isTelegramMember

internal suspend fun ApplicationCall.handleMuteInstallation(
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
        actorType = ActorType.WEBAPP_SESSION,
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

internal suspend fun ApplicationCall.handleUpdateIdentity(
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
        actorType = ActorType.WEBAPP_SESSION,
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

private fun String?.escapeTelegramHtml(): String =
    this.orEmpty()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

internal suspend fun ApplicationCall.handleUpdateDestination(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
    json: Json,
) {
    processUpdateDestination(this, installationRepository, telegramService, json)?.let { result ->
        appendWebAppSecurityHeaders()
        respond(result.status, result.payload)
    }
}

private suspend fun processUpdateDestination(
    call: ApplicationCall,
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
    json: Json,
): WebAppResponseSpec? {
    val session = call.authenticateWebAppSession(installationRepository) ?: return null
    if (!call.verifyAdminStatus(session, telegramService) ||
        !call.verifyCsrfHeader(installationRepository, session)
    ) return null

    val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: return destinationError(HttpStatusCode.BadRequest, "Invalid installation ID")
    val item = installationRepository.installationAdminContext(id)
        ?.takeIf { canAccess(session, it, telegramService) }
        ?: return destinationError(HttpStatusCode.NotFound, "Installation not found")
    val payload = runCatching {
        json.decodeFromString<DestinationUpdateRequestPayload>(call.receiveText())
    }.getOrNull()
        ?: return destinationError(HttpStatusCode.BadRequest, "Missing or invalid destination payload")
    if (payload.telegramChatId >= 0 || payload.telegramTopicId?.let { it <= 0 } == true) {
        return destinationError(HttpStatusCode.BadRequest, "Select a valid Telegram group and topic")
    }

    return validateDestinationAccess(installationRepository, telegramService, session.telegramUserId, item, payload)
        ?: saveDestination(installationRepository, session, item, payload)
}

private suspend fun validateDestinationAccess(
    repository: InstallationRepository,
    telegramService: TelegramService,
    userId: Long,
    item: net.raquezha.nuecagram.db.models.InstallationAdminContext,
    payload: DestinationUpdateRequestPayload,
): WebAppResponseSpec? {
    val allowed = repository.knownTelegramDestinations().any {
        it.telegramChatId == payload.telegramChatId && it.telegramTopicId == payload.telegramTopicId
    } || repository.installationsForAdmin(userId).any {
        it.telegramChatId == payload.telegramChatId && it.telegramTopicId == payload.telegramTopicId
    } || (item.telegramChatId == payload.telegramChatId && item.telegramTopicId == payload.telegramTopicId)
    if (!allowed) return destinationError(HttpStatusCode.BadRequest, "Selected Telegram destination is unavailable")

    val botId = telegramApiCall { telegramService.getMe()?.id }.getOrNull()
        ?: return destinationError(HttpStatusCode.ServiceUnavailable, "Telegram bot identity is unavailable")
    return validateDestinationUserMembership(telegramService, payload.telegramChatId, userId)
        ?: validateDestinationBotAccess(telegramService, payload.telegramChatId, botId)
}

private suspend fun validateDestinationUserMembership(
    telegramService: TelegramService,
    chatId: Long,
    userId: Long,
): WebAppResponseSpec? = telegramApiCall {
    telegramService.chatMemberStatus(chatId, userId)
}.fold(
    onSuccess = { status ->
        if (isTelegramMember(status)) null else destinationError(
            HttpStatusCode.Forbidden,
            "You must be a member of the selected Telegram group",
        )
    },
    onFailure = {
        destinationError(HttpStatusCode.ServiceUnavailable, "Telegram membership could not be verified")
    },
)

private suspend fun validateDestinationBotAccess(
    telegramService: TelegramService,
    chatId: Long,
    botId: Long,
): WebAppResponseSpec? = telegramApiCall {
    telegramService.chatMemberStatus(chatId, botId)
}.fold(
    onSuccess = { status ->
        if (isTelegramAdmin(status)) null else destinationError(
            HttpStatusCode.Forbidden,
            "Nuecagram must be an administrator in the selected group",
        )
    },
    onFailure = {
        destinationError(HttpStatusCode.ServiceUnavailable, "Bot access could not be verified")
    },
)

private suspend fun saveDestination(
    repository: InstallationRepository,
    session: net.raquezha.nuecagram.db.models.WebAppSessionContext,
    item: net.raquezha.nuecagram.db.models.InstallationAdminContext,
    payload: DestinationUpdateRequestPayload,
): WebAppResponseSpec = try {
    when (
        val result = repository.updateDestination(
            installationId = item.id,
            telegramChatId = payload.telegramChatId,
            telegramTopicId = payload.telegramTopicId,
            actorId = session.telegramUserId.toString(),
            metadataPatch = AuditMetadataPatch(
                actorUsername = session.username,
                actorFirstName = session.firstName,
            ),
        )
    ) {
        DestinationUpdateResult.NOT_FOUND -> destinationError(HttpStatusCode.NotFound, "Installation not found")
        DestinationUpdateResult.UPDATED, DestinationUpdateResult.UNCHANGED -> {
            if (result == DestinationUpdateResult.UPDATED &&
                session.telegramChatId != null &&
                session.telegramChatId < 0
            ) {
                // Keep group-scoped sessions aligned with the installation so follow-up
                // mute/test/rotate/delete calls still pass canAccess after a move.
                repository.updateWebAppSessionDestination(
                    sessionId = session.sessionId,
                    telegramChatId = payload.telegramChatId,
                    telegramTopicId = payload.telegramTopicId,
                )
            }
            repository.installationAdminContext(item.id)?.let {
                WebAppResponseSpec(HttpStatusCode.OK, it.toResponsePayload())
            } ?: destinationError(HttpStatusCode.NotFound, "Installation not found")
        }
    }
} catch (_: DuplicateInstallationException) {
    destinationError(HttpStatusCode.Conflict, "This repository is already connected to that destination")
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    destinationError(HttpStatusCode.InternalServerError, "Could not save the Telegram destination")
}

private fun destinationError(status: HttpStatusCode, message: String) =
    WebAppResponseSpec(status, ErrorResponsePayload(message))

internal suspend fun ApplicationCall.handleTestInstallation(
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

    val safeRepoLabel = item.repositoryButtonLabel().escapeTelegramHtml()
    val actorMention = when {
        !session.username.isNullOrBlank() -> "@${session.username.trim().removePrefix("@").escapeTelegramHtml()}"
        !session.firstName.isNullOrBlank() ->
            "<a href=\"tg://user?id=${session.telegramUserId}\">${session.firstName.trim().escapeTelegramHtml()}</a>"
        else -> "<a href=\"tg://user?id=${session.telegramUserId}\">Admin</a>"
    }

    telegramService.sendMessage(
        Message(
            chatId = item.telegramChatId.toString(),
            text = "🧪 <b>Test Notification</b>\n\n" +
                "Nuecagram test notification for <b>$safeRepoLabel</b>.\n" +
                "Triggered by $actorMention.\n\n" +
                "<i>Notification delivery is active and working properly.</i>",
            threadId = item.telegramTopicId,
        ),
    )

    installationRepository.writeAuditEvent(
        installationId = item.id,
        actorType = ActorType.WEBAPP_SESSION,
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
