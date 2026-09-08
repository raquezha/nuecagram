package net.raquezha.nuecagram.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import net.raquezha.nuecagram.db.models.IssuedPlatformAdminSession
import net.raquezha.nuecagram.db.models.PlatformAdminSessionContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class PlatformAdminSessionRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
) {
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

    suspend fun cleanupExpiredPlatformAdminSessions(now: Instant = Instant.now()): Int =
        databaseFactory.dbTransaction {
            PlatformAdminSessions.deleteWhere { PlatformAdminSessions.expiresAt lessEq now.databaseTime() }
        }
}

private fun Instant.databaseTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
