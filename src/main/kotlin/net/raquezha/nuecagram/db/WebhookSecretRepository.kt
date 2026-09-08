package net.raquezha.nuecagram.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import net.raquezha.nuecagram.db.models.IssuedCredential
import net.raquezha.nuecagram.db.models.VerifiedSecret
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

class WebhookSecretRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
) {
    private data class StoredSecretCandidate(
        val id: UUID,
        val installationId: UUID,
        val digest: ByteArray,
        val hash: String,
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

    suspend fun cleanupExpiredWebhookSecrets(now: Instant = Instant.now()): Int = databaseFactory.dbTransaction {
        val databaseNow = now.databaseTime()
        WebhookSecrets.deleteWhere {
            (WebhookSecrets.revokedAt.isNotNull() and (WebhookSecrets.revokedAt lessEq databaseNow)) or
                (WebhookSecrets.expiresAt.isNotNull() and (WebhookSecrets.expiresAt lessEq databaseNow))
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
}

private fun Instant.databaseTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
