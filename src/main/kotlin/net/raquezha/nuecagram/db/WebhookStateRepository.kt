package net.raquezha.nuecagram.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import net.raquezha.nuecagram.db.models.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

class WebhookStateRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
) {
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

    suspend fun upsertActiveMr(
        installationId: UUID,
        projectId: Long,
        sourceBranch: String,
        mrIid: Long,
        lastCommitSha: String? = null,
        targetProjectId: Long? = null,
    ) {
        val safeBranch = sourceBranch.take(MAX_BRANCH_LENGTH)
        databaseFactory.dbTransaction {
            ActiveMergeRequests.upsert {
                it[ActiveMergeRequests.installationId] = installationId
                it[ActiveMergeRequests.projectId] = projectId
                it[ActiveMergeRequests.sourceBranch] = safeBranch
                it[ActiveMergeRequests.mrIid] = mrIid
                it[ActiveMergeRequests.targetProjectId] = targetProjectId
                it[ActiveMergeRequests.lastCommitSha] = lastCommitSha
                it[ActiveMergeRequests.updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    suspend fun getActiveMrForBranch(
        installationId: UUID,
        projectId: Long,
        sourceBranch: String,
    ): ActiveMergeRequest? {
        val safeBranch = sourceBranch.take(MAX_BRANCH_LENGTH)
        return databaseFactory.dbTransaction {
            ActiveMergeRequests.selectAll()
                .where {
                    (ActiveMergeRequests.installationId eq installationId) and
                        (ActiveMergeRequests.projectId eq projectId) and
                        (ActiveMergeRequests.sourceBranch eq safeBranch)
                }
                .firstOrNull()
                ?.let { row ->
                    ActiveMergeRequest(
                        mrIid = row[ActiveMergeRequests.mrIid],
                        sourceBranch = row[ActiveMergeRequests.sourceBranch],
                        targetProjectId = row[ActiveMergeRequests.targetProjectId],
                        lastCommitSha = row[ActiveMergeRequests.lastCommitSha],
                    )
                }
        }
    }

    suspend fun clearActiveMr(
        installationId: UUID,
        projectId: Long,
        sourceBranch: String,
    ) {
        val safeBranch = sourceBranch.take(MAX_BRANCH_LENGTH)
        databaseFactory.dbTransaction {
            ActiveMergeRequests.deleteWhere {
                (ActiveMergeRequests.installationId eq installationId) and
                    (ActiveMergeRequests.projectId eq projectId) and
                    (ActiveMergeRequests.sourceBranch eq safeBranch)
            }
        }
    }

    suspend fun upsertLatestPushSha(
        installationId: UUID,
        projectId: Long,
        branch: String,
        latestPushSha: String,
    ) {
        val safeBranch = branch.take(MAX_BRANCH_LENGTH)
        databaseFactory.dbTransaction {
            RecentBranchPushes.upsert {
                it[RecentBranchPushes.installationId] = installationId
                it[RecentBranchPushes.projectId] = projectId
                it[RecentBranchPushes.branch] = safeBranch
                it[RecentBranchPushes.latestPushSha] = latestPushSha
                it[RecentBranchPushes.updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    suspend fun getLatestPushSha(
        installationId: UUID,
        projectId: Long,
        branch: String,
    ): String? {
        val safeBranch = branch.take(MAX_BRANCH_LENGTH)
        return databaseFactory.dbTransaction {
            RecentBranchPushes.selectAll()
                .where {
                    (RecentBranchPushes.installationId eq installationId) and
                        (RecentBranchPushes.projectId eq projectId) and
                        (RecentBranchPushes.branch eq safeBranch)
                }
                .firstOrNull()
                ?.get(RecentBranchPushes.latestPushSha)
        }
    }

    suspend fun tryRecordProcessedEvent(
        eventUuid: String?,
        installationId: UUID?,
        eventType: String,
    ): Boolean {
        val trimmedUuid = eventUuid?.trim() ?: return true
        if (trimmedUuid.isBlank()) return true
        val safeUuid = trimmedUuid.take(MAX_COLUMN_LENGTH)
        val safeType = eventType.take(MAX_EVENT_TYPE_LENGTH)
        return databaseFactory.dbTransaction {
            val exists = ProcessedWebhookEvents.selectAll()
                .where {
                    (ProcessedWebhookEvents.eventUuid eq safeUuid) and
                        (ProcessedWebhookEvents.eventType eq safeType)
                }
                .count() > 0
            if (exists) {
                false
            } else {
                ProcessedWebhookEvents.insertIgnore {
                    it[ProcessedWebhookEvents.eventUuid] = safeUuid
                    it[ProcessedWebhookEvents.installationId] = installationId
                    it[ProcessedWebhookEvents.eventType] = safeType
                    it[ProcessedWebhookEvents.processedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                }
                true
            }
        }
    }

    suspend fun clearProcessedWebhookEvents() {
        databaseFactory.dbTransaction {
            ProcessedWebhookEvents.deleteWhere { ProcessedWebhookEvents.eventUuid.isNotNull() }
        }
    }

    suspend fun cleanupStaleMrAndPushStates(
        now: Instant = Instant.now(),
        maxAgeDays: Long = 30,
    ): Int = databaseFactory.dbTransaction {
        val cutoff = now.minus(maxAgeDays, java.time.temporal.ChronoUnit.DAYS).databaseTime()
        val deletedMrs = ActiveMergeRequests.deleteWhere { ActiveMergeRequests.updatedAt lessEq cutoff }
        val deletedPushes = RecentBranchPushes.deleteWhere { RecentBranchPushes.updatedAt lessEq cutoff }
        val deletedEvents = ProcessedWebhookEvents.deleteWhere { ProcessedWebhookEvents.processedAt lessEq cutoff }
        deletedMrs + deletedPushes + deletedEvents
    }

    private companion object {
        const val MAX_COLUMN_LENGTH = 255
        const val MAX_BRANCH_LENGTH = 512
        const val MAX_EVENT_TYPE_LENGTH = 100
    }
}

private fun Instant.databaseTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
