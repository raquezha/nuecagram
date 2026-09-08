package net.raquezha.nuecagram.db.models

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

data class KnownTelegramDestination(
    val id: String,
    val telegramChatId: Long,
    val telegramTopicId: Long?,
    val chatTitle: String?,
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
