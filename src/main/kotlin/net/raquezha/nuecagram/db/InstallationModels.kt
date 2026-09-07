package net.raquezha.nuecagram.db

import java.time.Instant
import java.util.UUID
import net.raquezha.nuecagram.webhook.ChatDetails

data class InstallationRecord(
    val id: UUID,
    val repoName: String,
    val chatName: String?,
    val gitlabBaseUrl: String,
    val gitlabProjectId: Long?,
    val telegramChatId: Long,
    val telegramTopicId: Long?,
)

data class IssuedCredential(val id: UUID, val installationId: UUID, val raw: String)
data class VerifiedSecret(val secretId: UUID, val installationId: UUID)
data class ConsumedManagementLink(val linkId: UUID, val installationId: UUID)

data class IssuedManagementSession(
    val sessionId: UUID,
    val installationId: UUID,
    val raw: String,
    val csrf: String,
)

data class ManagementSessionContext(
    val sessionId: UUID,
    val installationId: UUID,
    val csrfDigest: ByteArray?,
    val csrfHash: String?,
)

data class IssuedPlatformAdminSession(val id: UUID, val raw: String, val csrf: String)
data class PlatformAdminSessionContext(val id: UUID, val csrfDigest: ByteArray, val csrfHash: String)

data class IssuedWebAppSession(
    val sessionId: UUID,
    val telegramUserId: Long,
    val telegramChatId: Long?,
    val telegramTopicId: Long?,
    val raw: String,
    val csrf: String,
)

data class WebAppSessionContext(
    val sessionId: UUID,
    val telegramUserId: Long,
    val telegramChatId: Long?,
    val telegramTopicId: Long?,
    val username: String?,
    val firstName: String?,
    val csrfDigest: ByteArray,
    val csrfHash: String,
)

data class LaunchNonceContext(
    val id: UUID,
    val telegramChatId: Long,
    val telegramTopicId: Long?,
    val telegramUserId: Long,
)

data class KnownTelegramDestination(
    val id: String,
    val telegramChatId: Long,
    val telegramTopicId: Long?,
    val chatTitle: String?,
)

data class PlatformAdminAuditRecord(
    val installationId: UUID?,
    val action: String,
    val createdAt: Instant,
    val repository: String,
    val actor: String,
    val chatDetails: String,
    val details: List<String> = emptyList(),
)

data class PlatformAdminInstallationsPage(
    val items: List<InstallationAdminContext>,
    val totalCount: Long,
)

data class PlatformAdminAuditPage(
    val items: List<PlatformAdminAuditRecord>,
    val totalCount: Long,
)

data class AuditIdentityDelta(
    val oldRepoName: String? = null,
    val newRepoName: String? = null,
    val oldNickname: String? = null,
    val newNickname: String? = null,
)

data class AuditMetadataPatch(
    val actorUsername: String? = null,
    val actorFirstName: String? = null,
    val repoName: String? = null,
    val nickname: String? = null,
    val chatId: Long? = null,
    val topicId: Long? = null,
    val identityDelta: AuditIdentityDelta? = null,
)

data class MrParticipants(
    val authorUsername: String?,
    val reviewerUsernames: List<String>,
)

data class ActiveMergeRequest(
    val mrIid: Long,
    val sourceBranch: String,
    val targetProjectId: Long?,
    val lastCommitSha: String?,
)

data class RecentBranchPush(
    val branch: String,
    val latestPushSha: String,
)

data class InstallationContext(
    val secretId: UUID,
    val installationId: UUID,
    val chatDetails: ChatDetails,
    val muted: Boolean,
)

sealed interface WebhookInstallationResult {
    data class Active(val context: InstallationContext) : WebhookInstallationResult
    data object SoftDeleted : WebhookInstallationResult
    data object NotFound : WebhookInstallationResult
}

data class InstallationAdminContext(
    val id: UUID,
    val repoName: String,
    val chatName: String?,
    val gitlabBaseUrl: String,
    val gitlabProjectId: Long?,
    val telegramChatId: Long,
    val telegramTopicId: Long?,
    val muted: Boolean,
) {
    fun destinationDisplayName(topicName: String? = null): String =
        when {
            chatName.isNullOrBlank() -> repoName
            topicName.isNullOrBlank() -> chatName
            else -> "$chatName ($topicName)"
        }

    val displayName: String
        get() = destinationDisplayName()

    fun repositoryButtonLabel(): String {
        val name = repoName.takeIf { it.isNotBlank() }
            ?: gitlabProjectId?.let { "Project #$it" }
            ?: gitlabBaseUrl.removePrefix("https://").removePrefix("http://")
        return if (!chatName.isNullOrBlank()) {
            "$name | $chatName"
        } else {
            name
        }
    }
}
