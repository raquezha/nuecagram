package net.raquezha.nuecagram.webhook

import org.gitlab4j.api.webhook.EventCommit
import org.gitlab4j.api.webhook.PushEvent

class PushMessageFormatter {

    fun formatPushEventMessage(event: PushEvent, mrIid: Long? = null): String {
        val beforeSha = event.before ?: ""
        val afterSha = event.after ?: ""

        if (beforeSha.isNullHash() || afterSha.isNullHash()) {
            throw SkipEventException()
        }

        val userName = event.userName ?: "Unknown"
        val projectName = event.project?.name ?: event.repository?.name ?: "Unknown"
        val projectWebUrl = event.project?.webUrl ?: event.repository?.homepage ?: ""

        val ref = (event.ref?.removePrefix("refs/heads/") ?: "unknown").ifBlank { "unknown" }
        val commits = event.commits.orEmpty()
        val commitCount = event.totalCommitsCount ?: commits.size

        if (commitCount == 0) {
            throw SkipEventException()
        }

        val compareUrl = "$projectWebUrl/-/compare/$beforeSha...$afterSha"
        val mrBadge = formatMrBadge(mrIid, projectWebUrl)
        return buildString {
            append("🚀 Push to ${ref.bold()}$mrBadge\n")
            append("${projectName.bold()} • ${compareUrl.link("$commitCount commit(s)")}\n")
            append("\n")
            appendPushCommits(commits)
            append("\n")
            append("Pushed by ${userName.bold()}")
        }
    }

    private fun StringBuilder.appendPushCommits(commits: List<EventCommit>) {
        val displayCommits = commits.take(FormatterConstants.MAX_DISPLAY_COMMITS)
        displayCommits.forEachIndexed { index, commit ->
            val isLast = index == displayCommits.size - 1 && commits.size <= FormatterConstants.MAX_DISPLAY_COMMITS
            val prefix = if (isLast) "└─" else "├─"
            val shortSha = commit.id?.take(FormatterConstants.SHORT_SHA_LENGTH) ?: "unknown"
            val commitUrl = commit.url ?: ""
            val rawTitle = commit.title ?: commit.message?.lines()?.firstOrNull() ?: "No message"
            val title = rawTitle.take(FormatterConstants.MAX_COMMIT_TITLE_LENGTH)
            val truncatedTitle = if (rawTitle.length > FormatterConstants.MAX_COMMIT_TITLE_LENGTH) {
                "$title..."
            } else {
                title
            }
            append("$prefix ${commitUrl.link(shortSha)} ${truncatedTitle.escapeHtml()}\n")
        }

        if (commits.size > FormatterConstants.MAX_DISPLAY_COMMITS) {
            append("└─ +${commits.size - FormatterConstants.MAX_DISPLAY_COMMITS} more commits\n")
        }
    }
}
