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

@Suppress("LargeClass")
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

        /** Maximum length for commit titles */
        private const val MAX_COMMIT_TITLE_LENGTH = 50

        /** Maximum length for context titles */
        private const val MAX_CONTEXT_TITLE_LENGTH = 60

        /** Maximum length for note content */
        private const val MAX_NOTE_LENGTH = 300

        /** Maximum length for short descriptions */
        private const val MAX_SHORT_DESC_LENGTH = 100

        /** Maximum length for descriptions */
        private const val MAX_DESC_LENGTH = 150

        /** Maximum length for release descriptions */
        private const val MAX_RELEASE_DESC_LENGTH = 200

        /** Maximum commits to display */
        private const val MAX_DISPLAY_COMMITS = 5

        /** Short SHA length */
        private const val SHORT_SHA_LENGTH = 7
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

    private fun Date?.formatFinishedAt(): String =
        this?.let {
            logger.debug { "unformatted date: $it" }
            DATE_FORMATTER.format(it.toInstant()).also { formatted ->
                logger.debug { "formatted date: $formatted" }
            }
        } ?: "N/A"

    private fun PipelineEvent.getPipelineUrl(): String = "${project.webUrl}/-/pipelines/${objectAttributes.id}"

    private fun formatNoteEvent(event: NoteEvent): String {
        val userName = event.user?.name ?: "Unknown"
        val projectName = event.project?.name ?: "Unknown"
        val noteUrl = event.objectAttributes?.url ?: ""
        val noteContent = event.objectAttributes?.note ?: ""

        val (contextInfo, contextTitle) = extractNoteContext(event) ?: throw SkipEventException()
        val clickableNote = noteUrl.link("comment")

        return buildString {
            append("💬 New $clickableNote on $contextInfo\n")
            append("${projectName.bold()}\n")
            append("\n")

            // Show the context (issue/MR/commit title)
            append("📌 ${contextTitle.take(MAX_CONTEXT_TITLE_LENGTH).italic()}")
            if (contextTitle.length > MAX_CONTEXT_TITLE_LENGTH) append("...")
            append("\n\n")

            // Show the comment content (truncated)
            val truncatedNote = noteContent.take(MAX_NOTE_LENGTH).trim()
            append("\"${truncatedNote.escapeHtml()}\"")
            if (noteContent.length > MAX_NOTE_LENGTH) append("...")
            append("\n\n")

            append("By ${userName.bold()}")
        }
    }

    /**
     * Extracts context information from a NoteEvent based on the noteable type.
     * @return Pair of (contextInfo, contextTitle) or null if unsupported/invalid
     */
    @Suppress("CyclomaticComplexMethod")
    private fun extractNoteContext(event: NoteEvent): Pair<String, String>? {
        val noteableType = event.objectAttributes?.noteableType

        return when (noteableType) {
            ISSUE -> extractIssueNoteContext(event)
            MERGE_REQUEST -> extractMergeRequestNoteContext(event)
            COMMIT -> extractCommitNoteContext(event)
            SNIPPET, null -> {
                logger.debug { "NoteEvent with noteableType=$noteableType, skipping" }
                null
            }
            else -> {
                logger.warn { "Unknown NoteEvent noteableType: $noteableType, skipping" }
                null
            }
        }
    }

    private fun extractIssueNoteContext(event: NoteEvent): Pair<String, String>? {
        val issue =
            event.issue ?: run {
                logger.warn { "NoteEvent for ISSUE but issue object is null, skipping" }
                return null
            }
        return "Issue #${issue.iid}" to (issue.title ?: "Untitled")
    }

    private fun extractMergeRequestNoteContext(event: NoteEvent): Pair<String, String>? {
        val mr =
            event.mergeRequest ?: run {
                logger.warn { "NoteEvent for MERGE_REQUEST but mergeRequest object is null, skipping" }
                return null
            }
        return "MR !${mr.iid}" to (mr.title ?: "Untitled")
    }

    private fun extractCommitNoteContext(event: NoteEvent): Pair<String, String>? {
        val commit =
            event.commit ?: run {
                logger.warn { "NoteEvent for COMMIT but commit object is null, skipping" }
                return null
            }
        val shortSha = commit.id?.take(SHORT_SHA_LENGTH) ?: "unknown"
        val title = commit.title ?: commit.message?.lines()?.firstOrNull() ?: "No message"
        return "Commit $shortSha" to title
    }

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
        val userName = event.userName ?: "Unknown"
        val projectName = event.repository?.name ?: "Unknown"
        val projectWebUrl = event.repository?.homepage ?: ""

        // Extract tag name from ref (refs/tags/v1.0.0 -> v1.0.0)
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
            else -> "🔄" to "updated"
        }

    private fun StringBuilder.appendTagCommitInfo(
        event: TagPushEvent,
        afterSha: String,
        projectWebUrl: String,
    ) {
        if (afterSha.isNullHash()) return

        val commits = event.commits.orEmpty()
        val latestCommit = commits.firstOrNull() ?: return

        val shortSha = latestCommit.id?.take(SHORT_SHA_LENGTH) ?: afterSha.take(SHORT_SHA_LENGTH)
        val commitUrl = latestCommit.url ?: "$projectWebUrl/-/commit/${latestCommit.id}"
        val commitTitle = latestCommit.title ?: latestCommit.message?.lines()?.firstOrNull() ?: ""

        append("\n")
        append("📌 ${commitUrl.link(shortSha)}")
        if (commitTitle.isNotBlank()) {
            append(" ${commitTitle.take(MAX_COMMIT_TITLE_LENGTH).escapeHtml()}")
            if (commitTitle.length > MAX_COMMIT_TITLE_LENGTH) append("...")
        }
        append("\n")
    }

    private fun formatIssueEventMessage(event: IssueEvent): String {
        val userName = event.user?.name ?: "Unknown"
        val projectName = event.repository?.name ?: event.project?.name ?: "Unknown"
        val issueUrl = event.objectAttributes?.url ?: ""
        val issueIid = event.objectAttributes?.iid ?: issueUrl.extractIssueNumber() ?: "?"
        val issueTitle = event.objectAttributes?.title ?: "Untitled"
        val issueDescription = event.objectAttributes?.description
        val action = event.objectAttributes?.action ?: "updated"

        val (emoji, actionText) = getIssueActionDisplay(action)
        val clickableIssue = issueUrl.link("#$issueIid")

        // Get labels if available
        val labels = event.labels.orEmpty().mapNotNull { it.title }

        // Get assignees if available
        val assignees = event.assignees.orEmpty().mapNotNull { it.name }

        return buildString {
            append("$emoji Issue $clickableIssue $actionText\n")
            append("${projectName.bold()}\n")
            append("\n")
            append("📋 ${issueTitle.bold()}\n")

            appendTruncatedDescription(issueDescription, MAX_DESC_LENGTH)
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
            "open" -> "🆕" to "opened"
            "close" -> "✅" to "closed"
            "reopen" -> "🔄" to "reopened"
            "update" -> "✏️" to "updated"
            else -> "📝" to action
        }

    private fun String.extractIssueNumber(): String? = Regex(""".*/issues/(\d+)""").find(this)?.groupValues?.get(1)

    private fun formatPushEventMessage(event: PushEvent): String {
        val beforeSha = event.before ?: ""
        val afterSha = event.after ?: ""

        // Skip if this is a branch create/delete (handled by pipeline or not interesting)
        if (beforeSha.isNullHash() || afterSha.isNullHash()) {
            throw SkipEventException()
        }

        val userName = event.userName ?: "Unknown"
        val projectName = event.project?.name ?: event.repository?.name ?: "Unknown"
        val projectWebUrl = event.project?.webUrl ?: event.repository?.homepage ?: ""

        // Extract branch name from ref
        val ref = event.ref?.removePrefix("refs/heads/") ?: "unknown"
        val commits = event.commits.orEmpty()
        val commitCount = event.totalCommitsCount ?: commits.size

        // Skip if no commits (empty push)
        if (commitCount == 0) {
            throw SkipEventException()
        }

        val compareUrl = "$projectWebUrl/-/compare/$beforeSha...$afterSha"

        return buildString {
            append("📤 Push to ${ref.bold()}\n")
            append("${projectName.bold()} • ${compareUrl.link("$commitCount commit(s)")}\n")
            append("\n")
            appendPushCommits(commits)
            append("\n")
            append("Pushed by ${userName.bold()}")
        }
    }

    private fun StringBuilder.appendPushCommits(commits: List<org.gitlab4j.api.webhook.EventCommit>) {
        val displayCommits = commits.take(MAX_DISPLAY_COMMITS)
        displayCommits.forEachIndexed { index, commit ->
            val isLast = index == displayCommits.size - 1 && commits.size <= MAX_DISPLAY_COMMITS
            val prefix = if (isLast) "└─" else "├─"
            val shortSha = commit.id?.take(SHORT_SHA_LENGTH) ?: "unknown"
            val commitUrl = commit.url ?: ""
            val rawTitle = commit.title ?: commit.message?.lines()?.firstOrNull() ?: "No message"
            val title = rawTitle.take(MAX_COMMIT_TITLE_LENGTH)
            val truncatedTitle = if (rawTitle.length > MAX_COMMIT_TITLE_LENGTH) "$title..." else title
            append("$prefix ${commitUrl.link(shortSha)} ${truncatedTitle.escapeHtml()}\n")
        }

        if (commits.size > MAX_DISPLAY_COMMITS) {
            append("└─ ... and ${commits.size - MAX_DISPLAY_COMMITS} more\n")
        }
    }

    private fun formatWikiPageEvent(event: WikiPageEvent): String {
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
                append("💬 ${pageMessage.take(MAX_SHORT_DESC_LENGTH).italic()}")
                if (pageMessage.length > MAX_SHORT_DESC_LENGTH) append("...")
                append("\n")
            }

            append("\n")
            append("By ${userName.bold()}")
        }
    }

    private fun getWikiActionDisplay(action: String): Pair<String, String> =
        when (action.lowercase()) {
            "create" -> "📖" to "created"
            "update" -> "✏️" to "updated"
            "delete" -> "🗑️" to "deleted"
            else -> "📄" to action
        }

    private fun formatDeployEventMessage(event: DeploymentEvent): String {
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
                val shortSha = commitUrl.substringAfterLast("/").take(SHORT_SHA_LENGTH)
                append("\n📌 ${commitUrl.link(shortSha)}")
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
            "created" -> "🆕" to "created"
            "running" -> "🔄" to "running"
            "success" -> "✅" to "succeeded"
            "failed" -> "❌" to "failed"
            "canceled" -> "⛔" to "canceled"
            "canceling" -> "⏳" to "canceling"
            else -> "🚀" to status
        }

    private fun formatReleaseEventMessage(event: ReleaseEvent): String {
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
                val truncatedDesc = description.take(MAX_RELEASE_DESC_LENGTH).trim()
                append("${truncatedDesc.italic()}")
                if (description.length > MAX_RELEASE_DESC_LENGTH) append("...")
                append("\n")
            }

            // Show assets count if available
            val assetsCount = event.assets?.count
            if (assetsCount != null && assetsCount > 0) {
                append("\n📦 $assetsCount asset(s)")
            }
        }
    }

    private fun getReleaseActionDisplay(action: String): Pair<String, String> =
        when (action.lowercase()) {
            "create" -> "🎉" to "published"
            "update" -> "✏️" to "updated"
            "delete" -> "🗑️" to "deleted"
            else -> "📦" to action
        }

    private fun formatMergeRequestEventMessage(event: MergeRequestEvent): String {
        val data = extractMergeRequestData(event)
        val (emoji, actionText) = getMergeRequestActionDisplay(data.action, data.isDraft)
        val clickableMR = data.url.link("!${data.iid}")

        return buildString {
            append("$emoji Merge Request $clickableMR $actionText\n")
            append("${data.projectName.bold()} • ${data.sourceBranch} → ${data.targetBranch}\n")
            append("\n")

            if (data.isDraft) append("📝 ")
            append("${data.title.bold()}\n")

            appendTruncatedDescription(data.description, MAX_DESC_LENGTH)
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

    private fun StringBuilder.appendTruncatedDescription(
        description: String?,
        maxLength: Int,
    ) {
        if (!description.isNullOrBlank()) {
            val truncatedDesc = description.take(maxLength).trim()
            append("${truncatedDesc.italic()}")
            if (description.length > maxLength) append("...")
            append("\n")
        }
    }

    private fun StringBuilder.appendLabels(labels: List<String>) {
        if (labels.isNotEmpty()) {
            append("\n🏷️ ${labels.joinToString(" • ")}")
        }
    }

    private fun getMergeRequestActionDisplay(
        action: String,
        isDraft: Boolean,
    ): Pair<String, String> =
        when (action.lowercase()) {
            "open" -> if (isDraft) "📝" to "draft opened" else "🆕" to "opened"
            "close" -> "🚫" to "closed"
            "reopen" -> "🔄" to "reopened"
            "update" -> "✏️" to "updated"
            "approved" -> "👍" to "approved"
            "unapproved" -> "👎" to "unapproved"
            "merge" -> "🎉" to "merged"
            else -> "🔀" to action
        }
}
