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
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

data class InstallationRecord(
    val id: UUID,
    val gitlabBaseUrl: String,
    val gitlabProjectId: Long?,
    val telegramChatId: Long,
    val telegramTopicId: Long?,
)

data class IssuedCredential(val id: UUID, val installationId: UUID, val raw: String)
data class VerifiedSecret(val secretId: UUID, val installationId: UUID)
data class ConsumedManagementLink(val linkId: UUID, val installationId: UUID)

data class IssuedManagementSession(
    val sessionId: UUID,
    val installationId: UUID,
    val raw: String,
    val csrf: String,
)

data class ManagementSessionContext(
    val sessionId: UUID,
    val installationId: UUID,
    val csrfDigest: ByteArray?,
    val csrfHash: String?,
)

data class IssuedPlatformAdminSession(val id: UUID, val raw: String, val csrf: String)
data class PlatformAdminSessionContext(val id: UUID, val csrfDigest: ByteArray, val csrfHash: String)

data class IssuedWebAppSession(
    val sessionId: UUID,
    val telegramUserId: Long,
    val telegramChatId: Long?,
    val telegramTopicId: Long?,
    val raw: String,
    val csrf: String,
)

data class WebAppSessionContext(
    val sessionId: UUID,
    val telegramUserId: Long,
    val telegramChatId: Long?,
    val telegramTopicId: Long?,
    val csrfDigest: ByteArray,
    val csrfHash: String,
)

data class LaunchNonceContext(
    val id: UUID,
    val telegramChatId: Long,
    val telegramTopicId: Long?,
    val telegramUserId: Long,
)

data class PlatformAdminAuditRecord(val installationId: UUID?, val action: String, val createdAt: Instant)

data class PlatformAdminInstallationsPage(
    val items: List<InstallationAdminContext>,
    val totalCount: Long,
)

data class PlatformAdminAuditPage(
    val items: List<PlatformAdminAuditRecord>,
    val totalCount: Long,
)

data class MrParticipants(
    val authorUsername: String?,
    val reviewerUsernames: List<String>,
)

data class InstallationContext(
    val secretId: UUID,
    val installationId: UUID,
    val chatDetails: ChatDetails,
    val muted: Boolean,
)

data class InstallationAdminContext(
    val id: UUID,
    val gitlabBaseUrl: String,
    val gitlabProjectId: Long?,
    val telegramChatId: Long,
    val telegramTopicId: Long?,
    val muted: Boolean,
)

private data class StoredCandidate(
    val id: UUID,
    val installationId: UUID,
    val digest: ByteArray,
    val hash: String,
)

@Suppress("TooManyFunctions")
class InstallationRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
) {
    suspend fun createInstallation(
        gitlabBaseUrl: String,
        gitlabProjectId: Long?,
        telegramChatId: Long,
        telegramTopicId: Long?,
    ): InstallationRecord {
        val installation = InstallationRecord(
            UUID.randomUUID(),
            gitlabBaseUrl,
            gitlabProjectId,
            telegramChatId,
            telegramTopicId,
        )
        databaseFactory.dbTransaction {
            Installations.insert {
                it[id] = installation.id
                it[Installations.gitlabBaseUrl] = installation.gitlabBaseUrl
                it[Installations.gitlabProjectId] = installation.gitlabProjectId
                it[Installations.telegramChatId] = installation.telegramChatId
                it[Installations.telegramTopicId] = installation.telegramTopicId
            }
        }
        return installation
    }

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
            StoredCandidate(
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
    ): InstallationContext? {
        val verified = verifyWebhookSecret(raw, now) ?: return null
        return databaseFactory.dbTransaction {
            installationWithMuteQuery(verified.installationId).firstOrNull()?.let { row ->
                InstallationContext(
                    verified.secretId,
                    verified.installationId,
                    ChatDetails(
                        row[Installations.telegramChatId].toString(),
                        row[Installations.telegramTopicId]?.toString(),
                    ),
                    row.getOrNull(MuteStates.muted) ?: false,
                )
            }
        }
    }

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
                .where { InstallationAdmins.telegramUserId eq telegramUserId }
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
                    inst.gitlabBaseUrl.lowercase().contains(queryStr)
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

    suspend fun issueManagementLink(
        installationId: UUID,
        expiresAt: Instant,
    ): IssuedCredential = databaseFactory.dbTransaction {
        val id = UUID.randomUUID()
        val (raw, stored) = CredentialCodec.issueCredential()
        ManagementLinks.insert {
            it[ManagementLinks.id] = id
            it[ManagementLinks.installationId] = installationId
            it[tokenDigest] = stored.digest
            it[tokenHash] = stored.hash
            it[ManagementLinks.expiresAt] = expiresAt.databaseTime()
        }
        IssuedCredential(id, installationId, raw)
    }

    suspend fun consumeManagementLink(
        raw: String,
        now: Instant = Instant.now(),
    ): ConsumedManagementLink? = databaseFactory.dbTransaction {
        val match = managementLinkCandidate(raw, now) ?: return@dbTransaction null
        val databaseNow = now.databaseTime()
        val consumed = ManagementLinks.update({
            (ManagementLinks.id eq match.id) and ManagementLinks.consumedAt.isNull() and
                (ManagementLinks.expiresAt greater databaseNow)
        }) {
            it[consumedAt] = databaseNow
        } == 1
        match.takeIf { consumed }?.let { ConsumedManagementLink(it.id, it.installationId) }
    }

    suspend fun exchangeManagementLinkForSession(
        raw: String,
        sessionExpiresAt: Instant,
        now: Instant = Instant.now(),
    ): IssuedManagementSession? = databaseFactory.dbTransaction {
        val match = managementLinkCandidate(raw, now) ?: return@dbTransaction null
        val databaseNow = now.databaseTime()
        val consumed = ManagementLinks.update({
            (ManagementLinks.id eq match.id) and ManagementLinks.consumedAt.isNull() and
                (ManagementLinks.expiresAt greater databaseNow)
        }) {
            it[consumedAt] = databaseNow
        } == 1
        if (!consumed) return@dbTransaction null

        val sessionId = UUID.randomUUID()
        val (sessionRaw, stored) = CredentialCodec.issueCredential()
        val (csrf, storedCsrf) = CredentialCodec.issueCredential()
        ManagementSessions.insert {
            it[id] = sessionId
            it[installationId] = match.installationId
            it[tokenDigest] = stored.digest
            it[tokenHash] = stored.hash
            it[expiresAt] = sessionExpiresAt.databaseTime()
            it[csrfDigest] = storedCsrf.digest
            it[csrfHash] = storedCsrf.hash
        }
        IssuedManagementSession(sessionId, match.installationId, sessionRaw, csrf)
    }

    suspend fun verifyManagementSession(
        raw: String,
        now: Instant = Instant.now(),
    ): ManagementSessionContext? = databaseFactory.dbTransaction {
        ManagementSessions.selectAll().where {
            (ManagementSessions.expiresAt greater now.databaseTime()) and
                (ManagementSessions.tokenDigest eq CredentialCodec.digest(raw))
        }.firstOrNull { row ->
            CredentialCodec.matches(raw, row[ManagementSessions.tokenDigest], row[ManagementSessions.tokenHash])
        }?.let { row ->
            ManagementSessionContext(
                row[ManagementSessions.id],
                row[ManagementSessions.installationId],
                row[ManagementSessions.csrfDigest],
                row[ManagementSessions.csrfHash],
            )
        }
    }

    fun verifyManagementCsrf(session: ManagementSessionContext, raw: String): Boolean =
        session.csrfDigest?.let { digest ->
            session.csrfHash?.let { hash -> CredentialCodec.matches(raw, digest, hash) }
        } ?: false

    suspend fun deleteManagementSession(id: UUID): Boolean = databaseFactory.dbTransaction {
        ManagementSessions.deleteWhere { ManagementSessions.id eq id } == 1
    }

    suspend fun issuePlatformAdminSession(expiresAt: Instant): IssuedPlatformAdminSession {
        val id = UUID.randomUUID()
        val (raw, stored) = CredentialCodec.issueCredential()
        val (csrf, storedCsrf) = CredentialCodec.issueCredential()
        databaseFactory.dbTransaction {
            PlatformAdminSessions.insert {
                it[PlatformAdminSessions.id] = id
                it[tokenDigest] = stored.digest
                it[tokenHash] = stored.hash
                it[csrfDigest] = storedCsrf.digest
                it[csrfHash] = storedCsrf.hash
                it[PlatformAdminSessions.expiresAt] = expiresAt.databaseTime()
            }
        }
        return IssuedPlatformAdminSession(id, raw, csrf)
    }

    suspend fun verifyPlatformAdminSession(
        raw: String,
        now: Instant = Instant.now(),
    ): PlatformAdminSessionContext? = databaseFactory.dbTransaction {
        PlatformAdminSessions.selectAll().where {
            (PlatformAdminSessions.expiresAt greater now.databaseTime()) and
                (PlatformAdminSessions.tokenDigest eq CredentialCodec.digest(raw))
        }.firstOrNull { row ->
            CredentialCodec.matches(
                raw,
                row[PlatformAdminSessions.tokenDigest],
                row[PlatformAdminSessions.tokenHash],
            )
        }?.let { row ->
            PlatformAdminSessionContext(
                row[PlatformAdminSessions.id],
                row[PlatformAdminSessions.csrfDigest],
                row[PlatformAdminSessions.csrfHash],
            )
        }
    }

    fun verifyPlatformAdminCsrf(session: PlatformAdminSessionContext, raw: String): Boolean =
        CredentialCodec.matches(raw, session.csrfDigest, session.csrfHash)

    suspend fun deletePlatformAdminSession(id: UUID): Boolean = databaseFactory.dbTransaction {
        PlatformAdminSessions.deleteWhere { PlatformAdminSessions.id eq id } == 1
    }

    suspend fun issueLaunchNonce(
        telegramChatId: Long,
        telegramTopicId: Long?,
        telegramUserId: Long,
        expiresAt: Instant,
    ): IssuedCredential = databaseFactory.dbTransaction {
        val id = UUID.randomUUID()
        val (raw, stored) = CredentialCodec.issueCredential()
        TelegramLaunchNonces.insert {
            it[TelegramLaunchNonces.id] = id
            it[TelegramLaunchNonces.nonceDigest] = stored.digest
            it[TelegramLaunchNonces.telegramChatId] = telegramChatId
            it[TelegramLaunchNonces.telegramTopicId] = telegramTopicId
            it[TelegramLaunchNonces.telegramUserId] = telegramUserId
            it[TelegramLaunchNonces.expiresAt] = expiresAt.databaseTime()
        }
        IssuedCredential(id, id, raw)
    }

    suspend fun consumeLaunchNonce(
        raw: String,
        now: Instant = Instant.now(),
    ): LaunchNonceContext? = databaseFactory.dbTransaction {
        val databaseNow = now.databaseTime()
        val row = TelegramLaunchNonces.selectAll().where {
            TelegramLaunchNonces.consumedAt.isNull() and
                (TelegramLaunchNonces.expiresAt greater databaseNow) and
                (TelegramLaunchNonces.nonceDigest eq CredentialCodec.digest(raw))
        }.firstOrNull() ?: return@dbTransaction null

        val consumed = TelegramLaunchNonces.update({
            (TelegramLaunchNonces.id eq row[TelegramLaunchNonces.id]) and TelegramLaunchNonces.consumedAt.isNull()
        }) {
            it[consumedAt] = databaseNow
        } == 1

        if (consumed) {
            LaunchNonceContext(
                id = row[TelegramLaunchNonces.id],
                telegramChatId = row[TelegramLaunchNonces.telegramChatId],
                telegramTopicId = row[TelegramLaunchNonces.telegramTopicId],
                telegramUserId = row[TelegramLaunchNonces.telegramUserId],
            )
        } else null
    }

    suspend fun issueWebAppSession(
        telegramUserId: Long,
        telegramChatId: Long?,
        telegramTopicId: Long?,
        expiresAt: Instant,
    ): IssuedWebAppSession = databaseFactory.dbTransaction {
        val id = UUID.randomUUID()
        val (raw, stored) = CredentialCodec.issueCredential()
        val (csrf, storedCsrf) = CredentialCodec.issueCredential()
        WebAppSessions.insert {
            it[WebAppSessions.id] = id
            it[WebAppSessions.telegramUserId] = telegramUserId
            it[WebAppSessions.telegramChatId] = telegramChatId
            it[WebAppSessions.telegramTopicId] = telegramTopicId
            it[tokenDigest] = stored.digest
            it[tokenHash] = stored.hash
            it[csrfDigest] = storedCsrf.digest
            it[csrfHash] = storedCsrf.hash
            it[WebAppSessions.expiresAt] = expiresAt.databaseTime()
        }
        IssuedWebAppSession(id, telegramUserId, telegramChatId, telegramTopicId, raw, csrf)
    }

    suspend fun verifyWebAppSession(
        raw: String,
        now: Instant = Instant.now(),
    ): WebAppSessionContext? = databaseFactory.dbTransaction {
        WebAppSessions.selectAll().where {
            (WebAppSessions.expiresAt greater now.databaseTime()) and
                (WebAppSessions.tokenDigest eq CredentialCodec.digest(raw))
        }.firstOrNull { row ->
            CredentialCodec.matches(raw, row[WebAppSessions.tokenDigest], row[WebAppSessions.tokenHash])
        }?.let { row ->
            WebAppSessionContext(
                sessionId = row[WebAppSessions.id],
                telegramUserId = row[WebAppSessions.telegramUserId],
                telegramChatId = row[WebAppSessions.telegramChatId],
                telegramTopicId = row[WebAppSessions.telegramTopicId],
                csrfDigest = row[WebAppSessions.csrfDigest],
                csrfHash = row[WebAppSessions.csrfHash],
            )
        }
    }

    fun verifyWebAppCsrf(session: WebAppSessionContext, raw: String): Boolean =
        CredentialCodec.matches(raw, session.csrfDigest, session.csrfHash)

    suspend fun deleteWebAppSession(id: UUID): Boolean = databaseFactory.dbTransaction {
        WebAppSessions.deleteWhere { WebAppSessions.id eq id } == 1
    }

    suspend fun cleanupExpiredWebAppSessions(now: Instant = Instant.now()): Int = databaseFactory.dbTransaction {
        WebAppSessions.deleteWhere { WebAppSessions.expiresAt lessEq now.databaseTime() }
    }


    suspend fun cleanupExpiredManagementLinks(now: Instant = Instant.now()): Int = databaseFactory.dbTransaction {
        ManagementLinks.deleteWhere { ManagementLinks.expiresAt lessEq now.databaseTime() }
    }

    suspend fun cleanupExpiredManagementSessions(now: Instant = Instant.now()): Int = databaseFactory.dbTransaction {
        ManagementSessions.deleteWhere { ManagementSessions.expiresAt lessEq now.databaseTime() }
    }

    suspend fun cleanupExpiredPlatformAdminSessions(now: Instant = Instant.now()): Int =
        databaseFactory.dbTransaction {
            PlatformAdminSessions.deleteWhere { PlatformAdminSessions.expiresAt lessEq now.databaseTime() }
        }

    suspend fun cleanupExpiredWebhookSecrets(now: Instant = Instant.now()): Int = databaseFactory.dbTransaction {
        val databaseNow = now.databaseTime()
        WebhookSecrets.deleteWhere {
            (WebhookSecrets.revokedAt.isNotNull() and (WebhookSecrets.revokedAt lessEq databaseNow)) or
                (WebhookSecrets.expiresAt.isNotNull() and (WebhookSecrets.expiresAt lessEq databaseNow))
        }
    }

    suspend fun writeAuditEvent(
        installationId: UUID?,
        actorType: String,
        actorId: String?,
        action: String,
        metadataJson: String = "{}",
    ) {
        databaseFactory.dbTransaction {
            AuditEvents.insert {
                it[id] = UUID.randomUUID()
                it[AuditEvents.installationId] = installationId
                it[AuditEvents.actorType] = actorType
                it[AuditEvents.actorId] = actorId
                it[AuditEvents.action] = action
                it[metadata] = metadataJson
            }
        }
    }

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

    private fun managementLinkCandidate(raw: String, now: Instant): StoredCandidate? =
        ManagementLinks.selectAll().where {
            ManagementLinks.consumedAt.isNull() and
                (ManagementLinks.expiresAt greater now.databaseTime()) and
                (ManagementLinks.tokenDigest eq CredentialCodec.digest(raw))
        }.mapNotNull { row ->
            val hash = row[ManagementLinks.tokenHash] ?: return@mapNotNull null
            StoredCandidate(
                row[ManagementLinks.id],
                row[ManagementLinks.installationId],
                row[ManagementLinks.tokenDigest],
                hash,
            )
        }.firstOrNull { CredentialCodec.matches(raw, it.digest, it.hash) }

    private fun installationWithMuteQuery(installationId: UUID? = null): Query {
        val join = Installations.join(
            MuteStates,
            JoinType.LEFT,
            Installations.id,
            MuteStates.installationId,
        )
        val query = join.selectAll()
        if (installationId != null) query.andWhere { Installations.id eq installationId }
        return query
    }

    private fun ResultRow.toAdminContext() = InstallationAdminContext(
        id = this[Installations.id],
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
        val serializedReviewers = reviewerUsernames.joinToString(",")
        databaseFactory.dbTransaction {
            MrParticipantCaches.upsert {
                it[MrParticipantCaches.installationId] = installationId
                it[MrParticipantCaches.projectId] = projectId
                it[MrParticipantCaches.mrIid] = mrIid
                it[MrParticipantCaches.authorUsername] = authorUsername
                it[MrParticipantCaches.reviewerUsernames] = serializedReviewers
                it[MrParticipantCaches.updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    suspend fun getMrParticipants(
        installationId: UUID,
        projectId: Long,
        mrIid: Long,
    ): MrParticipants? {
        return databaseFactory.dbTransaction {
            MrParticipantCaches.selectAll()
                .where {
                    (MrParticipantCaches.installationId eq installationId) and
                        (MrParticipantCaches.projectId eq projectId) and
                        (MrParticipantCaches.mrIid eq mrIid)
                }
                .singleOrNull()
                ?.let { row ->
                    val author = row[MrParticipantCaches.authorUsername]
                    val rawReviewers = row[MrParticipantCaches.reviewerUsernames]
                    val reviewers = if (rawReviewers.isBlank()) emptyList() else rawReviewers.split(",")
                    MrParticipants(authorUsername = author, reviewerUsernames = reviewers)
                }
        }
    }
}

private fun Instant.databaseTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
