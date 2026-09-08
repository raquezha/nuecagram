package net.raquezha.nuecagram.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import net.raquezha.nuecagram.db.models.InstallationAdminContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

class InstallationAdminRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
    private val lifecycleRepository: InstallationLifecycleRepository =
        InstallationLifecycleRepository(databaseFactory),
) {
    suspend fun installationAdminContext(installationId: UUID): InstallationAdminContext? =
        databaseFactory.dbTransaction {
            lifecycleRepository.installationWithMuteQuery(installationId).firstOrNull()?.toAdminContext()
        }

    suspend fun listInstallationsForContext(
        chatId: Long?,
        topicId: Long?,
    ): List<InstallationAdminContext> = databaseFactory.dbTransaction {
        val query = lifecycleRepository.installationWithMuteQuery()
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
            val query = lifecycleRepository.installationWithMuteQuery(uuid)
            if (chatId != null) {
                query.andWhere { Installations.telegramChatId eq chatId }
            }
            if (topicId != null) {
                query.andWhere { Installations.telegramTopicId eq topicId }
            }
            return@dbTransaction query.firstOrNull()?.toAdminContext()
        }
        val query = lifecycleRepository.installationWithMuteQuery()
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
}

private fun Instant.databaseTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
