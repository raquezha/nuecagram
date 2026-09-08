package net.raquezha.nuecagram.db

import java.time.Instant
import java.util.UUID
import net.raquezha.nuecagram.db.models.*

@Suppress("TooManyFunctions")
class InstallationRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
    private val webhookStateRepository: WebhookStateRepository = WebhookStateRepository(databaseFactory),
    private val authSessionRepository: AuthSessionRepository = AuthSessionRepository(databaseFactory),
    private val webhookSecretRepository: WebhookSecretRepository = WebhookSecretRepository(databaseFactory),
    private val telegramDestinationRepository: TelegramDestinationRepository =
        TelegramDestinationRepository(databaseFactory),
    private val lifecycleRepository: InstallationLifecycleRepository =
        InstallationLifecycleRepository(databaseFactory, webhookSecretRepository),
    private val adminRepository: InstallationAdminRepository =
        InstallationAdminRepository(databaseFactory, lifecycleRepository),
) {
    suspend fun createInstallation(
        repoName: String,
        chatName: String? = null,
        gitlabBaseUrl: String,
        gitlabProjectId: Long?,
        telegramChatId: Long,
        telegramTopicId: Long?,
    ): InstallationRecord = lifecycleRepository.createInstallation(
        repoName = repoName,
        chatName = chatName,
        gitlabBaseUrl = gitlabBaseUrl,
        gitlabProjectId = gitlabProjectId,
        telegramChatId = telegramChatId,
        telegramTopicId = telegramTopicId,
    )

    suspend fun createInstallation(
        gitlabBaseUrl: String,
        gitlabProjectId: Long?,
        telegramChatId: Long,
        telegramTopicId: Long?,
    ): InstallationRecord = lifecycleRepository.createInstallation(
        gitlabBaseUrl = gitlabBaseUrl,
        gitlabProjectId = gitlabProjectId,
        telegramChatId = telegramChatId,
        telegramTopicId = telegramTopicId,
    )

    suspend fun issueWebhookSecret(
        installationId: UUID,
        expiresAt: Instant? = null,
    ): IssuedCredential = webhookSecretRepository.issueWebhookSecret(installationId, expiresAt)

    suspend fun rotateWebhookSecret(
        installationId: UUID,
        graceUntil: Instant,
        expiresAt: Instant? = null,
    ): IssuedCredential = webhookSecretRepository.rotateWebhookSecret(installationId, graceUntil, expiresAt)

    suspend fun confirmWebhookSecret(
        secretId: UUID,
        confirmedAt: Instant = Instant.now(),
    ): Boolean = webhookSecretRepository.confirmWebhookSecret(secretId, confirmedAt)

    suspend fun verifyWebhookSecret(
        raw: String,
        now: Instant = Instant.now(),
    ): VerifiedSecret? = webhookSecretRepository.verifyWebhookSecret(raw, now)

    suspend fun resolveWebhookInstallation(
        raw: String,
        now: Instant = Instant.now(),
    ): WebhookInstallationResult = lifecycleRepository.resolveWebhookInstallation(raw, now)

    suspend fun recordTelegramUpdate(updateId: Long): Boolean =
        telegramDestinationRepository.recordTelegramUpdate(updateId)

    suspend fun upsertTelegramPrivateChat(userId: Long, chatId: Long) {
        telegramDestinationRepository.upsertTelegramPrivateChat(userId, chatId)
    }

    suspend fun telegramPrivateChatId(userId: Long): Long? =
        telegramDestinationRepository.telegramPrivateChatId(userId)

    suspend fun upsertKnownTelegramDestination(
        chatId: Long,
        topicId: Long?,
        chatTitle: String?,
    ) {
        telegramDestinationRepository.upsertKnownTelegramDestination(chatId, topicId, chatTitle)
    }

    suspend fun knownTelegramDestinations(): List<KnownTelegramDestination> =
        telegramDestinationRepository.knownTelegramDestinations()

    suspend fun installationAdminContext(installationId: UUID): InstallationAdminContext? =
        adminRepository.installationAdminContext(installationId)

    suspend fun listInstallationsForContext(
        chatId: Long?,
        topicId: Long?,
    ): List<InstallationAdminContext> = adminRepository.listInstallationsForContext(chatId, topicId)

    suspend fun recordInstallationAdmin(
        installationId: UUID,
        telegramUserId: Long,
        confirmedAt: Instant = Instant.now(),
    ) {
        adminRepository.recordInstallationAdmin(installationId, telegramUserId, confirmedAt)
    }

    suspend fun installationsForAdmin(telegramUserId: Long): List<InstallationAdminContext> =
        adminRepository.installationsForAdmin(telegramUserId)

    suspend fun findInstallationByQuery(
        rawQuery: String,
        chatId: Long? = null,
        topicId: Long? = null,
    ): InstallationAdminContext? = adminRepository.findInstallationByQuery(rawQuery, chatId, topicId)

    suspend fun updateIdentity(
        installationId: UUID,
        repoName: String,
        chatName: String?,
    ): Boolean = lifecycleRepository.updateIdentity(installationId, repoName, chatName)

    suspend fun setMuted(installationId: UUID, muted: Boolean) {
        lifecycleRepository.setMuted(installationId, muted)
    }

    suspend fun issueManagementLink(
        installationId: UUID,
        expiresAt: Instant,
    ): IssuedCredential = authSessionRepository.issueManagementLink(installationId, expiresAt)

    suspend fun consumeManagementLink(
        raw: String,
        now: Instant = Instant.now(),
    ): ConsumedManagementLink? = authSessionRepository.consumeManagementLink(raw, now)

    suspend fun exchangeManagementLinkForSession(
        raw: String,
        sessionExpiresAt: Instant,
        now: Instant = Instant.now(),
    ): IssuedManagementSession? = authSessionRepository.exchangeManagementLinkForSession(raw, sessionExpiresAt, now)

    suspend fun verifyManagementSession(
        raw: String,
        now: Instant = Instant.now(),
    ): ManagementSessionContext? = authSessionRepository.verifyManagementSession(raw, now)

    fun verifyManagementCsrf(session: ManagementSessionContext, raw: String): Boolean =
        authSessionRepository.verifyManagementCsrf(session, raw)

    suspend fun deleteManagementSession(id: UUID): Boolean = authSessionRepository.deleteManagementSession(id)

    suspend fun issuePlatformAdminSession(expiresAt: Instant): IssuedPlatformAdminSession =
        authSessionRepository.issuePlatformAdminSession(expiresAt)

    suspend fun verifyPlatformAdminSession(
        raw: String,
        now: Instant = Instant.now(),
    ): PlatformAdminSessionContext? = authSessionRepository.verifyPlatformAdminSession(raw, now)

    fun verifyPlatformAdminCsrf(session: PlatformAdminSessionContext, raw: String): Boolean =
        authSessionRepository.verifyPlatformAdminCsrf(session, raw)

    suspend fun deletePlatformAdminSession(id: UUID): Boolean = authSessionRepository.deletePlatformAdminSession(id)

    suspend fun issueLaunchNonce(
        telegramChatId: Long,
        telegramTopicId: Long?,
        telegramUserId: Long,
        expiresAt: Instant,
    ): IssuedCredential = authSessionRepository.issueLaunchNonce(
        telegramChatId = telegramChatId,
        telegramTopicId = telegramTopicId,
        telegramUserId = telegramUserId,
        expiresAt = expiresAt,
    )

    suspend fun consumeLaunchNonce(
        raw: String,
        telegramUserId: Long,
        now: Instant = Instant.now(),
    ): LaunchNonceContext? = authSessionRepository.consumeLaunchNonce(raw, telegramUserId, now)

    suspend fun issueWebAppSession(
        telegramUserId: Long,
        telegramChatId: Long?,
        telegramTopicId: Long?,
        username: String?,
        firstName: String?,
        expiresAt: Instant,
    ): IssuedWebAppSession = authSessionRepository.issueWebAppSession(
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
    ): WebAppSessionContext? = authSessionRepository.verifyWebAppSession(raw, now)

    fun verifyWebAppCsrf(session: WebAppSessionContext, raw: String): Boolean =
        authSessionRepository.verifyWebAppCsrf(session, raw)

    suspend fun deleteWebAppSession(id: UUID): Boolean = authSessionRepository.deleteWebAppSession(id)

    suspend fun cleanupExpiredWebAppSessions(now: Instant = Instant.now()): Int =
        authSessionRepository.cleanupExpiredWebAppSessions(now)

    suspend fun cleanupExpiredManagementLinks(now: Instant = Instant.now()): Int =
        authSessionRepository.cleanupExpiredManagementLinks(now)

    suspend fun cleanupExpiredManagementSessions(now: Instant = Instant.now()): Int =
        authSessionRepository.cleanupExpiredManagementSessions(now)

    suspend fun cleanupExpiredPlatformAdminSessions(now: Instant = Instant.now()): Int =
        authSessionRepository.cleanupExpiredPlatformAdminSessions(now)

    suspend fun cleanupExpiredWebhookSecrets(now: Instant = Instant.now()): Int =
        webhookSecretRepository.cleanupExpiredWebhookSecrets(now)

    suspend fun writeAuditEvent(
        installationId: UUID?,
        actorType: String,
        actorId: String?,
        action: String,
        metadataJson: String = "{}",
        metadataPatch: AuditMetadataPatch = AuditMetadataPatch(),
    ): Boolean = authSessionRepository.writeAuditEvent(
        installationId = installationId,
        actorType = actorType,
        actorId = actorId,
        action = action,
        metadataJson = metadataJson,
        metadataPatch = metadataPatch,
    )

    suspend fun softDeleteInstallation(id: UUID): Boolean = lifecycleRepository.softDeleteInstallation(id)

    suspend fun cleanupStaleMrAndPushStates(now: Instant = Instant.now(), maxAgeDays: Long = 30): Int =
        webhookStateRepository.cleanupStaleMrAndPushStates(now, maxAgeDays)

    suspend fun upsertMrParticipants(
        installationId: UUID,
        projectId: Long,
        mrIid: Long,
        authorUsername: String?,
        reviewerUsernames: List<String>,
    ) {
        webhookStateRepository.upsertMrParticipants(
            installationId = installationId,
            projectId = projectId,
            mrIid = mrIid,
            authorUsername = authorUsername,
            reviewerUsernames = reviewerUsernames,
        )
    }

    suspend fun getMrParticipants(
        installationId: UUID,
        projectId: Long,
        mrIid: Long,
    ): MrParticipants? = webhookStateRepository.getMrParticipants(installationId, projectId, mrIid)

    suspend fun upsertActiveMr(
        installationId: UUID,
        projectId: Long,
        sourceBranch: String,
        mrIid: Long,
        lastCommitSha: String? = null,
        targetProjectId: Long? = null,
    ) {
        webhookStateRepository.upsertActiveMr(
            installationId = installationId,
            projectId = projectId,
            sourceBranch = sourceBranch,
            mrIid = mrIid,
            lastCommitSha = lastCommitSha,
            targetProjectId = targetProjectId,
        )
    }

    suspend fun getActiveMrForBranch(
        installationId: UUID,
        projectId: Long,
        sourceBranch: String,
    ): ActiveMergeRequest? = webhookStateRepository.getActiveMrForBranch(installationId, projectId, sourceBranch)

    suspend fun clearActiveMr(
        installationId: UUID,
        projectId: Long,
        sourceBranch: String,
    ) {
        webhookStateRepository.clearActiveMr(installationId, projectId, sourceBranch)
    }

    suspend fun upsertLatestPushSha(
        installationId: UUID,
        projectId: Long,
        branch: String,
        latestPushSha: String,
    ) {
        webhookStateRepository.upsertLatestPushSha(installationId, projectId, branch, latestPushSha)
    }

    suspend fun getLatestPushSha(
        installationId: UUID,
        projectId: Long,
        branch: String,
    ): String? = webhookStateRepository.getLatestPushSha(installationId, projectId, branch)

    suspend fun tryRecordProcessedEvent(
        eventUuid: String?,
        installationId: UUID?,
        eventType: String,
    ): Boolean = webhookStateRepository.tryRecordProcessedEvent(eventUuid, installationId, eventType)

    suspend fun clearProcessedWebhookEvents() {
        webhookStateRepository.clearProcessedWebhookEvents()
    }
}
