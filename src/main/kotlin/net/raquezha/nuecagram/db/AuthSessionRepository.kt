package net.raquezha.nuecagram.db

import java.time.Instant
import java.util.UUID
import net.raquezha.nuecagram.db.models.*

@Suppress("TooManyFunctions")
class AuthSessionRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
    private val managementSessionRepository: ManagementSessionRepository = ManagementSessionRepository(databaseFactory),
    private val platformAdminSessionRepository: PlatformAdminSessionRepository =
        PlatformAdminSessionRepository(databaseFactory),
    private val webAppSessionRepository: WebAppSessionRepository = WebAppSessionRepository(databaseFactory),
    private val auditEventRepository: AuditEventRepository = AuditEventRepository(databaseFactory),
) {
    suspend fun issueManagementLink(
        installationId: UUID,
        expiresAt: Instant,
    ): IssuedCredential = managementSessionRepository.issueManagementLink(installationId, expiresAt)

    suspend fun consumeManagementLink(
        raw: String,
        now: Instant = Instant.now(),
    ): ConsumedManagementLink? = managementSessionRepository.consumeManagementLink(raw, now)

    suspend fun exchangeManagementLinkForSession(
        raw: String,
        sessionExpiresAt: Instant,
        now: Instant = Instant.now(),
    ): IssuedManagementSession? =
        managementSessionRepository.exchangeManagementLinkForSession(raw, sessionExpiresAt, now)

    suspend fun verifyManagementSession(
        raw: String,
        now: Instant = Instant.now(),
    ): ManagementSessionContext? = managementSessionRepository.verifyManagementSession(raw, now)

    fun verifyManagementCsrf(session: ManagementSessionContext, raw: String): Boolean =
        managementSessionRepository.verifyManagementCsrf(session, raw)

    suspend fun deleteManagementSession(id: UUID): Boolean =
        managementSessionRepository.deleteManagementSession(id)

    suspend fun issuePlatformAdminSession(expiresAt: Instant): IssuedPlatformAdminSession =
        platformAdminSessionRepository.issuePlatformAdminSession(expiresAt)

    suspend fun verifyPlatformAdminSession(
        raw: String,
        now: Instant = Instant.now(),
    ): PlatformAdminSessionContext? = platformAdminSessionRepository.verifyPlatformAdminSession(raw, now)

    fun verifyPlatformAdminCsrf(session: PlatformAdminSessionContext, raw: String): Boolean =
        platformAdminSessionRepository.verifyPlatformAdminCsrf(session, raw)

    suspend fun deletePlatformAdminSession(id: UUID): Boolean =
        platformAdminSessionRepository.deletePlatformAdminSession(id)

    suspend fun issueLaunchNonce(
        telegramChatId: Long,
        telegramTopicId: Long?,
        telegramUserId: Long,
        expiresAt: Instant,
    ): IssuedCredential =
        webAppSessionRepository.issueLaunchNonce(telegramChatId, telegramTopicId, telegramUserId, expiresAt)

    suspend fun consumeLaunchNonce(
        raw: String,
        telegramUserId: Long,
        now: Instant = Instant.now(),
    ): LaunchNonceContext? = webAppSessionRepository.consumeLaunchNonce(raw, telegramUserId, now)

    suspend fun issueWebAppSession(
        telegramUserId: Long,
        telegramChatId: Long?,
        telegramTopicId: Long?,
        username: String?,
        firstName: String?,
        expiresAt: Instant,
    ): IssuedWebAppSession = webAppSessionRepository.issueWebAppSession(
        telegramUserId,
        telegramChatId,
        telegramTopicId,
        username,
        firstName,
        expiresAt,
    )

    suspend fun verifyWebAppSession(
        raw: String,
        now: Instant = Instant.now(),
    ): WebAppSessionContext? = webAppSessionRepository.verifyWebAppSession(raw, now)

    fun verifyWebAppCsrf(session: WebAppSessionContext, raw: String): Boolean =
        webAppSessionRepository.verifyWebAppCsrf(session, raw)

    suspend fun deleteWebAppSession(id: UUID): Boolean =
        webAppSessionRepository.deleteWebAppSession(id)

    suspend fun cleanupExpiredWebAppSessions(now: Instant = Instant.now()): Int =
        webAppSessionRepository.cleanupExpiredWebAppSessions(now)

    suspend fun cleanupExpiredManagementLinks(now: Instant = Instant.now()): Int =
        managementSessionRepository.cleanupExpiredManagementLinks(now)

    suspend fun cleanupExpiredManagementSessions(now: Instant = Instant.now()): Int =
        managementSessionRepository.cleanupExpiredManagementSessions(now)

    suspend fun cleanupExpiredPlatformAdminSessions(now: Instant = Instant.now()): Int =
        platformAdminSessionRepository.cleanupExpiredPlatformAdminSessions(now)

    suspend fun writeAuditEvent(
        installationId: UUID?,
        actorType: String,
        actorId: String?,
        action: String,
        metadataJson: String = "{}",
        metadataPatch: AuditMetadataPatch = AuditMetadataPatch(),
    ): Boolean = auditEventRepository.writeAuditEvent(
        installationId = installationId,
        actorType = actorType,
        actorId = actorId,
        action = action,
        metadataJson = metadataJson,
        metadataPatch = metadataPatch,
    )
}
