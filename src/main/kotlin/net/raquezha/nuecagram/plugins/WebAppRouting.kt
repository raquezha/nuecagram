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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.ConfigWithSecrets
import net.raquezha.nuecagram.db.models.ActorType
import net.raquezha.nuecagram.db.models.AuditMetadataPatch
import net.raquezha.nuecagram.db.models.InstallationAdminContext
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.models.WebAppSessionContext
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
internal data class InstallationResponsePayload(
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
internal data class DestinationUpdateRequestPayload(
    val telegramChatId: Long,
    val telegramTopicId: Long? = null,
)

@Serializable
internal data class MuteRequestPayload(
    val muted: Boolean,
)

@Serializable
internal data class IdentityRequestPayload(
    val repoName: String,
    val chatName: String? = null,
)

@Serializable
internal data class MuteResponsePayload(
    val id: String,
    val muted: Boolean,
)

@Serializable
internal data class TestResponsePayload(
    val success: Boolean,
    val message: String,
)

@Serializable
internal data class ErrorResponsePayload(
    val error: String,
)

@Serializable
internal data class CreateInstallationRequestPayload(
    val repoName: String,
    val chatName: String? = null,
    val gitlabBaseUrl: String,
    val gitlabProjectId: Long,
    val telegramChatId: Long? = null,
    val telegramTopicId: Long? = null,
)

@Serializable
internal data class CreateInstallationResponsePayload(
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
        call.handleWebAppShell(installationRepository, basePath, config.botUsername)
    }

    get("$basePath/webapp/app.js") {
        call.appendWebAppSecurityHeaders()
        call.respondText(
            webAppAsset("app.js", basePath, config.botUsername),
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

    post("$basePath/api/webapp/installations/{id}/destination") {
        call.handleUpdateDestination(installationRepository, telegramService, json)
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
    botUsername: String,
) {
    appendWebAppSecurityHeaders()
    respondText(
        webAppAsset("webapp.html", basePath, botUsername),
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

internal data class WebAppResponseSpec(
    val status: HttpStatusCode,
    val payload: Any,
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
    if (!verifyAdminStatus(session, telegramService) || !verifyCsrfHeader(installationRepository, session)) return

    val dmId = resolveDmId(session, installationRepository)
    val idParam = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val item = if (idParam != null) installationRepository.installationAdminContext(idParam) else null

    when {
        dmId == null -> respond(HttpStatusCode.Forbidden, ErrorResponsePayload("DM bootstrap required"))
        idParam == null -> respond(HttpStatusCode.BadRequest, ErrorResponsePayload("Invalid installation ID"))
        item == null || !canAccess(session, item, telegramService) -> {
            respond(HttpStatusCode.NotFound, ErrorResponsePayload("Installation not found"))
        }
        else -> executeDeleteInstallation(installationRepository, session, item)
    }
}

private suspend fun ApplicationCall.executeDeleteInstallation(
    installationRepository: InstallationRepository,
    session: WebAppSessionContext,
    item: InstallationAdminContext,
) {
    val deleted = try {
        installationRepository.softDeleteInstallation(
            id = item.id,
            actorType = ActorType.WEBAPP_SESSION,
            actorId = session.telegramUserId.toString(),
            action = "webapp_delete",
            metadataPatch = AuditMetadataPatch(
                actorUsername = session.username,
                actorFirstName = session.firstName,
                repoName = item.repoName,
                nickname = item.chatName,
                chatId = item.telegramChatId,
                topicId = item.telegramTopicId,
            ),
        )
    } catch (_: IllegalStateException) {
        respond(HttpStatusCode.InternalServerError, ErrorResponsePayload("Failed to record audit event"))
        return
    }

    if (!deleted) {
        respond(HttpStatusCode.NotFound, ErrorResponsePayload("Installation not found"))
        return
    }

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
                actorType = ActorType.WEBAPP_SESSION,
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

internal suspend fun canAccess(
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

internal suspend fun ApplicationCall.verifyAdminStatus(
    session: WebAppSessionContext,
    telegramService: TelegramService,
): Boolean {
    val chatId = session.telegramChatId?.takeIf { it < 0 } ?: return true
    val status = telegramApiCall { telegramService.chatMemberStatus(chatId, session.telegramUserId) }
        .getOrNull()
    if (!isTelegramAdmin(status)) {
        respond(HttpStatusCode.Forbidden, ErrorResponsePayload("Telegram group administrator permissions required"))
        return false
    }
    return verifyBotAdminStatus(chatId, telegramService)
}

internal suspend fun ApplicationCall.verifyBotAdminStatus(
    chatId: Long,
    telegramService: TelegramService,
): Boolean = when (telegramBotAdminStatus(chatId, telegramService)) {
    BotAdminStatus.ADMIN -> true
    BotAdminStatus.NOT_ADMIN -> {
        respond(
            HttpStatusCode.Forbidden,
            ErrorResponsePayload("Add Nuecagram to this group and make it an administrator before continuing."),
        )
        false
    }
    BotAdminStatus.UNAVAILABLE -> {
        respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponsePayload("Could not verify Nuecagram's group permissions. Please try again."),
        )
        false
    }
}

internal enum class BotAdminStatus { ADMIN, NOT_ADMIN, UNAVAILABLE }

internal enum class TargetAccess { ADMIN_ALLOWED, MEMBER_ALLOWED, USER_NOT_MEMBER, BOT_NOT_ADMIN, UNAVAILABLE }

internal suspend fun telegramBotAdminStatus(
    chatId: Long,
    telegramService: TelegramService,
): BotAdminStatus {
    val botUserId = telegramApiCall { telegramService.getMe()?.id }.getOrNull()
        ?: return BotAdminStatus.UNAVAILABLE
    val status = telegramApiCall { telegramService.chatMemberStatus(chatId, botUserId) }
        .fold({ it }, { return BotAdminStatus.UNAVAILABLE })
    return if (isTelegramAdmin(status)) BotAdminStatus.ADMIN else BotAdminStatus.NOT_ADMIN
}

internal suspend fun <T> telegramApiCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        Result.failure(exception)
    }

internal suspend fun ApplicationCall.authenticateWebAppSession(
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

internal suspend fun ApplicationCall.verifyCsrfHeader(
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

internal fun InstallationAdminContext.toResponsePayload() = InstallationResponsePayload(
    id = id.toString(),
    repoName = repoName,
    chatName = chatName,
    gitlabBaseUrl = gitlabBaseUrl,
    gitlabProjectId = gitlabProjectId,
    telegramChatId = telegramChatId,
    telegramTopicId = telegramTopicId,
    muted = muted,
)

internal fun net.raquezha.nuecagram.db.models.InstallationRecord.toAdminContext(muted: Boolean) =
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

internal fun ConfigWithSecrets.webhookEndpointUrl(): String = "${publicBaseUrl()}/webhook"

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

private fun webAppAsset(name: String, basePath: String, botUsername: String): String {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("webapp/$name")
        ?: error("Missing Web App asset: $name")
    return stream.bufferedReader().use { it.readText() }
        .replace("{{BASE_PATH}}", basePath)
        .replace("{{BOT_USERNAME}}", botUsername)
}
