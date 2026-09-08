package net.raquezha.nuecagram.db.models

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
