package net.raquezha.nuecagram.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import net.raquezha.nuecagram.db.models.ConsumedManagementLink
import net.raquezha.nuecagram.db.models.IssuedCredential
import net.raquezha.nuecagram.db.models.IssuedManagementSession
import net.raquezha.nuecagram.db.models.ManagementSessionContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

private data class StoredManagementCandidate(
    val id: UUID,
    val installationId: UUID,
    val digest: ByteArray,
    val hash: String,
)

class ManagementSessionRepository(
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

    suspend fun cleanupExpiredManagementLinks(now: Instant = Instant.now()): Int = databaseFactory.dbTransaction {
        ManagementLinks.deleteWhere { ManagementLinks.expiresAt lessEq now.databaseTime() }
    }

    suspend fun cleanupExpiredManagementSessions(now: Instant = Instant.now()): Int = databaseFactory.dbTransaction {
        ManagementSessions.deleteWhere { ManagementSessions.expiresAt lessEq now.databaseTime() }
    }

    private fun managementLinkCandidate(raw: String, now: Instant): StoredManagementCandidate? =
        ManagementLinks.selectAll().where {
            ManagementLinks.consumedAt.isNull() and
                (ManagementLinks.expiresAt greater now.databaseTime()) and
                (ManagementLinks.tokenDigest eq CredentialCodec.digest(raw))
        }.mapNotNull { row ->
            val hash = row[ManagementLinks.tokenHash] ?: return@mapNotNull null
            StoredManagementCandidate(
                row[ManagementLinks.id],
                row[ManagementLinks.installationId],
                row[ManagementLinks.tokenDigest],
                hash,
            )
        }.firstOrNull { CredentialCodec.matches(raw, it.digest, it.hash) }
}

private fun Instant.databaseTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
