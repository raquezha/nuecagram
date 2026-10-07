package net.raquezha.nuecagram.webhook

import org.gitlab4j.api.webhook.MergeRequestEvent

class MergeRequestMessageFormatter {

    fun formatMergeRequestEventMessage(
        event: MergeRequestEvent,
        squashedCommitCount: Int? = null,
    ): String {
        val data = extractMergeRequestData(event)
        val (emoji, actionText) = getMergeRequestActionDisplay(
            action = data.action,
            isDraft = data.isDraft,
            squashedCommitCount = squashedCommitCount,
        )
        val clickableMR = data.url.link("!${data.iid}")

        return buildString {
            append("$emoji Merge Request $clickableMR $actionText\n")
            append("${data.projectName.bold()} • ${data.sourceBranch} → ${data.targetBranch}\n")
            append("\n")

            if (data.isDraft) append("📝 ")
            append("${data.title.bold()}\n")

            appendTruncatedDescription(data.description, FormatterConstants.MAX_DESC_LENGTH)
            appendLabels(data.labels)

            append("\n\n")
            append("By ${data.userName.bold()}")
        }
    }

    private data class MergeRequestData(
        val userName: String,
        val projectName: String,
        val url: String,
        val iid: Any,
        val title: String,
        val description: String?,
        val action: String,
        val sourceBranch: String,
        val targetBranch: String,
        val isDraft: Boolean,
        val labels: List<String>,
    )

    private fun extractMergeRequestData(event: MergeRequestEvent) =
        MergeRequestData(
            userName = event.user?.name ?: "Unknown",
            projectName = event.repository?.name ?: event.project?.name ?: "Unknown",
            url = event.objectAttributes?.url ?: "",
            iid = event.objectAttributes?.iid ?: "?",
            title = event.objectAttributes?.title ?: "Untitled",
            description = event.objectAttributes?.description,
            action = event.objectAttributes?.action ?: "updated",
            sourceBranch = event.objectAttributes?.sourceBranch ?: "unknown",
            targetBranch = event.objectAttributes?.targetBranch ?: "unknown",
            isDraft = event.objectAttributes?.workInProgress == true,
            labels = event.labels.orEmpty().mapNotNull { it.title },
        )

    private fun getMergeRequestActionDisplay(
        action: String,
        isDraft: Boolean,
        squashedCommitCount: Int? = null,
    ): Pair<String, String> =
        when (action.lowercase()) {
            "open" -> if (isDraft) "📝" to "draft opened" else "🔓" to "opened"
            "close" -> "✅" to "closed"
            "reopen" -> "🔓" to "reopened"
            "update" -> "✏️" to "updated"
            "approved" -> "👍" to "approved"
            "unapproved" -> "👎" to "unapproved"
            "merge" -> {
                val count = (squashedCommitCount ?: 1).coerceAtLeast(1)
                "🟣" to "Merged (Squashed $count commits)"
            }
            else -> "🔷" to action
        }
}
