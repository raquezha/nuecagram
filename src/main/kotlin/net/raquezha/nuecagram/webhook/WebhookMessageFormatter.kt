@file:Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")

package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import org.gitlab4j.api.GitLabApiException
import org.gitlab4j.api.utils.UrlEncoder.urlEncode
import org.gitlab4j.api.webhook.AbstractPushEvent
import org.gitlab4j.api.webhook.BuildEvent
import org.gitlab4j.api.webhook.DeploymentEvent
import org.gitlab4j.api.webhook.Event
import org.gitlab4j.api.webhook.IssueEvent
import org.gitlab4j.api.webhook.MergeRequestEvent
import org.gitlab4j.api.webhook.NoteEvent
import org.gitlab4j.api.webhook.NoteEvent.NoteableType.COMMIT
import org.gitlab4j.api.webhook.NoteEvent.NoteableType.ISSUE
import org.gitlab4j.api.webhook.NoteEvent.NoteableType.MERGE_REQUEST
import org.gitlab4j.api.webhook.NoteEvent.NoteableType.SNIPPET
import org.gitlab4j.api.webhook.PipelineEvent
import org.gitlab4j.api.webhook.PushEvent
import org.gitlab4j.api.webhook.ReleaseEvent
import org.gitlab4j.api.webhook.TagPushEvent
import org.gitlab4j.api.webhook.WikiPageEvent
import org.koin.java.KoinJavaComponent.inject
import java.text.SimpleDateFormat
import java.util.*

class WebhookMessageFormatter {
    private val logger by inject<KLogger>(KLogger::class.java)

    fun formatEventMessage(event: Event): String =
        when (event) {
            is PipelineEvent -> formatPipelineEvent(event)
            is PushEvent -> formatPushEventMessage(event)
            is TagPushEvent -> formatTagPushEvent(event)
            is WikiPageEvent -> formatWikiPageEvent(event)
            is DeploymentEvent -> formatDeployEventMessage(event)
            is ReleaseEvent -> formatReleaseEventMessage(event)
            is IssueEvent -> formatIssueEventMessage(event)
            is BuildEvent -> formatBuildEventMessage(event)
            is MergeRequestEvent -> formatMergeRequestEventMessage(event)
            is NoteEvent -> formatNoteEvent(event)
            else -> throwUnsupportedEventException(event)
        }

    private fun String.bold() = "<b>$this</b>"

    private fun String.italic() = "<i>$this</i>"

    private fun String.italicBold() = this.bold().italic()

    private fun String.link(label: String) = "<a href=\"$this\">$label</a>"

    private fun String.isNullHash(): Boolean = this == "0000000000000000000000000000000000000000"

    private fun throwUnsupportedEventException(event: Event): Nothing {
        val message = "Unsupported event object_kind, object_kind=${event.objectKind}"
        logger.error { message }
        throw GitLabApiException(message)
    }

    private fun formatBuildEventMessage(event: BuildEvent): String {
        if (event.buildStatus in listOf("created")) {
            throw SkipEventException()
        }
        val jobUrl = event.getPipelineUrl().link("#${event.buildId}")
        return buildString {
            append(event.getBuildStatusEmoji())
            append("Job ${event.buildName.bold()} $jobUrl ")
            append("triggered by ${event.user.name.bold()} ")
            append("in project ${event.repository.name.bold()} has ")
            append(event.getBuildStatusMessage())
        }
    }

    private fun BuildEvent.getBuildStatusEmoji(): String =
        when (buildStatus) {
            "created" -> "✨ "
            "pending" -> "⏳ "
            "running" -> "\uD83D\uDFE2 "
            "success" -> "✅ "
            "failed" -> "❎ "
            "canceled" -> "⛔ "
            else -> ""
        }

    private fun BuildEvent.getBuildStatusMessage(): String =
        when (buildStatus) {
            "created" -> createdStatus()
            "pending" -> pendingStatus()
            "running" -> runningStatus()
            "success" -> successStatus()
            "failed" -> failedStatus()
            "canceled" -> canceledStatus()
            else -> throw GitLabApiException("[build status $buildStatus not supported.]")
        }

    private fun BuildEvent.failedStatus(): String =
        "${"failed".bold()} due to ${buildFailureReason.italicBold()} at ${buildFinishedAt?.formatFinishedAt()}."

    private fun BuildEvent.canceledStatus(): String = "been ${"canceled".bold()} at ${buildFinishedAt?.formatFinishedAt()}."

    @Suppress("UnusedReceiverParameter")
    private fun BuildEvent.createdStatus(): String = "been ${"created".bold()}."

    @Suppress("UnusedReceiverParameter")
    private fun BuildEvent.pendingStatus(): String = "went to ${"pending".bold()} status."

    @Suppress("UnusedReceiverParameter")
    private fun BuildEvent.runningStatus(): String = "started ${"running".bold()}. Only time will tell when will it be finished."

    private fun BuildEvent.successStatus(): String = "finished ${"successfully".bold()} at ${buildFinishedAt?.formatFinishedAt()}."

    private fun BuildEvent.getPipelineUrl(): String = "${repository.homepage}/-/jobs/$buildId"

    private fun NoteEvent.getUrl(label: String): String = objectAttributes.url.link(label)

    private fun Date?.formatFinishedAt(): String =
        this?.let {
            logger.debug { "unformatted date: $it" }
            val formatter = SimpleDateFormat("hh:mm a 'on' MMMM dd, yyyy")
            formatter.timeZone = TimeZone.getTimeZone("Asia/Manila")

            formatter.format(it).also { formatted ->
                logger.debug { "formatted date: $formatted" }
            }
        } ?: "N/A"

    private fun PipelineEvent.getPipelineUrl(): String = "${project.webUrl}-/pipelines/${objectAttributes.id}"

    private fun formatNoteEvent(event: NoteEvent): String {
        val randomCommentMessage = RandomCommentMessage()
        val randomMessage = randomCommentMessage.getRandomComment()
        return when (event.objectAttributes.noteableType) {
            SNIPPET -> throw SkipEventException() // won't support snippet for now
            ISSUE ->
                event.generateNoteMessage(
                    randomMessage = randomMessage,
                    url = event.getUrl("issue"),
                    description = "Issue: ${event.issue.title}",
                )
            MERGE_REQUEST ->
                event.generateNoteMessage(
                    randomMessage = randomMessage,
                    url = event.getUrl("merge request"),
                    description = "Merge Request: ${event.mergeRequest.title}",
                )
            COMMIT ->
                event.generateNoteMessage(
                    randomMessage = randomMessage,
                    url = event.getUrl("commit"),
                    description = "Commit Message: ${event.commit.message}",
                )
        }
    }

    private fun NoteEvent.generateNoteMessage(
        randomMessage: String,
        url: String,
        description: String,
    ): String =
        "${user.name.bold()} $randomMessage $url in ${project.name.bold()}:\n" +
            "\n${objectAttributes.note}\n" +
            "\n${description.trim().italic()}"

    private fun formatPipelineEvent(event: PipelineEvent): String {
        val status = event.objectAttributes.status
        val projectName = event.project.name
        val ref = event.objectAttributes.ref

        val clickablePipeline = event.getPipelineUrl().link("#${event.objectAttributes.id}".bold())

        return buildString {
            append("Pipeline $clickablePipeline for branch ${ref.bold()} ")
            append("in project ${projectName.bold()} has ")
            when (status) {
                "failed" -> append("failed!".bold())
                "canceled" -> append("has been ${"canceled".bold()}.")
                else -> throw SkipEventException()
            }
        }
    }

    private fun formatTagPushEvent(event: TagPushEvent): String {
        val (refType, tagName) =
            Regex("refs/(heads|tags)/(.+)").find(event.ref)?.destructured
                ?: throw IllegalArgumentException("Invalid ref format")
        val itemType = if (refType == "heads") "branch" else "tag"
        val tagUrl = "${event.repository.homepage}/tree/${urlEncode(tagName)}".link(tagName)

        val beforeSha = event.before
        val afterSha = event.after

        return when {
            beforeSha.isNullHash() -> "${event.userName.bold()} pushed new $itemType $tagUrl at ${event.repository.name}"
            afterSha.isNullHash() -> "${event.userName.bold()} deleted $itemType $tagUrl at ${event.repository.name}"
            else -> "${event.userName.bold()} updated $itemType $tagUrl at ${event.repository.name}"
        }
    }

    private fun formatIssueEventMessage(event: IssueEvent): String =
        buildIssueMessage(
            userMention = event.user.name,
            issueUrl = event.objectAttributes.url,
            issueTitle = event.objectAttributes.title,
            repositoryName = event.repository.name,
            issueDescription = event.objectAttributes.description,
            action = getFormattedAction(event.objectAttributes.action),
        )

    private fun buildIssueMessage(
        userMention: String,
        issueUrl: String,
        issueTitle: String,
        repositoryName: String,
        issueDescription: String?,
        action: String,
    ): String =
        formatActionWithTitleAndDescription(
            user = userMention,
            action = action,
            link = issueUrl.link("issue#${issueUrl.extractIssueNumber()}"),
            repositoryName = repositoryName,
            title = issueTitle,
            description = issueDescription,
        )

    private fun String.extractIssueNumber(): String? = Regex(""".*/issues/(\d+)""").find(this)?.groupValues?.get(1)

    private fun AbstractPushEvent.mentionBranch(): String {
        val destStr = project.pathWithNamespace.ifEmpty { repository.name }
        return "${repository.homepage}/tree/${urlEncode(branch)}".link("$destStr/$branch")
    }

    private fun getWebPreview(event: PushEvent): String {
        val fileSummary =
            buildString {
                val changes = event.commits.flatMap { listOf(it.added.size, it.removed.size, it.modified.size) }
                val totalChanges = changes.sum()
                if (totalChanges > 0) {
                    append("$totalChanges file${if (totalChanges > 1) "s" else ""} changes. ")
                    val modified = changes[2]
                    val added = changes[0]
                    val removed = changes[1]

                    val parts = mutableListOf<String>()
                    if (modified > 0) parts.add("$modified modified")
                    if (added > 0) parts.add("$added added")
                    if (removed > 0) parts.add("$removed removed")

                    append(parts.joinToString(separator = ", ", prefix = "", postfix = ""))
                }
            }
        val pushedCommitLabel =
            if (event.commits.isNotEmpty()) {
                "${event.commits.size} commits"
            } else {
                "Commit"
            }
        val compareUrl =
            compareURL(event.repository.homepage, event.before, event.after)
                .takeIf { event.commits.isNotEmpty() } ?: ""

        return buildString {
            append("$pushedCommitLabel\n$fileSummary ")
            append("(${compareUrl.link("${event.before.take(8)}...${event.after.take(8)}")})")
        }
    }

    private fun compareURL(
        homepage: String,
        before: String,
        after: String,
    ): String = "$homepage/compare/$before...$after"

    private fun mention(
        userName: String?,
        name: String,
    ): String = userName?.takeIf { it.isNotEmpty() }?.let { "@${it.bold()}" } ?: name.bold()

    private fun formatCommits(event: PushEvent): String {
        val otherCommiter =
            event.commits.any {
                it.author.email != event.userEmail && it.author.name != event.userName
            }
        return event.commits.joinToString("\n") { commit ->
            val authorPrefix = if (otherCommiter) "${mention(commit.author.username, commit.author.name)}: " else ""
            "$authorPrefix${commit.url.link(commit.message.trimEnd('\n'))}"
        } + "\n"
    }

    private fun formatPushEventMessage(event: PushEvent): String {
        val clickableBranch = event.mentionBranch()
        val commitText = formatCommits(event)
        val wp = getWebPreview(event)
        return if (event.commits.isNotEmpty()) {
            "${event.userName.bold()} pushed $wp to $clickableBranch\n\n$commitText"
        } else {
            if (!event.after.isNullHash() && event.after.isNotEmpty()) {
                "${event.userName.bold()} created branch $clickableBranch"
            } else {
                val destStr = event.project.pathWithNamespace.ifEmpty { event.repository.name }
                "${event.userName.bold()} deleted branch ${"$destStr/${event.branch}".bold()}\n"
            }
        }
    }

    private fun formatWikiPageEvent(event: WikiPageEvent): String =
        formatAction(
            user = event.user.name,
            action = getFormattedAction(event.objectAttributes.action),
            link = "a <a href='${event.objectAttributes.url}'>Wiki Page</a>",
            repositoryName = event.project.name,
        )

    private fun formatDeployEventMessage(event: DeploymentEvent): String {
        val projectName = event.project.name ?: "Unknown project"
        val environment = event.environment ?: "unknown environment"
        val status = event.status ?: "unknown status"
        val user = event.user.username ?: "unknown user"
        val commitUrl = event.commitUrl

        return buildString {
            append("Deployment to ${environment.bold()} in project ${projectName.bold()} ")
            append(
                when (status) {
                    "success" -> "succeeded.".bold()
                    "failed" -> "failed.".bold()
                    "created" -> "created.".bold()
                    "canceling" -> "is being canceled.".bold()
                    "canceled" -> "has been canceled.".bold()
                    else -> "has status $status."
                },
            )
            if (commitUrl != null) {
                append(" Commit: ${commitUrl.link("link")}")
            }
            append(" Deployed by ${user.bold()}.")
        }
    }

    private fun formatReleaseEventMessage(event: ReleaseEvent): String =
        buildString {
            append("Release ${event.url.link(event.name).bold()}")
            append(" ${getFormattedAction(event.action)} in project ${event.project.name.bold()}")
        }

    private fun formatMergeRequestEventMessage(event: MergeRequestEvent): String =
        formatActionWithTitleAndDescription(
            user = event.user.name,
            action = getFormattedAction(event.objectAttributes.action),
            link = "${event.objectAttributes.url.link("merge request#${event.objectAttributes.id}")}",
            repositoryName = event.repository.name,
            title = event.objectAttributes.title,
            description = event.objectAttributes.description,
        )

    private fun formatActionWithTitleAndDescription(
        user: String,
        action: String,
        link: String,
        repositoryName: String,
        title: String,
        description: String?,
    ): String =
        buildString {
            append(formatAction(user, action, link, repositoryName))
            append(title.bold())
            if (!description.isNullOrEmpty()) append("\n\t\t${description.italic()}")
        }

    private fun formatAction(
        user: String,
        action: String,
        link: String,
        repositoryName: String,
    ): String = "${user.bold()} ${action.bold()} $link in project ${repositoryName}\n\n"

    private fun getFormattedAction(action: String): String =
        when (action.lowercase()) {
            "delete" -> "deleted"
            "create" -> "created"
            "update" -> "updated"
            "open" -> "opened"
            "close" -> "closed"
            "reopen" -> "reopened"
            "approved" -> "approved"
            "unapproved" -> "unapproved"
            "approval" -> "approval"
            "unapproval" -> "unapproval"
            "merge" -> "merged"
            else -> throw SkipEventException()
        }
}
