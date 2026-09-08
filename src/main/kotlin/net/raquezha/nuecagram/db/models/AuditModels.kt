package net.raquezha.nuecagram.db.models

import java.time.Instant

data class PlatformAdminAuditRecord(
    val installationId: java.util.UUID?,
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

object AuditMetadataKeys {
    const val INSTALLATION_ID = "installation_id"
    const val ACTOR_ID = "actor_id"
    const val USERNAME = "username"
    const val FIRST_NAME = "first_name"
    const val REPO_NAME = "repo_name"
    const val NICKNAME = "nickname"
    const val CHAT_ID = "chat_id"
    const val TOPIC_ID = "topic_id"
    const val OLD_REPO_NAME = "old_repo_name"
    const val NEW_REPO_NAME = "new_repo_name"
    const val OLD_NICKNAME = "old_nickname"
    const val NEW_NICKNAME = "new_nickname"
}

object ActorType {
    const val TELEGRAM = "telegram"
    const val WEBAPP_SESSION = "webapp_session"
    const val PLATFORM_ADMIN = "platform_admin"
    const val MANAGEMENT_SESSION = "management_session"

    val REQUIRED_ACTOR_ID = setOf(TELEGRAM, WEBAPP_SESSION)
}
