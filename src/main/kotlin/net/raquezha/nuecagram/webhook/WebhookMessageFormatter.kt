package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import org.gitlab4j.api.GitLabApiException
import org.gitlab4j.api.models.Build
import org.gitlab4j.api.models.BuildStatus
import org.gitlab4j.api.utils.UrlEncoder.urlEncode
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

class WebhookMessageFormatter {
    private val logger by inject<KLogger>(KLogger::class.java)

    companion object {
        /** Default timezone for date formatting. Override via system property 'nuecagram.timezone' */
        private val DEFAULT_TIMEZONE: TimeZone =
            TimeZone.getTimeZone(
                System.getProperty("nuecagram.timezone", "Asia/Manila"),
            )

        /** Thread-safe date formatter for finished timestamps */
        private val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter
                .ofPattern("hh:mm a 'on' MMMM dd, yyyy")
                .withZone(ZoneId.of(DEFAULT_TIMEZONE.id))

        /** Seconds per hour for duration formatting */
        private const val SECONDS_PER_HOUR = 3600L

        /** Seconds per minute for duration formatting */
        private const val SECONDS_PER_MINUTE = 60L
    }

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

    /**
     * Escape HTML special characters to prevent XSS and broken formatting.
     */
    private fun String.escapeHtml(): String =
        this
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun String.bold() = "<b>${this.escapeHtml()}</b>"

    private fun String.italic() = "<i>${this.escapeHtml()}</i>"

    private fun String.italicBold() = this.escapeHtml().let { "<b><i>$it</i></b>" }

    private fun String.link(label: String) = "<a href=\"$this\">${label.escapeHtml()}</a>"

    private fun String.isNullHash(): Boolean = this == "0000000000000000000000000000000000000000"

    private fun throwUnsupportedEventException(event: Event): Nothing {
        val message = "Unsupported event object_kind, object_kind=${event.objectKind}"
        logger.error { message }
        throw GitLabApiException(message)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun formatBuildEventMessage(event: BuildEvent): String {
        // This method is no longer called directly for job-only mode
        // Job-only mode uses formatJobOnlyPipelineMessage instead
        // This is kept for backward compatibility if formatEventMessage is called with BuildEvent
        throw SkipEventException()
    }

    /**
     * Format a consolidated pipeline message from accumulated job events (job-only webhook mode).
     * This creates a pipeline-like view from individual BuildEvents.
     */
    fun formatJobOnlyPipelineMessage(
        trackedPipeline: TrackedPipeline,
        pipelineId: Long,
    ): String {
        val projectName = trackedPipeline.projectName ?: "Unknown"
        val projectWebUrl = trackedPipeline.projectWebUrl ?: ""
        val ref = trackedPipeline.ref ?: "unknown"
        val commitSha = trackedPipeline.commitSha?.take(7) ?: "unknown"
        val commitMessage =
            trackedPipeline.commitMessage
                ?.lines()
                ?.firstOrNull()
                ?.trim() ?: ""
        val userName = trackedPipeline.userName ?: "Unknown"
        val jobs = trackedPipeline.jobs

        // Determine overall pipeline status from jobs
        val pipelineStatus = derivePipelineStatusFromJobs(jobs.values)
        val statusEmoji = getPipelineStatusEmoji(pipelineStatus)
        val statusText = getPipelineStatusText(pipelineStatus)

        val pipelineUrl = "$projectWebUrl/-/pipelines/$pipelineId"
        val clickablePipeline = pipelineUrl.link("#$pipelineId")

        return buildString {
            append("$statusEmoji Pipeline $clickablePipeline $statusText\n")
            append("${projectName.bold()} • ${ref.bold()} • $commitSha\n")

            if (commitMessage.isNotEmpty()) {
                append("💬 ${commitMessage.italic()}\n")
            }
            append("\n")

            // Sort jobs by stage then by id
            val sortedJobs =
                jobs.values.sortedWith(
                    compareBy({ it.stage }, { it.id }),
                )

            sortedJobs.forEachIndexed { index, job ->
                val isLast = index == sortedJobs.size - 1
                val prefix = if (isLast) "└─" else "├─"
                val jobEmoji = getJobInfoStatusEmoji(job.status)
                val jobUrl = "$projectWebUrl/-/jobs/${job.id}"
                val jobStatusText = formatJobInfoStatus(job, jobUrl)
                append("$prefix $jobEmoji ${job.name}$jobStatusText\n")
            }
            append("\n")

            // Show total duration if available and pipeline is in terminal state
            val totalDuration =
                jobs.values
                    .mapNotNull { it.duration }
                    .sum()
                    .toLong()
            if (totalDuration > 0 && pipelineStatus in listOf("success", "failed", "canceled")) {
                append("Total: ${formatDuration(totalDuration)} • ")
            }
            append("Triggered by ${userName.bold()}")
        }
    }

    /**
     * Derive the overall pipeline status from accumulated jobs.
     */
    private fun derivePipelineStatusFromJobs(jobs: Collection<JobInfo>): String =
        when {
            jobs.isEmpty() -> "pending"
            jobs.any { it.status == "failed" && !it.allowFailure } -> "failed"
            jobs.any { it.status == "running" } -> "running"
            jobs.any { it.status == "pending" || it.status == "created" } -> "pending"
            jobs.any { it.status == "canceled" } -> "canceled"
            jobs.all {
                it.status == "success" ||
                    it.status == "skipped" ||
                    (it.status == "failed" && it.allowFailure)
            } -> "success"
            else -> "running"
        }

    /**
     * Get emoji for JobInfo status (string-based, for job-only mode).
     */
    private fun getJobInfoStatusEmoji(status: String): String =
        when (status.lowercase()) {
            "created" -> "🆕"
            "pending" -> "⏳"
            "running" -> "🔄"
            "success" -> "✅"
            "failed" -> "❌"
            "canceled" -> "⛔"
            "skipped" -> "⏭️"
            "manual" -> "👆"
            else -> "❓"
        }

    /**
     * Format status text for JobInfo (job-only mode).
     */
    private fun formatJobInfoStatus(
        job: JobInfo,
        jobUrl: String,
    ): String =
        when (job.status.lowercase()) {
            "success" -> if (job.duration != null) " (${formatDuration(job.duration.toLong())})" else ""
            "failed" -> {
                val reason = if (!job.failureReason.isNullOrBlank()) " (${job.failureReason})" else ""
                " ${jobUrl.link("View Logs")}$reason"
            }
            "running" -> " running..."
            "pending" -> " pending"
            "created" -> " created"
            "canceled" -> " canceled"
            "skipped" -> " skipped"
            "manual" -> " manual"
            else -> ""
        }

    private fun BuildEvent.getBuildStatusEmoji(): String =
        when (buildStatus) {
            "created" -> "✨ "
            "pending" -> "⏳ "
            "running" -> "\uD83D\uDFE2 "
            "success" -> "✅ "
            "failed" -> "❌ "
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
            DATE_FORMATTER.format(it.toInstant()).also { formatted ->
                logger.debug { "formatted date: $formatted" }
            }
        } ?: "N/A"

    private fun PipelineEvent.getPipelineUrl(): String = "${project.webUrl}/-/pipelines/${objectAttributes.id}"

    private fun formatNoteEvent(event: NoteEvent): String {
        val randomCommentMessage = RandomCommentMessage()
        val randomMessage = randomCommentMessage.getRandomComment()

        val noteableType = event.objectAttributes.noteableType
        val result: String? =
            when (noteableType) {
                ISSUE -> {
                    val issue = event.issue
                    if (issue == null) {
                        logger.warn { "NoteEvent for ISSUE but issue object is null, skipping" }
                        null
                    } else {
                        event.generateNoteMessage(
                            randomMessage = randomMessage,
                            url = event.getUrl("issue"),
                            description = "Issue: ${issue.title}",
                        )
                    }
                }
                MERGE_REQUEST -> {
                    val mergeRequest = event.mergeRequest
                    if (mergeRequest == null) {
                        logger.warn { "NoteEvent for MERGE_REQUEST but mergeRequest object is null, skipping" }
                        null
                    } else {
                        event.generateNoteMessage(
                            randomMessage = randomMessage,
                            url = event.getUrl("merge request"),
                            description = "Merge Request: ${mergeRequest.title}",
                        )
                    }
                }
                COMMIT -> {
                    val commit = event.commit
                    if (commit == null) {
                        logger.warn { "NoteEvent for COMMIT but commit object is null, skipping" }
                        null
                    } else {
                        event.generateNoteMessage(
                            randomMessage = randomMessage,
                            url = event.getUrl("commit"),
                            description = "Commit Message: ${commit.message}",
                        )
                    }
                }
                SNIPPET, null -> {
                    logger.debug { "NoteEvent with noteableType=$noteableType, skipping" }
                    null
                }
                else -> {
                    logger.warn { "Unknown NoteEvent noteableType: $noteableType, skipping" }
                    null
                }
            }

        return result ?: throw SkipEventException()
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
        val pipelineId = event.objectAttributes.id
        val ref = event.objectAttributes.ref
        val commitSha = event.commit?.id?.take(7) ?: "unknown"
        val userName = event.user?.name ?: "Unknown"
        val pipelineUrl = event.getPipelineUrl()
        val projectWebUrl = event.project.webUrl
        val projectName = event.project.name

        val statusEmoji = getPipelineStatusEmoji(status)
        val statusText = getPipelineStatusText(status)
        val clickablePipeline = pipelineUrl.link("#$pipelineId")

        return buildString {
            append("$statusEmoji Pipeline $clickablePipeline $statusText\n")
            append("${projectName.bold()} • ${ref.bold()} • $commitSha\n")

            // GitLab sends job data in 'builds' array, not 'jobs'
            val builds = event.builds.orEmpty()
            if (builds.isNotEmpty()) {
                append("\n")
                val sortedBuilds =
                    builds.sortedWith(
                        compareBy(
                            { getStageOrder(it.stage, event.objectAttributes.stages) },
                            { it.id },
                        ),
                    )

                sortedBuilds.forEachIndexed { index, build ->
                    val isLast = index == sortedBuilds.size - 1
                    val prefix = if (isLast) "└─" else "├─"
                    val buildEmoji = getBuildStatusEmoji(build.status)
                    val buildName = build.name
                    val buildUrl = "$projectWebUrl/-/jobs/${build.id}"

                    val buildStatusText = formatBuildStatus(build, buildUrl)
                    append("$prefix $buildEmoji $buildName$buildStatusText\n")
                }
                append("\n")
            } else {
                // Enhanced display when no job details available
                // Show commit message for context
                val commitTitle =
                    event.commit?.title ?: event.commit
                        ?.message
                        ?.lines()
                        ?.firstOrNull()
                        ?.trim()
                if (!commitTitle.isNullOrBlank()) {
                    append("💬 ${commitTitle.italic()}\n")
                }

                // Show stages if available
                val stages = event.objectAttributes.stages.orEmpty()
                if (stages.isNotEmpty()) {
                    append("📋 Stages: ${stages.joinToString(" → ")}\n")
                }

                // Show pipeline source if not a simple push
                val source = event.objectAttributes.source
                if (!source.isNullOrBlank() && source != "push") {
                    append("🚀 via $source\n")
                }

                // Show merge request context if available
                val mergeRequest = event.mergeRequest
                if (mergeRequest != null) {
                    val mrTitle = mergeRequest.title
                    val mrUrl = mergeRequest.url
                    if (!mrTitle.isNullOrBlank() && !mrUrl.isNullOrBlank()) {
                        append("🔀 MR: ${mrUrl.link(mrTitle)}\n")
                    }
                }

                append("\n")
            }

            val duration = event.objectAttributes.duration
            if (duration != null && status in listOf("success", "failed", "canceled")) {
                append("Total: ${formatDuration(duration.toLong())} • ")
            }
            append("Triggered by ${userName.bold()}")
        }
    }

    private fun getPipelineStatusEmoji(status: String): String =
        when (status) {
            "pending" -> "⏳"
            "running" -> "🔄"
            "success" -> "✅"
            "failed" -> "❌"
            "canceled" -> "⛔"
            "skipped" -> "⏭️"
            "manual" -> "👆"
            "scheduled" -> "🕐"
            else -> "❓"
        }

    private fun getPipelineStatusText(status: String): String =
        when (status) {
            "pending" -> "pending"
            "running" -> "running"
            "success" -> "passed"
            "failed" -> "failed"
            "canceled" -> "canceled"
            "skipped" -> "skipped"
            "manual" -> "manual"
            "scheduled" -> "scheduled"
            else -> status
        }

    private fun getBuildStatusEmoji(status: BuildStatus?): String =
        when (status) {
            BuildStatus.CREATED -> "🆕"
            BuildStatus.PENDING -> "⏳"
            BuildStatus.RUNNING -> "🔄"
            BuildStatus.SUCCESS -> "✅"
            BuildStatus.FAILED -> "❌"
            BuildStatus.CANCELED -> "⛔"
            BuildStatus.SKIPPED -> "⏭️"
            BuildStatus.MANUAL -> "👆"
            else -> "❓"
        }

    private fun formatBuildStatus(
        build: Build,
        buildUrl: String,
    ): String {
        val status = build.status ?: return ""
        val duration = build.duration

        return when (status) {
            BuildStatus.SUCCESS -> {
                if (duration != null) " (${formatDuration(duration.toLong())})" else ""
            }
            BuildStatus.FAILED -> {
                " ${buildUrl.link("View Logs")}"
            }
            BuildStatus.RUNNING -> " running..."
            BuildStatus.PENDING -> " pending"
            BuildStatus.CANCELED -> " canceled"
            BuildStatus.SKIPPED -> " skipped"
            BuildStatus.MANUAL -> " manual"
            else -> ""
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / SECONDS_PER_HOUR
        val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val secs = seconds % SECONDS_PER_MINUTE

        return when {
            hours > 0 -> "${hours}h ${minutes}m ${secs}s"
            minutes > 0 -> "${minutes}m ${secs}s"
            else -> "${secs}s"
        }
    }

    private fun getStageOrder(
        stage: String?,
        stages: List<String>?,
    ): Int {
        if (stage == null || stages == null) return Int.MAX_VALUE
        val index = stages.indexOf(stage)
        return if (index >= 0) index else Int.MAX_VALUE
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

    @Suppress("UNUSED_PARAMETER")
    private fun formatPushEventMessage(event: PushEvent): String {
        // Skip push events - pipeline consolidation handles notifications
        throw SkipEventException()
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
