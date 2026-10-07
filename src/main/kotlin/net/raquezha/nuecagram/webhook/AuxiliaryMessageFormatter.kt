package net.raquezha.nuecagram.webhook

import org.gitlab4j.api.utils.UrlEncoder.urlEncode
import org.gitlab4j.api.webhook.BuildEvent
import org.gitlab4j.api.webhook.DeploymentEvent
import org.gitlab4j.api.webhook.IssueEvent
import org.gitlab4j.api.webhook.ReleaseEvent
import org.gitlab4j.api.webhook.TagPushEvent
import org.gitlab4j.api.webhook.WikiPageEvent

class AuxiliaryMessageFormatter {

    @Suppress("UNUSED_PARAMETER")
    fun formatBuildEventMessage(event: BuildEvent): String {
        throw SkipEventException()
    }

    fun formatDeployEventMessage(event: DeploymentEvent): String {
        val projectName = event.project?.name ?: "Unknown"
        val environment = event.environment ?: "unknown"
        val status = event.status ?: "unknown"
        val userName = event.user?.username ?: event.user?.name ?: "Unknown"
        val commitUrl = event.commitUrl
        val deployableUrl = event.deployableUrl

        val (emoji, statusText) = getDeploymentStatusDisplay(status)

        return buildString {
            append("$emoji Deployment to ${environment.bold()} $statusText\n")
            append("${projectName.bold()}\n")

            if (commitUrl != null) {
                val shortSha = commitUrl.substringAfterLast("/").take(FormatterConstants.SHORT_SHA_LENGTH)
                append("\n🔗 ${commitUrl.link(shortSha)}")
            }

            if (deployableUrl != null) {
                append("\n🔗 ${deployableUrl.link("View Job")}")
            }

            append("\n\n")
            append("By ${userName.bold()}")
        }
    }

    private fun getDeploymentStatusDisplay(status: String): Pair<String, String> =
        when (status.lowercase()) {
            "created" -> "✨" to "created"
            "running" -> "⚙️" to "running"
            "success" -> "✅" to "succeeded"
            "failed" -> "❌" to "failed"
            "canceled" -> "⛔" to "canceled"
            "canceling" -> "⏳" to "canceling"
            else -> "🔷" to status
        }

    fun formatReleaseEventMessage(event: ReleaseEvent): String {
        val projectName = event.project?.name ?: "Unknown"
        val releaseName = event.name ?: event.tag ?: "Unknown"
        val releaseUrl = event.url ?: ""
        val action = event.action ?: "created"
        val description = event.description
        val tag = event.tag

        val (emoji, actionText) = getReleaseActionDisplay(action)
        val clickableRelease = releaseUrl.link(releaseName)

        return buildString {
            append("$emoji Release $clickableRelease $actionText\n")
            append("${projectName.bold()}")
            if (tag != null && tag != releaseName) {
                append(" • $tag")
            }
            append("\n")

            if (!description.isNullOrBlank()) {
                append("\n")
                val truncatedDesc = description.take(FormatterConstants.MAX_RELEASE_DESC_LENGTH).trim()
                append("${truncatedDesc.italic()}")
                if (description.length > FormatterConstants.MAX_RELEASE_DESC_LENGTH) append("...")
                append("\n")
            }

            val assetsCount = event.assets?.count
            if (assetsCount != null && assetsCount > 0) {
                append("\n📦 $assetsCount asset(s)")
            }
        }
    }

    private fun getReleaseActionDisplay(action: String): Pair<String, String> =
        when (action.lowercase()) {
            "create" -> "🚀" to "published"
            "update" -> "✏️" to "updated"
            "delete" -> "🗑️" to "deleted"
            else -> "🔷" to action
        }

    fun formatIssueEventMessage(event: IssueEvent): String {
        val userName = event.user?.name ?: "Unknown"
        val projectName = event.repository?.name ?: event.project?.name ?: "Unknown"
        val issueUrl = event.objectAttributes?.url ?: ""
        val issueIid = event.objectAttributes?.iid ?: issueUrl.extractIssueNumber() ?: "?"
        val issueTitle = event.objectAttributes?.title ?: "Untitled"
        val issueDescription = event.objectAttributes?.description
        val action = event.objectAttributes?.action ?: "updated"

        val (emoji, actionText) = getIssueActionDisplay(action)
        val clickableIssue = issueUrl.link("#$issueIid")
        val labels = event.labels.orEmpty().mapNotNull { it.title }
        val assignees = event.assignees.orEmpty().mapNotNull { it.name }

        return buildString {
            append("$emoji Issue $clickableIssue $actionText\n")
            append("${projectName.bold()}\n")
            append("\n")
            append("📌 ${issueTitle.bold()}\n")

            appendTruncatedDescription(issueDescription, FormatterConstants.MAX_DESC_LENGTH)
            appendLabels(labels)
            appendAssignees(assignees)

            append("\n\n")
            append("By ${userName.bold()}")
        }
    }

    private fun StringBuilder.appendAssignees(assignees: List<String>) {
        if (assignees.isNotEmpty()) {
            append("\n👤 ${assignees.joinToString(", ")}")
        }
    }

    private fun getIssueActionDisplay(action: String): Pair<String, String> =
        when (action.lowercase()) {
            "open" -> "🔓" to "opened"
            "close" -> "✅" to "closed"
            "reopen" -> "🔓" to "reopened"
            "update" -> "✏️" to "updated"
            else -> "🔷" to action
        }

    private fun String.extractIssueNumber(): String? = Regex(""".*/issues/(\d+)""").find(this)?.groupValues?.get(1)

    fun formatTagPushEvent(event: TagPushEvent): String {
        val userName = event.userName ?: "Unknown"
        val projectName = event.repository?.name ?: "Unknown"
        val projectWebUrl = event.repository?.homepage ?: ""
        val tagName = event.ref?.removePrefix("refs/tags/") ?: "unknown"

        val beforeSha = event.before ?: ""
        val afterSha = event.after ?: ""

        val (emoji, action) = getTagActionDisplay(beforeSha, afterSha)

        val tagUrl = "$projectWebUrl/-/tags/${urlEncode(tagName)}"
        val clickableTag = tagUrl.link(tagName)

        return buildString {
            append("$emoji Tag $clickableTag $action\n")
            append("${projectName.bold()}\n")
            appendTagCommitInfo(event, afterSha, projectWebUrl)
            append("\n")
            append("By ${userName.bold()}")
        }
    }

    private fun getTagActionDisplay(
        beforeSha: String,
        afterSha: String,
    ): Pair<String, String> =
        when {
            beforeSha.isNullHash() -> "🏷️" to "created"
            afterSha.isNullHash() -> "🗑️" to "deleted"
            else -> "🏷️" to "updated"
        }

    private fun StringBuilder.appendTagCommitInfo(
        event: TagPushEvent,
        afterSha: String,
        projectWebUrl: String,
    ) {
        if (afterSha.isNullHash()) return

        val commits = event.commits.orEmpty()
        val latestCommit = commits.firstOrNull() ?: return

        val shortSha = latestCommit.id?.take(FormatterConstants.SHORT_SHA_LENGTH)
            ?: afterSha.take(FormatterConstants.SHORT_SHA_LENGTH)
        val commitUrl = latestCommit.url ?: "$projectWebUrl/-/commit/${latestCommit.id}"
        val commitTitle = latestCommit.title ?: latestCommit.message?.lines()?.firstOrNull() ?: ""

        append("\n")
        append("🔗 ${commitUrl.link(shortSha)}")
        if (commitTitle.isNotBlank()) {
            append(" ${commitTitle.take(FormatterConstants.MAX_COMMIT_TITLE_LENGTH).escapeHtml()}")
            if (commitTitle.length > FormatterConstants.MAX_COMMIT_TITLE_LENGTH) append("...")
        }
        append("\n")
    }

    fun formatWikiPageEvent(event: WikiPageEvent): String {
        val userName = event.user?.name ?: "Unknown"
        val projectName = event.project?.name ?: "Unknown"
        val pageUrl = event.objectAttributes?.url ?: ""
        val pageTitle = event.objectAttributes?.title ?: "Untitled"
        val action = event.objectAttributes?.action ?: "updated"
        val pageMessage = event.objectAttributes?.message

        val (emoji, actionText) = getWikiActionDisplay(action)
        val clickablePage = pageUrl.link(pageTitle)

        return buildString {
            append("$emoji Wiki $clickablePage $actionText\n")
            append("${projectName.bold()}\n")

            if (!pageMessage.isNullOrBlank()) {
                append("\n")
                append("💬 ${pageMessage.take(FormatterConstants.MAX_SHORT_DESC_LENGTH).italic()}")
                if (pageMessage.length > FormatterConstants.MAX_SHORT_DESC_LENGTH) append("...")
                append("\n")
            }

            append("\n")
            append("By ${userName.bold()}")
        }
    }

    private fun getWikiActionDisplay(action: String): Pair<String, String> =
        when (action.lowercase()) {
            "create" -> "📄" to "created"
            "update" -> "✏️" to "updated"
            "delete" -> "🗑️" to "deleted"
            else -> "🔷" to action
        }
}
