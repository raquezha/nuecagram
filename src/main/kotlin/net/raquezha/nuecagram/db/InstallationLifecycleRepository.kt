package net.raquezha.nuecagram.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import net.raquezha.nuecagram.db.models.InstallationContext
import net.raquezha.nuecagram.db.models.InstallationRecord
import net.raquezha.nuecagram.db.models.WebhookInstallationResult
import net.raquezha.nuecagram.webhook.ChatDetails
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

class InstallationLifecycleRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
    private val webhookSecretRepository: WebhookSecretRepository = WebhookSecretRepository(databaseFactory),
) {
    private companion object {
        const val UNKNOWN_REPOSITORY_NAME = "Unknown Repository"
        const val MAX_COLUMN_LENGTH = 255
    }

    suspend fun createInstallation(
        repoName: String,
        chatName: String? = null,
        gitlabBaseUrl: String,
        gitlabProjectId: Long?,
        telegramChatId: Long,
        telegramTopicId: Long?,
    ): InstallationRecord {
        val normalizedRepoName = repoName.trim().take(MAX_COLUMN_LENGTH)
        require(normalizedRepoName.isNotBlank() && normalizedRepoName != UNKNOWN_REPOSITORY_NAME) {
            "repoName must be non-blank and not use the legacy fallback value"
        }
        val normalizedChatName = chatName?.trim()?.takeIf(String::isNotBlank)?.take(MAX_COLUMN_LENGTH)
        val normalizedGitlabUrl = gitlabBaseUrl.redactedUrl().trim().trimEnd('/').take(MAX_COLUMN_LENGTH)
        val installation = InstallationRecord(
            id = UUID.randomUUID(),
            repoName = normalizedRepoName,
            chatName = normalizedChatName,
            gitlabBaseUrl = normalizedGitlabUrl,
            gitlabProjectId = gitlabProjectId,
            telegramChatId = telegramChatId,
            telegramTopicId = telegramTopicId,
        )
        databaseFactory.dbTransaction {
            Installations.insert {
                it[id] = installation.id
                it[Installations.repoName] = installation.repoName
                it[Installations.chatName] = installation.chatName
                it[Installations.gitlabBaseUrl] = installation.gitlabBaseUrl
                it[Installations.gitlabProjectId] = installation.gitlabProjectId
                it[Installations.telegramChatId] = installation.telegramChatId
                it[Installations.telegramTopicId] = installation.telegramTopicId
            }
        }
        return installation
    }

    suspend fun createInstallation(
        gitlabBaseUrl: String,
        gitlabProjectId: Long?,
        telegramChatId: Long,
        telegramTopicId: Long?,
    ): InstallationRecord =
        createInstallation(
            repoName = deriveRepositoryName(gitlabBaseUrl, gitlabProjectId),
            gitlabBaseUrl = gitlabBaseUrl,
            gitlabProjectId = gitlabProjectId,
            telegramChatId = telegramChatId,
            telegramTopicId = telegramTopicId,
        )

    suspend fun resolveWebhookInstallation(
        raw: String,
        now: Instant = Instant.now(),
    ): WebhookInstallationResult {
        val verified = webhookSecretRepository.verifyWebhookSecret(raw, now)
            ?: return WebhookInstallationResult.NotFound
        return databaseFactory.dbTransaction {
            val row = installationWithMuteQuery(verified.installationId, includeDeleted = true).firstOrNull()
                ?: return@dbTransaction WebhookInstallationResult.NotFound

            if (row[Installations.deletedAt] != null) {
                return@dbTransaction WebhookInstallationResult.SoftDeleted
            }

            WebhookInstallationResult.Active(
                InstallationContext(
                    verified.secretId,
                    verified.installationId,
                    ChatDetails(
                        row[Installations.telegramChatId].toString(),
                        row[Installations.telegramTopicId]?.toString(),
                    ),
                    row.getOrNull(MuteStates.muted) ?: false,
                ),
            )
        }
    }

    suspend fun updateIdentity(
        installationId: UUID,
        repoName: String,
        chatName: String?,
    ): Boolean {
        val normalizedRepoName = repoName.trim().take(MAX_COLUMN_LENGTH)
        require(normalizedRepoName.isNotBlank() && normalizedRepoName != UNKNOWN_REPOSITORY_NAME) {
            "repoName must be non-blank and not use the legacy fallback value"
        }
        val normalizedChatName = chatName?.trim()?.takeIf(String::isNotBlank)?.take(MAX_COLUMN_LENGTH)
        return databaseFactory.dbTransaction {
            Installations.update({ Installations.id eq installationId }) {
                it[Installations.repoName] = normalizedRepoName
                it[Installations.chatName] = normalizedChatName
            } == 1
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

    suspend fun softDeleteInstallation(id: UUID): Boolean = databaseFactory.dbTransaction {
        val count = Installations.update({ (Installations.id eq id) and (Installations.deletedAt.isNull()) }) {
            it[deletedAt] = Instant.now().databaseTime()
        }
        count > 0
    }

    private fun deriveRepositoryName(gitlabBaseUrl: String, gitlabProjectId: Long?): String {
        if (gitlabProjectId != null) return "Project #$gitlabProjectId"
        val cleanUrl = gitlabBaseUrl.redactedUrl()
        return cleanUrl.trim()
            .substringAfter("://", cleanUrl.trim())
            .substringAfter('/', "")
            .trim('/')
            .takeIf(String::isNotBlank)
            ?: cleanUrl.trim().trim('/').takeIf(String::isNotBlank)
            ?: UNKNOWN_REPOSITORY_NAME
    }

    internal fun installationWithMuteQuery(
        installationId: UUID? = null,
        includeDeleted: Boolean = false,
    ): Query {
        val join = Installations.join(
            MuteStates,
            JoinType.LEFT,
            Installations.id,
            MuteStates.installationId,
        )
        val query = join.selectAll()
        if (!includeDeleted) {
            query.andWhere { Installations.deletedAt.isNull() }
        }
        if (installationId != null) query.andWhere { Installations.id eq installationId }
        return query
    }
}

private fun Instant.databaseTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
