package net.raquezha.nuecagram.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import java.util.UUID
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.models.ActorType
import net.raquezha.nuecagram.db.models.AuditIdentityDelta
import net.raquezha.nuecagram.db.models.AuditMetadataPatch
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService

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
