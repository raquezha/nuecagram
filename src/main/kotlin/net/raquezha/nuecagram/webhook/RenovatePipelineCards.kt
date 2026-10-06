package net.raquezha.nuecagram.webhook

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import net.raquezha.nuecagram.db.DatabaseFactory

/** Persisted branch-pipeline cards awaiting an MR, keyed by the commit that triggered them. */
@Suppress("MagicNumber") // JDBC parameter and result-set indices mirror SQL column order.
internal class RenovatePipelineCards(
    private val clock: Clock = Clock.systemUTC(),
) {
    // ponytail: global Renovate delivery lock; shard by installation/branch if throughput requires it.
    suspend fun <T> coordinateDelivery(block: suspend () -> T): T =
        DatabaseFactory.withAdvisoryLock(72419031L, block)

    data class Key(val installationId: UUID, val projectId: Long, val branch: String, val sha: String)

    data class DueCard(
        val key: Key,
        val chatDetails: ChatDetails,
        val text: String,
        val claimUntil: Instant,
    )

    suspend fun recordOutcome(key: Key, pipelineId: Long, text: String, eventTime: Instant): Unit =
        DatabaseFactory.dbQuery { connection ->
            connection.prepareStatement(
                """
                INSERT INTO renovate_pipeline_outcomes
                    (installation_id, project_id, branch, commit_sha, pipeline_id, card_text, event_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (installation_id, project_id, pipeline_id) DO UPDATE
                SET card_text = EXCLUDED.card_text, event_time = EXCLUDED.event_time
                WHERE EXCLUDED.event_time >= renovate_pipeline_outcomes.event_time
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, key.installationId)
                statement.setLong(2, key.projectId)
                statement.setString(3, key.branch)
                statement.setString(4, key.sha)
                statement.setLong(5, pipelineId)
                statement.setString(6, text)
                statement.setObject(7, eventTime.atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }

    suspend fun recordMr(key: Key, iid: Long, text: String): Unit = DatabaseFactory.dbQuery { connection ->
        connection.prepareStatement(
            """
            INSERT INTO renovate_mr_cards (installation_id, project_id, branch, commit_sha, mr_iid, card_text)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (installation_id, project_id, branch) DO UPDATE
            SET commit_sha = EXCLUDED.commit_sha, mr_iid = EXCLUDED.mr_iid, card_text = EXCLUDED.card_text,
                message_id = CASE WHEN renovate_mr_cards.mr_iid = EXCLUDED.mr_iid
                    THEN renovate_mr_cards.message_id ELSE NULL END
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, key.installationId)
            statement.setLong(2, key.projectId)
            statement.setString(3, key.branch)
            statement.setString(4, key.sha)
            statement.setLong(5, iid)
            statement.setString(6, text)
            statement.executeUpdate()
        }
    }

    suspend fun render(key: Key): String = DatabaseFactory.dbQuery { connection ->
        connection.prepareStatement(
            """
            SELECT card_text FROM renovate_mr_cards
            WHERE installation_id = ? AND project_id = ? AND branch = ? AND commit_sha = ?
            UNION ALL
            SELECT card_text FROM (
                SELECT card_text FROM renovate_pipeline_outcomes
                WHERE installation_id = ? AND project_id = ? AND branch = ? AND commit_sha = ?
                ORDER BY pipeline_id
            ) AS outcomes
            """.trimIndent(),
        ).use { statement ->
            for (start in listOf(1, 5)) {
                statement.setObject(start, key.installationId)
                statement.setLong(start + 1, key.projectId)
                statement.setString(start + 2, key.branch)
                statement.setString(start + 3, key.sha)
            }
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }.joinToString("\n\n")
            }
        }
    }

    suspend fun record(
        key: Key,
        pipelineId: Long,
        chatDetails: ChatDetails,
        text: String,
    ): String? = DatabaseFactory.dbQuery { connection ->
        connection.prepareStatement(
            """
            INSERT INTO renovate_pipeline_cards
                (installation_id, project_id, branch, commit_sha, pipeline_id,
                 chat_id, topic_id, card_text, due_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (installation_id, project_id, branch, commit_sha)
            DO UPDATE SET pipeline_id = EXCLUDED.pipeline_id, card_text = EXCLUDED.card_text,
                updated_at = EXCLUDED.updated_at
            RETURNING message_id
            """.trimIndent(),
        ).use { statement ->
            val now = clock.instant()
            statement.setObject(1, key.installationId)
            statement.setLong(2, key.projectId)
            statement.setString(3, key.branch)
            statement.setString(4, key.sha)
            statement.setLong(5, pipelineId)
            statement.setString(6, chatDetails.chatId)
            statement.setString(7, chatDetails.topicId)
            statement.setString(8, text)
            statement.setObject(9, now.plus(2, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC))
            statement.setObject(10, now.atOffset(ZoneOffset.UTC))
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    suspend fun claimDue(limit: Int = 25): List<DueCard> = DatabaseFactory.dbQuery { connection ->
        val now = clock.instant()
        val claimedUntil = now.plusSeconds(60)
        connection.prepareStatement(
            """
            UPDATE renovate_pipeline_cards SET claim_until = ?, updated_at = ?
            WHERE (installation_id, project_id, branch, commit_sha) IN (
                SELECT installation_id, project_id, branch, commit_sha
                FROM renovate_pipeline_cards AS pending
                WHERE pending.message_id IS NULL AND pending.due_at <= ?
                    AND (pending.claim_until IS NULL OR pending.claim_until <= ?)
                ORDER BY pending.due_at LIMIT ? FOR UPDATE SKIP LOCKED
            )
            RETURNING installation_id, project_id, branch, commit_sha, chat_id, topic_id, card_text
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, claimedUntil.atOffset(ZoneOffset.UTC))
            statement.setObject(2, now.atOffset(ZoneOffset.UTC))
            statement.setObject(3, now.atOffset(ZoneOffset.UTC))
            statement.setObject(4, now.atOffset(ZoneOffset.UTC))
            statement.setInt(5, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            DueCard(
                                Key(
                                    rows.getObject(1, UUID::class.java),
                                    rows.getLong(2),
                                    rows.getString(3),
                                    rows.getString(4),
                                ),
                                ChatDetails(rows.getString(5), rows.getString(6)),
                                rows.getString(7),
                                claimedUntil,
                            ),
                        )
                    }
                }
            }
        }
    }

    suspend fun findExistingMessageId(
        installationId: UUID,
        projectId: Long,
        branch: String,
    ): String? = DatabaseFactory.dbQuery { connection ->
        connection.prepareStatement(
            """
            SELECT message_id FROM (
                SELECT message_id, 0 AS priority, CURRENT_TIMESTAMP AS updated_at FROM renovate_mr_cards
                WHERE installation_id = ? AND project_id = ? AND branch = ? AND message_id IS NOT NULL
                UNION ALL
                SELECT message_id, 1 AS priority, updated_at FROM renovate_pipeline_cards
                WHERE installation_id = ? AND project_id = ? AND branch = ? AND message_id IS NOT NULL
            ) AS cards ORDER BY priority, updated_at DESC LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, installationId)
            statement.setLong(2, projectId)
            statement.setString(3, branch)
            statement.setObject(4, installationId)
            statement.setLong(5, projectId)
            statement.setString(6, branch)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) else null
            }
        }
    }

    suspend fun cancelPending(
        installationId: UUID,
        projectId: Long,
        branch: String,
        messageId: String,
    ) {
        while (true) {
            try {
                storeMessageId(installationId, projectId, branch, messageId)
                return
            } catch (exception: java.sql.SQLException) {
                io.github.oshai.kotlinlogging.KotlinLogging.logger {}.error(exception) {
                    "Retrying persistence of delivered Renovate message $messageId"
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private suspend fun storeMessageId(
        installationId: UUID,
        projectId: Long,
        branch: String,
        messageId: String,
    ): Unit = DatabaseFactory.dbQuery { connection ->
        connection.prepareStatement(
            "UPDATE renovate_mr_cards SET message_id = ? WHERE installation_id = ? AND project_id = ? AND branch = ?",
        ).use { statement ->
            statement.setString(1, messageId)
            statement.setObject(2, installationId)
            statement.setLong(3, projectId)
            statement.setString(4, branch)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            UPDATE renovate_pipeline_cards
            SET message_id = ?, updated_at = ?
            WHERE installation_id = ? AND project_id = ? AND branch = ? AND message_id IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, messageId)
            statement.setObject(2, clock.instant().atOffset(ZoneOffset.UTC))
            statement.setObject(3, installationId)
            statement.setLong(4, projectId)
            statement.setString(5, branch)
            statement.executeUpdate()
        }
    }

    suspend fun cleanupStale(): Int = DatabaseFactory.dbQuery { connection ->
        connection.prepareStatement("DELETE FROM renovate_pipeline_cards WHERE updated_at < ?").use { statement ->
            statement.setObject(1, clock.instant().minus(30, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))
            statement.executeUpdate()
        }
    }

    suspend fun markSent(card: DueCard, messageId: String): String = DatabaseFactory.dbQuery { connection ->
        connection.prepareStatement(
            """
            UPDATE renovate_pipeline_cards SET message_id = ?, claim_until = NULL, updated_at = ?
            WHERE installation_id = ? AND project_id = ? AND branch = ? AND commit_sha = ?
                AND ((message_id IS NULL AND claim_until = ?) OR message_id = ?)
            RETURNING card_text
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, messageId)
            statement.setObject(2, clock.instant().atOffset(ZoneOffset.UTC))
            statement.setObject(3, card.key.installationId)
            statement.setLong(4, card.key.projectId)
            statement.setString(5, card.key.branch)
            statement.setString(6, card.key.sha)
            statement.setObject(7, card.claimUntil.atOffset(ZoneOffset.UTC))
            statement.setString(8, messageId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Lost claim for Renovate pipeline card ${card.key}" }
                rows.getString(1)
            }
        }
    }
}
