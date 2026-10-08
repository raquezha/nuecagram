package net.raquezha.nuecagram.webhook

import org.gitlab4j.api.models.Build
import org.gitlab4j.api.models.BuildStatus
import org.gitlab4j.api.webhook.PipelineEvent

class PipelineMessageFormatter {

    fun formatPipelineEvent(event: PipelineEvent, mrIid: Long? = null): String {
        val status = event.objectAttributes.status
        val pipelineId = event.objectAttributes.id
        val ref = event.objectAttributes.ref
        val commitSha = event.commit?.id?.take(FormatterConstants.SHORT_SHA_LENGTH) ?: "unknown"
        val userName = event.user?.name ?: "Unknown"
        val pipelineUrl = event.getPipelineUrl()
        val projectWebUrl = event.project.webUrl
        val projectName = event.project.name

        val statusEmoji = getPipelineStatusEmoji(status)
        val statusText = getPipelineStatusText(status)
        val clickablePipeline = pipelineUrl.link("#$pipelineId")
        val mrBadge = formatMrBadge(
            event.mergeRequest?.iid ?: mrIid,
            projectWebUrl,
            event.mergeRequest?.url,
        )

        return buildString {
            append("$statusEmoji Pipeline $clickablePipeline $statusText\n")
            append("${projectName.bold()} • ${ref.bold()}$mrBadge • $commitSha\n")
            appendPipelineFallbackDetails(event)

            val builds = event.builds.orEmpty()
            if (builds.isNotEmpty()) {
                appendBuildRows(builds, event.objectAttributes.stages, projectWebUrl)
            }

            appendPipelineFooter(event, userName)
        }
    }

    fun formatUnifiedPushPipelineMessage(
        pushHeader: String,
        event: PipelineEvent,
        mrIid: Long? = null,
    ): String {
        val status = event.objectAttributes.status
        val pipelineId = event.objectAttributes.id
        val userName = event.user?.name ?: "Unknown"
        val pipelineUrl = event.getPipelineUrl()
        val projectWebUrl = event.project.webUrl
        val statusEmoji = getPipelineStatusEmoji(status)
        val statusText = getPipelineStatusText(status)
        val clickablePipeline = pipelineUrl.link("#$pipelineId")
        val mrBadge = formatMrBadge(
            event.mergeRequest?.iid ?: mrIid,
            projectWebUrl,
            event.mergeRequest?.url,
        )

        return buildString {
            append(pushHeader.trimEnd())
            append("\n\n")
            append("$statusEmoji Pipeline $clickablePipeline $statusText$mrBadge\n")

            val builds = event.builds.orEmpty()
            if (builds.isNotEmpty()) {
                appendBuildRows(builds, event.objectAttributes.stages, projectWebUrl)
            }

            appendPipelineFooter(event, userName)
        }
    }

    fun formatJobOnlyPipelineMessage(
        trackedPipeline: TrackedPipeline,
        pipelineId: Long,
    ): String {
        val projectName = trackedPipeline.projectName ?: "Unknown"
        val projectWebUrl = trackedPipeline.projectWebUrl ?: ""
        val ref = trackedPipeline.ref ?: "unknown"
        val commitSha = trackedPipeline.commitSha?.take(FormatterConstants.SHORT_SHA_LENGTH) ?: "unknown"
        val commitMessage =
            trackedPipeline.commitMessage
                ?.lines()
                ?.firstOrNull()
                ?.trim() ?: ""
        val userName = trackedPipeline.userName ?: "Unknown"
        val jobs = trackedPipeline.jobs

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

    private fun StringBuilder.appendPipelineFooter(event: PipelineEvent, userName: String) {
        val status = event.objectAttributes.status
        val duration = event.objectAttributes.duration
        if (duration != null && status in listOf("success", "failed", "canceled")) {
            append("Total: ${formatDuration(duration.toLong())} • ")
        }
        append("Triggered by ${userName.bold()}")
    }

    private data class CollapsedBuildRows(
        val buildsToShow: List<Build>,
        val hiddenPassedCount: Int,
    )

    private fun collapseBuildRows(
        sortedBuilds: List<Build>,
        stages: List<String>?,
    ): CollapsedBuildRows {
        if (sortedBuilds.size <= FormatterConstants.MAX_DISPLAY_BUILDS) {
            return CollapsedBuildRows(sortedBuilds, 0)
        }
        val passedBuilds = sortedBuilds.filter { it.status == BuildStatus.SUCCESS }
        if (passedBuilds.size <= 2) {
            return CollapsedBuildRows(sortedBuilds, 0)
        }
        val nonPassedBuilds = sortedBuilds.filterNot { it.status == BuildStatus.SUCCESS }
        val maxPassedToShow = (FormatterConstants.MAX_DISPLAY_BUILDS - nonPassedBuilds.size).coerceAtLeast(0)
        val passedToShow = passedBuilds.take(maxPassedToShow)
        val hiddenPassedCount = passedBuilds.size - passedToShow.size
        val displayed = (nonPassedBuilds + passedToShow).sortedWith(
            compareBy(
                { getStageOrder(it.stage, stages) },
                { it.id },
            ),
        )
        return CollapsedBuildRows(displayed, hiddenPassedCount)
    }

    private fun StringBuilder.appendBuildRows(
        builds: List<Build>,
        stages: List<String>?,
        projectWebUrl: String,
    ) {
        append("\n")
        val sortedBuilds = builds.sortedWith(
            compareBy(
                { getStageOrder(it.stage, stages) },
                { it.id },
            ),
        )
        val (displayedBuilds, hiddenPassedCount) = collapseBuildRows(sortedBuilds, stages)

        displayedBuilds.forEachIndexed { index, build ->
            val isLast = index == displayedBuilds.size - 1 && hiddenPassedCount == 0
            val prefix = if (isLast) "└─" else "├─"
            val buildEmoji = getBuildStatusEmoji(build.status)
            val buildName = build.name.orEmpty().escapeHtml()
            val buildUrl = "$projectWebUrl/-/jobs/${build.id}"

            val buildStatusText = formatBuildStatus(build, buildUrl)
            append("$prefix $buildEmoji $buildName$buildStatusText\n")
        }

        if (hiddenPassedCount > 0) {
            append("└─ +$hiddenPassedCount passed jobs hidden...\n")
        }
        append("\n")
    }

    private fun StringBuilder.appendPipelineFallbackDetails(event: PipelineEvent) {
        val commitTitle =
            event.commit?.title ?: event.commit
                ?.message
                ?.lines()
                ?.firstOrNull()
                ?.trim()
        if (!commitTitle.isNullOrBlank()) {
            append("💬 ${commitTitle.italic()}\n")
        }

        val stages = event.objectAttributes.stages.orEmpty()
        if (stages.isNotEmpty()) {
            append("📋 Stages: ${stages.joinToString(" → ").escapeHtml()}\n")
        }

        val source = event.objectAttributes.source
        if (!source.isNullOrBlank() && source != "push") {
            append("🚀 via ${source.escapeHtml()}\n")
        }

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
        BuildStatus.RUNNING -> formatRunningBuildStatus(build)
        BuildStatus.PENDING -> " pending"
        BuildStatus.CANCELED -> " canceled"
        BuildStatus.SKIPPED -> " skipped"
        BuildStatus.MANUAL -> " manual"
        else -> ""
    }
}

private fun formatRunningBuildStatus(build: Build): String {
    val runnerName = build.runner?.description?.takeIf(String::isNotBlank)
        ?: build.runner?.name?.takeIf(String::isNotBlank)
    val stage = build.stage?.takeIf(String::isNotBlank)
    return when {
        runnerName != null && stage != null ->
            " running on ${runnerName.escapeHtml()} (${stage.escapeHtml()})"
        runnerName != null -> " running on ${runnerName.escapeHtml()}"
        stage != null -> " running (${stage.escapeHtml()})"
        else -> " running..."
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

private fun PipelineEvent.getPipelineUrl(): String = "${project.webUrl}/-/pipelines/${objectAttributes.id}"
