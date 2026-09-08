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
