package net.raquezha.nuecagram.db

import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val PARAM_1 = 1
private const val PARAM_2 = 2
private const val PARAM_3 = 3
private const val PARAM_4 = 4
private const val PARAM_5 = 5
private const val PARAM_6 = 6

data class InstallationRecord(
    val id: UUID,
    val gitlabBaseUrl: String,
    val gitlabProjectId: Long?,
    val telegramChatId: Long,
    val telegramTopicId: Long?,
)

data class IssuedCredential(
    val id: UUID,
    val installationId: UUID,
    val raw: String,
)

data class VerifiedSecret(
    val secretId: UUID,
    val installationId: UUID,
)

data class ConsumedManagementLink(
    val linkId: UUID,
    val installationId: UUID,
)

class InstallationRepository {
    suspend fun createInstallation(
        gitlabBaseUrl: String,
        gitlabProjectId: Long?,
        telegramChatId: Long,
        telegramTopicId: Long?,
    ): InstallationRecord {
        val installation =
            InstallationRecord(
                id = UUID.randomUUID(),
                gitlabBaseUrl = gitlabBaseUrl,
                gitlabProjectId = gitlabProjectId,
                telegramChatId = telegramChatId,
                telegramTopicId = telegramTopicId,
            )
        DatabaseFactory.dbQuery { connection ->
            connection.prepareStatement(
                """
                INSERT INTO installations (id, gitlab_base_url, gitlab_project_id, telegram_chat_id, telegram_topic_id)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(PARAM_1, installation.id)
                statement.setString(PARAM_2, installation.gitlabBaseUrl)
                statement.setNullableLong(PARAM_3, installation.gitlabProjectId)
                statement.setLong(PARAM_4, installation.telegramChatId)
                statement.setNullableLong(PARAM_5, installation.telegramTopicId)
                statement.executeUpdate()
            }
        }
        return installation
    }

    suspend fun issueWebhookSecret(
        installationId: UUID,
        expiresAt: Instant? = null,
    ): IssuedCredential = issueCredential(
        installationId = installationId,
        insertSql = """
            INSERT INTO webhook_secrets (id, installation_id, secret_digest, secret_hash, expires_at)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
        bindExpiration = { setNullableInstant(PARAM_5, expiresAt) },
    )

    suspend fun rotateWebhookSecret(
        installationId: UUID,
        graceUntil: Instant,
        expiresAt: Instant? = null,
    ): IssuedCredential {
        val issued = issueWebhookSecret(installationId, expiresAt)
        DatabaseFactory.dbQuery { connection ->
            connection.prepareStatement(
                """
                UPDATE webhook_secrets
                SET revoked_at = ?
                WHERE installation_id = ? AND id <> ? AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setInstant(PARAM_1, graceUntil)
                statement.setObject(PARAM_2, installationId)
                statement.setObject(PARAM_3, issued.id)
                statement.executeUpdate()
            }
        }
        return issued
    }

    suspend fun confirmWebhookSecret(
        secretId: UUID,
        confirmedAt: Instant = Instant.now(),
    ): Boolean =
        DatabaseFactory.dbQuery { connection ->
            connection.prepareStatement(
                "UPDATE webhook_secrets SET confirmed_at = ? WHERE id = ? AND confirmed_at IS NULL",
            ).use { statement ->
                statement.setInstant(PARAM_1, confirmedAt)
                statement.setObject(PARAM_2, secretId)
                statement.executeUpdate() == 1
            }
        }

    suspend fun verifyWebhookSecret(
        raw: String,
        now: Instant = Instant.now(),
    ): VerifiedSecret? =
        DatabaseFactory.dbQuery { connection ->
            val candidates = mutableListOf<Triple<UUID, UUID, String>>()
            connection.prepareStatement(
                """
                SELECT id, installation_id, secret_hash, secret_digest
                FROM webhook_secrets
                WHERE (revoked_at IS NULL OR revoked_at > ?)
                  AND (expires_at IS NULL OR expires_at > ?)
                  AND secret_digest = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInstant(PARAM_1, now)
                statement.setInstant(PARAM_2, now)
                statement.setBytes(PARAM_3, CredentialCodec.digest(raw))
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val digest = result.getBytes("secret_digest")
                        val hash = result.getString("secret_hash") ?: continue
                        if (CredentialCodec.matches(raw, digest, hash)) {
                            candidates += Triple(
                                result.getObject("id", UUID::class.java),
                                result.getObject("installation_id", UUID::class.java),
                                hash,
                            )
                        }
                    }
                }
            }
            candidates.firstOrNull()?.let { VerifiedSecret(it.first, it.second) }
        }

    suspend fun issueManagementLink(
        installationId: UUID,
        expiresAt: Instant,
    ): IssuedCredential = issueCredential(
        installationId = installationId,
        insertSql = """
            INSERT INTO management_links (id, installation_id, token_digest, token_hash, expires_at)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
        bindExpiration = { setInstant(PARAM_5, expiresAt) },
    )

    suspend fun consumeManagementLink(
        raw: String,
        now: Instant = Instant.now(),
    ): ConsumedManagementLink? =
        DatabaseFactory.dbQuery { connection ->
            data class Candidate(
                val id: UUID,
                val installationId: UUID,
                val digest: ByteArray,
                val hash: String,
            )
            val candidates = mutableListOf<Candidate>()
            connection.prepareStatement(
                """
                SELECT id, installation_id, token_digest, token_hash
                FROM management_links
                WHERE consumed_at IS NULL AND expires_at > ? AND token_digest = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInstant(PARAM_1, now)
                statement.setBytes(PARAM_2, CredentialCodec.digest(raw))
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val hash = result.getString("token_hash") ?: continue
                        candidates += Candidate(
                            id = result.getObject("id", UUID::class.java),
                            installationId = result.getObject("installation_id", UUID::class.java),
                            digest = result.getBytes("token_digest"),
                            hash = hash,
                        )
                    }
                }
            }
            val match =
                candidates.firstOrNull { CredentialCodec.matches(raw, it.digest, it.hash) }
                    ?: return@dbQuery null
            connection.prepareStatement(
                "UPDATE management_links SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL AND expires_at > ?",
            ).use { statement ->
                statement.setInstant(PARAM_1, now)
                statement.setObject(PARAM_2, match.id)
                statement.setInstant(PARAM_3, now)
                if (statement.executeUpdate() == 1) ConsumedManagementLink(match.id, match.installationId) else null
            }
        }

    suspend fun cleanupExpiredManagementLinks(now: Instant = Instant.now()): Int =
        DatabaseFactory.dbQuery { connection ->
            connection.prepareStatement(
                "DELETE FROM management_links WHERE expires_at <= ?",
            ).use { statement ->
                statement.setInstant(PARAM_1, now)
                statement.executeUpdate()
            }
        }

    suspend fun cleanupExpiredWebhookSecrets(now: Instant = Instant.now()): Int =
        DatabaseFactory.dbQuery { connection ->
            connection.prepareStatement(
                """
                DELETE FROM webhook_secrets
                WHERE (revoked_at IS NOT NULL AND revoked_at <= ?)
                   OR (expires_at IS NOT NULL AND expires_at <= ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setInstant(PARAM_1, now)
                statement.setInstant(PARAM_2, now)
                statement.executeUpdate()
            }
        }

    suspend fun writeAuditEvent(
        installationId: UUID?,
        actorType: String,
        actorId: String?,
        action: String,
        metadataJson: String = "{}",
    ) {
        DatabaseFactory.dbQuery { connection ->
            connection.prepareStatement(
                """
                INSERT INTO audit_events (id, installation_id, actor_type, actor_id, action, metadata)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(PARAM_1, UUID.randomUUID())
                statement.setObject(PARAM_2, installationId)
                statement.setString(PARAM_3, actorType)
                statement.setString(PARAM_4, actorId)
                statement.setString(PARAM_5, action)
                statement.setString(PARAM_6, metadataJson)
                statement.executeUpdate()
            }
        }
    }

    private suspend fun issueCredential(
        installationId: UUID,
        insertSql: String,
        bindExpiration: PreparedStatement.() -> Unit,
    ): IssuedCredential {
        val id = UUID.randomUUID()
        val (raw, stored) = CredentialCodec.issueCredential()
        DatabaseFactory.dbQuery { connection ->
            connection.prepareStatement(insertSql).use { statement ->
                statement.setObject(PARAM_1, id)
                statement.setObject(PARAM_2, installationId)
                statement.setBytes(PARAM_3, stored.digest)
                statement.setString(PARAM_4, stored.hash)
                statement.bindExpiration()
                statement.executeUpdate()
            }
        }
        return IssuedCredential(id, installationId, raw)
    }
}

private fun PreparedStatement.setNullableInstant(index: Int, value: Instant?) {
    if (value == null) {
        setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
    } else {
        setInstant(index, value)
    }
}

private fun PreparedStatement.setInstant(index: Int, value: Instant) {
    setObject(index, value.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
}

private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
}
