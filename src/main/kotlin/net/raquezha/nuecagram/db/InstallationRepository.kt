package net.raquezha.nuecagram.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import net.raquezha.nuecagram.webhook.ChatDetails
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

@Suppress("TooManyFunctions")
class InstallationRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
    private val webhookStateRepository: WebhookStateRepository = WebhookStateRepository(databaseFactory),
    private val authSessionRepository: AuthSessionRepository = AuthSessionRepository(databaseFactory),
) {
    private companion object {
        const val UNKNOWN_REPOSITORY_NAME = "Unknown Repository"
        const val MAX_COLUMN_LENGTH = 255
    }

    private data class StoredSecretCandidate(
        val id: UUID,
        val installationId: UUID,
        val digest: ByteArray,
        val hash: String,
    )
    suspend fun createInstallation(
        repoName: String,
        chatName: String? = null,
        gitlabBaseUrl: String,
        gitlabProjectId: Long?,
        telegramChatId: Long,
        telegramTopicId: Long?,
    ): InstallationRecord {
        val normalizedRepoName = repoName.trim().take(MAX_COLUMN_LENGTH)
        require(normalizedRepoName.isNotBlank() && normalizedRepoName != UNKNOWN_REPOSITORY_NAME) {
            "repoName must be non-blank and not use the legacy fallback value"
        }
        val normalizedChatName = chatName?.trim()?.takeIf(String::isNotBlank)?.take(MAX_COLUMN_LENGTH)
        val normalizedGitlabUrl = gitlabBaseUrl.redactedUrl().trim().trimEnd('/').take(MAX_COLUMN_LENGTH)
        val installation = InstallationRecord(
            id = UUID.randomUUID(),
            repoName = normalizedRepoName,
            chatName = normalizedChatName,
            gitlabBaseUrl = normalizedGitlabUrl,
            gitlabProjectId = gitlabProjectId,
            telegramChatId = telegramChatId,
            telegramTopicId = telegramTopicId,
        )
        databaseFactory.dbTransaction {
            Installations.insert {
                it[id] = installation.id
                it[Installations.repoName] = installation.repoName
                it[Installations.chatName] = installation.chatName
                it[Installations.gitlabBaseUrl] = installation.gitlabBaseUrl
                it[Installations.gitlabProjectId] = installation.gitlabProjectId
                it[Installations.telegramChatId] = installation.telegramChatId
                it[Installations.telegramTopicId] = installation.telegramTopicId
            }
        }
        return installation
    }

    suspend fun createInstallation(
        gitlabBaseUrl: String,
        gitlabProjectId: Long?,
        telegramChatId: Long,
        telegramTopicId: Long?,
    ): InstallationRecord =
        createInstallation(
            repoName = deriveRepositoryName(gitlabBaseUrl, gitlabProjectId),
            gitlabBaseUrl = gitlabBaseUrl,
            gitlabProjectId = gitlabProjectId,
            telegramChatId = telegramChatId,
            telegramTopicId = telegramTopicId,
        )

    suspend fun issueWebhookSecret(
        installationId: UUID,
        expiresAt: Instant? = null,
    ): IssuedCredential = databaseFactory.dbTransaction {
        issueWebhookSecret(installationId, expiresAt)
    }

    suspend fun rotateWebhookSecret(
        installationId: UUID,
        graceUntil: Instant,
        expiresAt: Instant? = null,
    ): IssuedCredential = databaseFactory.dbTransaction {
        val issued = issueWebhookSecret(installationId, expiresAt)
        WebhookSecrets.update({
            (WebhookSecrets.installationId eq installationId) and
                (WebhookSecrets.id neq issued.id) and WebhookSecrets.revokedAt.isNull()
        }) {
            it[revokedAt] = graceUntil.databaseTime()
        }
        issued
    }

    suspend fun confirmWebhookSecret(
        secretId: UUID,
        confirmedAt: Instant = Instant.now(),
    ): Boolean = databaseFactory.dbTransaction {
        WebhookSecrets.update({
            (WebhookSecrets.id eq secretId) and WebhookSecrets.confirmedAt.isNull()
        }) {
            it[WebhookSecrets.confirmedAt] = confirmedAt.databaseTime()
        } == 1
    }

    suspend fun verifyWebhookSecret(
        raw: String,
        now: Instant = Instant.now(),
    ): VerifiedSecret? = databaseFactory.dbTransaction {
        val databaseNow = now.databaseTime()
        WebhookSecrets.selectAll().where {
            (WebhookSecrets.secretDigest eq CredentialCodec.digest(raw)) and
                (WebhookSecrets.revokedAt.isNull() or (WebhookSecrets.revokedAt greater databaseNow)) and
                (WebhookSecrets.expiresAt.isNull() or (WebhookSecrets.expiresAt greater databaseNow))
        }.mapNotNull { row ->
            val hash = row[WebhookSecrets.secretHash] ?: return@mapNotNull null
            StoredSecretCandidate(
                row[WebhookSecrets.id],
                row[WebhookSecrets.installationId],
                row[WebhookSecrets.secretDigest],
                hash,
            )
        }.firstOrNull { CredentialCodec.matches(raw, it.digest, it.hash) }
            ?.let { VerifiedSecret(it.id, it.installationId) }
    }

    suspend fun resolveWebhookInstallation(
        raw: String,
        now: Instant = Instant.now(),
    ): WebhookInstallationResult {
        val verified = verifyWebhookSecret(raw, now) ?: return WebhookInstallationResult.NotFound
        return databaseFactory.dbTransaction {
            val row = installationWithMuteQuery(verified.installationId, includeDeleted = true).firstOrNull()
                ?: return@dbTransaction WebhookInstallationResult.NotFound

            if (row[Installations.deletedAt] != null) {
                return@dbTransaction WebhookInstallationResult.SoftDeleted
            }

            WebhookInstallationResult.Active(
                InstallationContext(
                    verified.secretId,
                    verified.installationId,
                    ChatDetails(
                        row[Installations.telegramChatId].toString(),
                        row[Installations.telegramTopicId]?.toString(),
                    ),
                    row.getOrNull(MuteStates.muted) ?: false,
                ),
            )
        }
    }

    private fun deriveRepositoryName(gitlabBaseUrl: String, gitlabProjectId: Long?): String {
        if (gitlabProjectId != null) return "Project #$gitlabProjectId"
        val cleanUrl = gitlabBaseUrl.redactedUrl()
        return cleanUrl.trim()
            .substringAfter("://", cleanUrl.trim())
            .substringAfter('/', "")
            .trim('/')
            .takeIf(String::isNotBlank)
            ?: cleanUrl.trim().trim('/').takeIf(String::isNotBlank)
            ?: UNKNOWN_REPOSITORY_NAME
    }

    private fun destinationId(chatId: Long, topicId: Long?): String = "$chatId:${topicId ?: 0}"

    suspend fun recordTelegramUpdate(updateId: Long): Boolean = databaseFactory.dbTransaction {
        TelegramUpdates.insertIgnore { it[TelegramUpdates.updateId] = updateId }.insertedCount == 1
    }

    suspend fun upsertTelegramPrivateChat(userId: Long, chatId: Long) {
        databaseFactory.dbTransaction {
            TelegramPrivateChats.upsert(TelegramPrivateChats.telegramUserId) {
                it[telegramUserId] = userId
                it[telegramChatId] = chatId
                it[startedAt] = Instant.now().databaseTime()
            }
        }
    }

    suspend fun telegramPrivateChatId(userId: Long): Long? = databaseFactory.dbTransaction {
        TelegramPrivateChats.selectAll()
            .where { TelegramPrivateChats.telegramUserId eq userId }
            .firstOrNull()?.get(TelegramPrivateChats.telegramChatId)
    }

    suspend fun upsertKnownTelegramDestination(
        chatId: Long,
        topicId: Long?,
        chatTitle: String?,
    ) {
        if (chatId >= 0) return
        val destinationId = destinationId(chatId, topicId)
        databaseFactory.dbTransaction {
            KnownTelegramDestinations.upsert(KnownTelegramDestinations.id) {
                it[KnownTelegramDestinations.id] = destinationId
                it[KnownTelegramDestinations.telegramChatId] = chatId
                it[KnownTelegramDestinations.telegramTopicId] = topicId
                it[KnownTelegramDestinations.chatTitle] =
                    chatTitle?.trim()?.takeIf(String::isNotBlank)?.take(MAX_COLUMN_LENGTH)
                it[KnownTelegramDestinations.lastSeenAt] = Instant.now().databaseTime()
            }
        }
    }

    suspend fun knownTelegramDestinations(): List<KnownTelegramDestination> = databaseFactory.dbTransaction {
        KnownTelegramDestinations.selectAll()
            .orderBy(KnownTelegramDestinations.lastSeenAt to SortOrder.DESC)
            .map {
                KnownTelegramDestination(
                    id = it[KnownTelegramDestinations.id],
                    telegramChatId = it[KnownTelegramDestinations.telegramChatId],
                    telegramTopicId = it[KnownTelegramDestinations.telegramTopicId],
                    chatTitle = it[KnownTelegramDestinations.chatTitle],
                )
            }
    }

    suspend fun installationAdminContext(installationId: UUID): InstallationAdminContext? =
        databaseFactory.dbTransaction {
            installationWithMuteQuery(installationId).firstOrNull()?.toAdminContext()
        }

    suspend fun listInstallationsForContext(
        chatId: Long?,
        topicId: Long?,
    ): List<InstallationAdminContext> = databaseFactory.dbTransaction {
        val query = installationWithMuteQuery()
        if (chatId != null) {
            query.andWhere { Installations.telegramChatId eq chatId }
            if (topicId != null) {
                query.andWhere { Installations.telegramTopicId eq topicId }
            }
        }
        query.map { it.toAdminContext() }
    }

    suspend fun recordInstallationAdmin(
        installationId: UUID,
        telegramUserId: Long,
        confirmedAt: Instant = Instant.now(),
    ) {
        databaseFactory.dbTransaction {
            InstallationAdmins.upsert(InstallationAdmins.installationId, InstallationAdmins.telegramUserId) {
                it[InstallationAdmins.installationId] = installationId
                it[InstallationAdmins.telegramUserId] = telegramUserId
                it[InstallationAdmins.confirmedAt] = confirmedAt.databaseTime()
            }
        }
    }

    suspend fun installationsForAdmin(telegramUserId: Long): List<InstallationAdminContext> =
        databaseFactory.dbTransaction {
            Installations.join(
                InstallationAdmins,
                JoinType.INNER,
                Installations.id,
                InstallationAdmins.installationId,
            ).join(
                MuteStates,
                JoinType.LEFT,
                Installations.id,
                MuteStates.installationId,
            ).selectAll()
                .where {
                    (InstallationAdmins.telegramUserId eq telegramUserId) and
                        (Installations.deletedAt.isNull())
                }
                .orderBy(InstallationAdmins.confirmedAt to SortOrder.DESC)
                .map { it.toAdminContext() }
        }

    suspend fun findInstallationByQuery(
        rawQuery: String,
        chatId: Long? = null,
        topicId: Long? = null,
    ): InstallationAdminContext? = databaseFactory.dbTransaction {
        val queryStr = rawQuery.trim().lowercase()
        if (queryStr.isBlank()) return@dbTransaction null
        val uuid = runCatching { UUID.fromString(queryStr) }.getOrNull()
        if (uuid != null) {
            val query = installationWithMuteQuery(uuid)
            if (chatId != null) {
                query.andWhere { Installations.telegramChatId eq chatId }
            }
            if (topicId != null) {
                query.andWhere { Installations.telegramTopicId eq topicId }
            }
            return@dbTransaction query.firstOrNull()?.toAdminContext()
        }
        val query = installationWithMuteQuery()
        if (chatId != null) {
            query.andWhere { Installations.telegramChatId eq chatId }
        }
        if (topicId != null) {
            query.andWhere { Installations.telegramTopicId eq topicId }
        }
        query.map { it.toAdminContext() }
            .firstOrNull { inst ->
                inst.id.toString().lowercase().startsWith(queryStr) ||
                    inst.gitlabProjectId?.toString() == queryStr ||
                    inst.gitlabBaseUrl.lowercase().contains(queryStr) ||
                    inst.repoName.lowercase().contains(queryStr) ||
                    inst.chatName?.lowercase()?.contains(queryStr) == true
            }
    }

    suspend fun updateIdentity(
        installationId: UUID,
        repoName: String,
        chatName: String?,
    ): Boolean {
        val normalizedRepoName = repoName.trim().take(MAX_COLUMN_LENGTH)
        require(normalizedRepoName.isNotBlank() && normalizedRepoName != UNKNOWN_REPOSITORY_NAME) {
            "repoName must be non-blank and not use the legacy fallback value"
        }
        val normalizedChatName = chatName?.trim()?.takeIf(String::isNotBlank)?.take(MAX_COLUMN_LENGTH)
        return databaseFactory.dbTransaction {
            Installations.update({ Installations.id eq installationId }) {
                it[Installations.repoName] = normalizedRepoName
                it[Installations.chatName] = normalizedChatName
            } == 1
        }
    }

    suspend fun setMuted(installationId: UUID, muted: Boolean) {
        databaseFactory.dbTransaction {
            MuteStates.upsert(MuteStates.installationId) {
                it[MuteStates.installationId] = installationId
                it[MuteStates.muted] = muted
                it[updatedAt] = Instant.now().databaseTime()
            }
        }
    }

    // Delegated Auth & Session Operations
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
        authSessionRepository.cleanupExpiredWebhookSecrets(now)

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

    private fun Transaction.issueWebhookSecret(
        installationId: UUID,
        expiresAt: Instant?,
    ): IssuedCredential {
        val id = UUID.randomUUID()
        val (raw, stored) = CredentialCodec.issueCredential()
        WebhookSecrets.insert {
            it[WebhookSecrets.id] = id
            it[WebhookSecrets.installationId] = installationId
            it[secretDigest] = stored.digest
            it[secretHash] = stored.hash
            it[WebhookSecrets.expiresAt] = expiresAt?.databaseTime()
        }
        return IssuedCredential(id, installationId, raw)
    }

    suspend fun softDeleteInstallation(id: UUID): Boolean = databaseFactory.dbTransaction {
        val count = Installations.update({ (Installations.id eq id) and (Installations.deletedAt.isNull()) }) {
            it[deletedAt] = Instant.now().databaseTime()
        }
        count > 0
    }

    suspend fun cleanupStaleMrAndPushStates(now: Instant = Instant.now(), maxAgeDays: Long = 30): Int =
        webhookStateRepository.cleanupStaleMrAndPushStates(now, maxAgeDays)

    private fun installationWithMuteQuery(
        installationId: UUID? = null,
        includeDeleted: Boolean = false,
    ): Query {
        val join = Installations.join(
            MuteStates,
            JoinType.LEFT,
            Installations.id,
            MuteStates.installationId,
        )
        val query = join.selectAll()
        if (!includeDeleted) {
            query.andWhere { Installations.deletedAt.isNull() }
        }
        if (installationId != null) query.andWhere { Installations.id eq installationId }
        return query
    }

    private fun ResultRow.toAdminContext() = InstallationAdminContext(
        id = this[Installations.id],
        repoName = this[Installations.repoName],
        chatName = this[Installations.chatName],
        gitlabBaseUrl = this[Installations.gitlabBaseUrl],
        gitlabProjectId = this[Installations.gitlabProjectId],
        telegramChatId = this[Installations.telegramChatId],
        telegramTopicId = this[Installations.telegramTopicId],
        muted = getOrNull(MuteStates.muted) ?: false,
    )

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

private fun Instant.databaseTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
