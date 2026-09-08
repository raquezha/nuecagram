package net.raquezha.nuecagram.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import net.raquezha.nuecagram.db.models.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

private data class StoredAuthCandidate(
    val id: UUID,
    val installationId: UUID,
    val digest: ByteArray,
    val hash: String,
)

@Suppress("TooManyFunctions")
class AuthSessionRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
) {
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
        telegramUserId: Long,
        now: Instant = Instant.now(),
    ): LaunchNonceContext? = databaseFactory.dbTransaction {
        val databaseNow = now.databaseTime()
        val row = TelegramLaunchNonces.selectAll().where {
            TelegramLaunchNonces.consumedAt.isNull() and
                (TelegramLaunchNonces.expiresAt greater databaseNow) and
                (TelegramLaunchNonces.telegramUserId eq telegramUserId) and
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
        username: String?,
        firstName: String?,
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
            it[WebAppSessions.username] = username
            it[WebAppSessions.firstName] = firstName
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
                username = row[WebAppSessions.username],
                firstName = row[WebAppSessions.firstName],
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

    suspend fun writeAuditEvent(
        installationId: UUID?,
        actorType: String,
        actorId: String?,
        action: String,
        metadataJson: String = "{}",
        metadataPatch: AuditMetadataPatch = AuditMetadataPatch(),
    ): Boolean {
        if (actorType in ACTOR_ID_REQUIRED_TYPES && actorId.isNullOrBlank()) return false

        databaseFactory.dbTransaction {
            val installationSnapshot = installationId?.let { id ->
                Installations.join(
                    MuteStates,
                    org.jetbrains.exposed.v1.core.JoinType.LEFT,
                    additionalConstraint = { Installations.id eq MuteStates.installationId },
                ).selectAll().where {
                    (Installations.id eq id) and Installations.deletedAt.isNull()
                }.firstOrNull()?.let { row ->
                    InstallationAdminContext(
                        id = row[Installations.id],
                        repoName = row[Installations.repoName],
                        chatName = row[Installations.chatName],
                        gitlabBaseUrl = row[Installations.gitlabBaseUrl],
                        gitlabProjectId = row[Installations.gitlabProjectId],
                        telegramChatId = row[Installations.telegramChatId],
                        telegramTopicId = row[Installations.telegramTopicId],
                        muted = row.getOrNull(MuteStates.muted) ?: false,
                    )
                }
            }
            AuditEvents.insert {
                it[id] = UUID.randomUUID()
                it[AuditEvents.installationId] = installationId
                it[AuditEvents.actorType] = actorType
                it[AuditEvents.actorId] = actorId
                it[AuditEvents.action] = action
                it[metadata] = buildAuditMetadataJson(
                    existingMetadataJson = metadataJson,
                    installationId = installationId,
                    installation = installationSnapshot,
                    actorId = actorId,
                    metadataPatch = metadataPatch,
                )
            }
        }
        return true
    }

    private fun managementLinkCandidate(raw: String, now: Instant): StoredAuthCandidate? =
        ManagementLinks.selectAll().where {
            ManagementLinks.consumedAt.isNull() and
                (ManagementLinks.expiresAt greater now.databaseTime()) and
                (ManagementLinks.tokenDigest eq CredentialCodec.digest(raw))
        }.mapNotNull { row ->
            val hash = row[ManagementLinks.tokenHash] ?: return@mapNotNull null
            StoredAuthCandidate(
                row[ManagementLinks.id],
                row[ManagementLinks.installationId],
                row[ManagementLinks.tokenDigest],
                hash,
            )
        }.firstOrNull { CredentialCodec.matches(raw, it.digest, it.hash) }

    private fun buildAuditMetadataJson(
        existingMetadataJson: String,
        installationId: UUID?,
        installation: InstallationAdminContext?,
        actorId: String?,
        metadataPatch: AuditMetadataPatch,
    ): String {
        val existing =
            runCatching { auditJson.parseToJsonElement(existingMetadataJson).jsonObject }
                .getOrDefault(JsonObject(emptyMap()))
        val fields = existing.toMutableMap()
        fields.putString("installation_id", installationId?.toString())
        fields.putString("actor_id", actorId)
        fields.putString("username", metadataPatch.actorUsername)
        fields.putString("first_name", metadataPatch.actorFirstName)
        fields.putString("repo_name", metadataPatch.repoName ?: installation?.repoName)
        fields.putString("nickname", metadataPatch.nickname ?: installation?.chatName)
        fields.putLong("chat_id", metadataPatch.chatId ?: installation?.telegramChatId)
        fields.putLong("topic_id", metadataPatch.topicId ?: installation?.telegramTopicId)
        fields.putString("old_repo_name", metadataPatch.identityDelta?.oldRepoName)
        fields.putString("new_repo_name", metadataPatch.identityDelta?.newRepoName)
        fields.putString("old_nickname", metadataPatch.identityDelta?.oldNickname)
        fields.putString("new_nickname", metadataPatch.identityDelta?.newNickname)
        return auditJson.encodeToString(JsonObject.serializer(), JsonObject(fields))
    }

    private companion object {
        val ACTOR_ID_REQUIRED_TYPES = setOf("telegram", "webapp_session")
        val auditJson = Json
    }
}

private fun MutableMap<String, JsonElement>.putString(key: String, value: String?) {
    value?.takeIf(String::isNotBlank)?.let { put(key, JsonPrimitive(it)) }
}

private fun MutableMap<String, JsonElement>.putLong(key: String, value: Long?) {
    if (value != null) {
        put(key, JsonPrimitive(value))
    }
}

private fun Instant.databaseTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
