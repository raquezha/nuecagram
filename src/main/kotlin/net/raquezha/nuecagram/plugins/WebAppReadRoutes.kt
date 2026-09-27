@file:Suppress("TooManyFunctions")

package net.raquezha.nuecagram.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.telegram.isTelegramAdmin
import net.raquezha.nuecagram.telegram.isTelegramMember

private const val MAX_DESTINATION_LOOKUPS_IN_FLIGHT = 8

internal suspend fun ApplicationCall.handleGetInstallations(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
) {
    val session = authenticateWebAppSession(installationRepository) ?: return
    val groupChatId = session.telegramChatId?.takeIf { it < 0 }
    val groupUserStatus = if (groupChatId != null) {
        if (!verifyBotAdminStatus(groupChatId, telegramService)) return
        telegramApiCall { telegramService.chatMemberStatus(groupChatId, session.telegramUserId) }
            .getOrElse {
                respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponsePayload("Telegram membership could not be verified"),
                )
                return
            }
    } else {
        null
    }
    if (groupChatId != null && !isTelegramMember(groupUserStatus)) {
        respond(HttpStatusCode.Forbidden, ErrorResponsePayload("Telegram group membership required"))
        return
    }

    val isGroupContext = groupChatId != null
    val scopedOnly = request.queryParameters["scope"] != "all"
    val items = if (isGroupContext && scopedOnly && !isTelegramAdmin(groupUserStatus)) {
        emptyList()
    } else if (isGroupContext && scopedOnly) {
        installationRepository.listInstallationsForContext(session.telegramChatId, session.telegramTopicId)
    } else {
        val adminChatIds = mutableMapOf<Long, Boolean>()
        suspend fun isActiveAdmin(chatId: Long): Boolean =
            adminChatIds.getOrPut(chatId) {
                runCatching { telegramService.chatMemberStatus(chatId, session.telegramUserId) }
                    .map(::isTelegramAdmin)
                    .getOrDefault(false)
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

internal suspend fun ApplicationCall.handleGetInstallationDetail(
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

internal suspend fun ApplicationCall.handleGetDestinations(
    installationRepository: InstallationRepository,
    telegramService: TelegramService,
) {
    val session = authenticateWebAppSession(installationRepository) ?: return
    val userId = session.telegramUserId
    val botUserId = telegramApiCall { telegramService.getMe()?.id }.getOrNull()
    if (botUserId == null) {
        respond(HttpStatusCode.ServiceUnavailable, ErrorResponsePayload("Telegram bot identity is unavailable"))
        return
    }

    val installedList = installationRepository.installationsForAdmin(userId)
    val knownList = installationRepository.knownTelegramDestinations()
    val allChatIds = (installedList.map { it.telegramChatId } + knownList.map { it.telegramChatId }).distinct()

    val activeAdminMap = activeDestinationAdminMap(allChatIds, userId, botUserId, telegramService)

    fun isActiveAdmin(chatId: Long): Boolean = activeAdminMap[chatId] == true

    val installed = installedList
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

    val known = knownList.mapNotNull { dest ->
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
        .sortedByDescending { it.telegramChatId == session.telegramChatId }
    appendWebAppSecurityHeaders()
    respond(HttpStatusCode.OK, combined)
}

private suspend fun activeDestinationAdminMap(
    chatIds: List<Long>,
    userId: Long,
    botUserId: Long,
    telegramService: TelegramService,
): Map<Long, Boolean> = chatIds.chunked(MAX_DESTINATION_LOOKUPS_IN_FLIGHT).flatMap { batch ->
    coroutineScope {
        batch.map { chatId ->
            async {
                val userIsMember = telegramApiCall {
                    telegramService.chatMemberStatus(chatId, userId)
                }.getOrNull().let(::isTelegramMember)
                val botIsAdmin = userIsMember && telegramApiCall {
                    telegramService.chatMemberStatus(chatId, botUserId)
                }.getOrNull().let(::isTelegramAdmin)
                chatId to (userIsMember && botIsAdmin)
            }
        }.awaitAll()
    }
}.toMap()

@Serializable
private data class DestinationPayload(
    val id: String,
    val name: String,
    val telegramChatId: Long,
    val telegramTopicId: Long? = null,
)

