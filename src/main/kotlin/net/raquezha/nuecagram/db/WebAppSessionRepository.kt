package net.raquezha.nuecagram.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import net.raquezha.nuecagram.db.models.IssuedCredential
import net.raquezha.nuecagram.db.models.IssuedWebAppSession
import net.raquezha.nuecagram.db.models.LaunchNonceContext
import net.raquezha.nuecagram.db.models.WebAppSessionContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

class WebAppSessionRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
) {
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
}

private fun Instant.databaseTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
