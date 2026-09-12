package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.server.application.Application
import kotlinx.coroutines.channels.Channel
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import net.raquezha.nuecagram.db.InstallationRepository
import org.gitlab4j.api.models.Build
import org.gitlab4j.api.models.BuildStatus
import org.gitlab4j.api.webhook.BuildEvent
import org.gitlab4j.api.webhook.MergeRequestEvent
import org.gitlab4j.api.webhook.PipelineEvent
import org.gitlab4j.api.webhook.PushEvent
import org.koin.ktor.ext.inject

private data class EventProcessingContext(
    val webhookService: WebHookService,
    val installationRepository: InstallationRepository,
    val telegramService: TelegramService,
    val formatter: WebhookMessageFormatter,
    val logger: KLogger,
)

private data class MergeRequestState(
    val projectId: Long?,
    val mrIid: Long?,
    val authorUsername: String?,
    val reviewers: List<String>,
    val sourceBranch: String?,
    val lastCommitSha: String?,
    val action: String?,
)

private data class PipelineTargets(
    val usernames: List<String>,
    val isReviewer: Boolean = false,
    val mrIid: Long? = null,
)

@Suppress("TooManyFunctions")
class WebhookRequestHandler(
    private val application: Application,
    private val randomMessageProvider: RandomMessageProvider,
) {
    private val eventFilter = WebhookEventFilter()

    /** Buffered channel with capacity limit to prevent memory exhaustion */
    private val queue = Channel<EventData>(capacity = QUEUE_CAPACITY)

    companion object {
        const val PARSE_MODE = "HTML"
        const val MESSAGE_PROCESSING = "Queue started processing."
        const val MESSAGE_STOPPED = "Queue stopped processing."
        const val MESSAGE_ERROR = "Error processing webhook data."
        const val MESSAGE_SKIPPED = "This event is skipped."

        /** Maximum number of pending webhook events in the queue */
        private const val QUEUE_CAPACITY = 100

        private val PIPELINE_TERMINAL_STATUSES = listOf("success", "failed", "canceled", "skipped")
        private val JOB_TERMINAL_STATUSES = listOf("success", "failed", "canceled", "skipped")
        private val ACCEPTABLE_COMPLETED_BUILD_STATUSES = setOf(BuildStatus.SUCCESS, BuildStatus.SKIPPED)
    }

    suspend fun enqueue(eventData: EventData) {
        queue.send(eventData)
    }

    /** Close the queue channel for graceful shutdown */
    fun close() {
        queue.close()
    }

    /**
     * Convert a String to Long with warning logging on failure.
     * Returns null if conversion fails.
     */
    private fun String?.toMessageIdOrNull(
        fieldName: String,
        logger: KLogger,
    ): Long? {
        if (this == null) return null
        return this.toLongOrNull().also { result ->
            if (result == null) {
                logger.warn { "Could not convert $fieldName '$this' to Long" }
            }
        }
    }

    suspend fun processQueue() {
        val ctx = EventProcessingContext(
            webhookService = application.inject<WebHookService>().value,
            installationRepository = application.inject<InstallationRepository>().value,
            logger = application.inject<KLogger>().value,
            telegramService = application.inject<TelegramService>().value,
            formatter = application.inject<WebhookMessageFormatter>().value,
        )

        ctx.logger.debug { MESSAGE_PROCESSING }
        for (data in queue) {
            try {
                processEvent(
                    data = data,
                    ctx = ctx,
                )
            } catch (skipEx: SkipEventException) {
                ctx.logger.debug { MESSAGE_SKIPPED }
            } catch (e: Exception) {
                ctx.logger.error { "$MESSAGE_ERROR \n${e.message}" }
            }
        }
        ctx.logger.debug { MESSAGE_STOPPED }
    }

    private suspend fun processEvent(
        data: EventData,
        ctx: EventProcessingContext,
    ) {
        val event = data.event
        val installationId = data.installationId
        val chatDetails = data.chatDetails

        when (event) {
            is PipelineEvent -> {
                handlePipelineEvent(
                    installationId = installationId,
                    event = event,
                    chatDetails = chatDetails,
                    ctx = ctx,
                )
            }
            is BuildEvent -> {
                handleBuildEvent(
                    installationId = installationId,
                    event = event,
                    chatDetails = chatDetails,
                    ctx = ctx,
                )
            }
            is MergeRequestEvent -> {
                handleMergeRequestEvent(
                    installationId = installationId,
                    event = event,
                    chatDetails = chatDetails,
                    ctx = ctx,
                )
            }
            is PushEvent -> {
                handlePushEvent(
                    installationId = installationId,
                    event = event,
                    chatDetails = chatDetails,
                    ctx = ctx,
                )
            }
            else -> {
                handleGenericEvent(
                    event = data.event,
                    chatDetails = chatDetails,
                    ctx = ctx,
                )
            }
        }
    }

    private suspend fun handlePipelineEvent(
        installationId: java.util.UUID,
        event: PipelineEvent,
        chatDetails: ChatDetails,
        ctx: EventProcessingContext,
    ) {
        val attrs = event.objectAttributes ?: return
        val pipelineId = attrs.id
        val status = attrs.status

        ctx.webhookService.markPipelineEventReceived(installationId, pipelineId)
        ctx.webhookService.cleanupStaleEntries()

        val existingMessageId = ctx.webhookService.getPipelineMessageId(installationId, pipelineId)

        val messageId =
            ctx.telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                    messageId = existingMessageId,
                    text = ctx.formatter.formatEventMessage(event),
                    parseMode = PARSE_MODE,
                    disableWebPagePreview = true,
                ),
            )
        ctx.logger.debug { "Pipeline #$pipelineId: sent/updated message $messageId" }

        when (status) {
            in PIPELINE_TERMINAL_STATUSES -> {
                handleTerminalPipelineReply(
                    installationId = installationId,
                    pipelineId = pipelineId,
                    status = status,
                    event = event,
                    chatDetails = chatDetails,
                    messageId = messageId,
                    ctx = ctx,
                )
                ctx.webhookService.clearTrackedPipeline(installationId, pipelineId)
                ctx.logger.debug { "Pipeline #$pipelineId finished ($status), cleared all tracking" }
            }
            "manual" -> {
                handleManualWaitingPipelineReply(
                    installationId = installationId,
                    pipelineId = pipelineId,
                    event = event,
                    chatDetails = chatDetails,
                    messageId = messageId,
                    ctx = ctx,
                )
                ctx.webhookService.setPipelineMessageId(installationId, pipelineId, messageId)
                ctx.logger.debug { "Pipeline #$pipelineId ($status): tracking message $messageId" }
            }
            else -> {
                ctx.webhookService.setPipelineMessageId(installationId, pipelineId, messageId)
                ctx.logger.debug { "Pipeline #$pipelineId ($status): tracking message $messageId" }
            }
        }
    }

    private suspend fun handleTerminalPipelineReply(
        installationId: java.util.UUID,
        pipelineId: Long,
        status: String,
        event: PipelineEvent,
        chatDetails: ChatDetails,
        messageId: String,
        ctx: EventProcessingContext,
    ) {
        val targets = resolvePipelineTargetUsernames(installationId, status, event, ctx)
        if (targets.usernames.isNotEmpty()) {
            sendPipelineReply(
                text = formatPipelineCompletionReply(status, targets),
                chatDetails = chatDetails,
                messageId = messageId,
                ctx = ctx,
            )
            ctx.logger.debug { "Pipeline #$pipelineId: sent completion reply tagging ${targets.usernames}" }
        }
    }

    private suspend fun handleManualWaitingPipelineReply(
        installationId: java.util.UUID,
        pipelineId: Long,
        event: PipelineEvent,
        chatDetails: ChatDetails,
        messageId: String,
        ctx: EventProcessingContext,
    ) {
        if (!event.isWaitingForBlockingManualAction()) return
        if (!ctx.webhookService.tryMarkPipelineNotification(installationId, pipelineId, "manual_waiting")) return

        val targets = resolvePipelineTargetUsernames(installationId, "success", event, ctx)
        if (targets.usernames.isNotEmpty()) {
            val mrRef = targets.mrIid?.let { "!$it" } ?: "the merge request"
            val text = if (targets.isReviewer) {
                "${targets.usernames.handles()} pipeline passed; waiting for manual action. Please review $mrRef."
            } else {
                "${targets.usernames.handles()} pipeline passed; waiting for manual action."
            }
            sendPipelineReply(
                text = text,
                chatDetails = chatDetails,
                messageId = messageId,
                ctx = ctx,
            )
            ctx.logger.debug { "Pipeline #$pipelineId: sent manual-waiting reply tagging ${targets.usernames}" }
        }
    }

    private suspend fun resolvePipelineTargetUsernames(
        installationId: java.util.UUID,
        status: String,
        event: PipelineEvent,
        ctx: EventProcessingContext,
    ): PipelineTargets {
        val projectId = event.project?.id
            ?: event.mergeRequest?.targetProjectId
            ?: event.mergeRequest?.sourceProjectId
        val branch = event.objectAttributes?.ref?.removePrefix("refs/heads/")?.trim()
        val activeMr = if (projectId != null && !branch.isNullOrBlank()) {
            ctx.installationRepository.getActiveMrForBranch(installationId, projectId, branch)
        } else {
            null
        }
        val mrIid = event.extractMrIid(activeMr?.mrIid)
        val cachedParticipants = if (mrIid != null && projectId != null) {
            ctx.installationRepository.getMrParticipants(installationId, projectId, mrIid)
        } else {
            null
        }

        val rawReviewers = cachedParticipants?.reviewerUsernames.orEmpty().filter(String::isNotBlank).distinct()
        val author = cachedParticipants?.authorUsername?.takeIf(String::isNotBlank)
        val validReviewers = resolveReviewers(rawReviewers, author)

        return when {
            status == "success" && validReviewers.isNotEmpty() ->
                PipelineTargets(validReviewers, isReviewer = true, mrIid = mrIid)
            author != null ->
                PipelineTargets(listOf(author), isReviewer = false, mrIid = mrIid)
            !event.user?.username.isNullOrBlank() ->
                PipelineTargets(listOf(event.user.username), isReviewer = false, mrIid = mrIid)
            else ->
                PipelineTargets(emptyList())
        }
    }

    private fun PipelineEvent.extractMrIid(activeMrIid: Long?): Long? =
        mergeRequest?.iid
            ?: activeMrIid
            ?: objectAttributes?.ref?.let { ref ->
                Regex("""refs/merge-requests/(\d+)/""").find(ref)?.groupValues?.get(1)?.toLongOrNull()
            }

    private fun resolveReviewers(rawReviewers: List<String>, author: String?): List<String> =
        if (author != null && rawReviewers.size > 1) {
            val normalizedAuthor = author.trim().removePrefix("@")
            rawReviewers.filterNot { it.trim().removePrefix("@").equals(normalizedAuthor, ignoreCase = true) }
        } else {
            rawReviewers
        }

    private suspend fun sendPipelineReply(
        text: String,
        chatDetails: ChatDetails,
        messageId: String,
        ctx: EventProcessingContext,
    ) {
        ctx.telegramService.sendMessage(
            Message(
                chatId = chatDetails.chatId,
                threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                text = text,
                parseMode = PARSE_MODE,
                replyToMessageId = messageId.toMessageIdOrNull("replyToMessageId", ctx.logger),
            ),
        )
    }

    private fun PipelineEvent.isWaitingForBlockingManualAction(): Boolean {
        val builds = builds.orEmpty()
        return builds.any { it.isBlockingManual() } &&
            builds.filterNot { it.isManual() }.all { it.isCompletedRequired() }
    }

    private fun Build.isManual(): Boolean =
        manual == true || `when` == "manual" || status == BuildStatus.MANUAL

    private fun Build.isBlockingManual(): Boolean = isManual() && allowFailure != true

    private fun Build.isCompletedRequired(): Boolean =
        allowFailure == true || status in ACCEPTABLE_COMPLETED_BUILD_STATUSES

    private suspend fun handleBuildEvent(
        installationId: java.util.UUID,
        event: BuildEvent,
        chatDetails: ChatDetails,
        ctx: EventProcessingContext,
    ) {
        val pipelineId = event.pipelineId
        val jobId = event.buildId
        val status = event.buildStatus

        // Cleanup stale entries periodically (prevents memory leak)
        ctx.webhookService.cleanupStaleEntries()

        // Check if PipelineEvent is handling this pipeline (both-enabled mode)
        if (ctx.webhookService.hasPipelineEvent(installationId, pipelineId)) {
            ctx.logger.debug { "Skipping BuildEvent #$jobId - PipelineEvent is handling pipeline #$pipelineId" }
            return
        }

        // Job-only mode: accumulate jobs and build consolidated message
        // This is a fallback for users who only enabled "Job events" in GitLab.
        // For best experience, users should enable "Pipeline events" instead.
        val isFirstJobForPipeline = ctx.webhookService.getTrackedPipeline(installationId, pipelineId) == null
        if (isFirstJobForPipeline) {
            ctx.logger.debug {
                "Job-only mode: Pipeline #$pipelineId has no PipelineEvent. " +
                    "Using job accumulation fallback."
            }
        }
        ctx.logger.debug { "Processing BuildEvent #$jobId for pipeline #$pipelineId in job-only mode" }

        val jobInfo = event.toJobInfo(jobId, status)
        val metadata = event.toPipelineMetadata()

        // Add job to tracked pipeline
        ctx.webhookService.addJobToTrackedPipeline(installationId, pipelineId, jobInfo, metadata)

        val trackedPipeline =
            ctx.webhookService.getTrackedPipeline(installationId, pipelineId)
                ?: return logMissingTrackedPipeline(ctx.logger, pipelineId)

        val existingMessageId = trackedPipeline.messageId

        val messageId =
            ctx.telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                    messageId = existingMessageId,
                    text = ctx.formatter.formatJobOnlyPipelineMessage(trackedPipeline, pipelineId),
                    parseMode = PARSE_MODE,
                    disableWebPagePreview = true,
                ),
            )
        ctx.logger.debug {
            "Pipeline #$pipelineId (job-only): sent/updated message $messageId with ${trackedPipeline.jobs.size} jobs"
        }

        ctx.webhookService.updateTrackedPipelineMessageId(installationId, pipelineId, messageId)
        logTerminalJobOnlyPipelineIfNeeded(ctx.logger, pipelineId, trackedPipeline)
    }

    private fun BuildEvent.toJobInfo(
        jobId: Long,
        status: String?,
    ) =
        JobInfo(
            id = jobId,
            name = buildName ?: "unknown",
            stage = buildStage ?: "unknown",
            status = status ?: "unknown",
            duration = buildDuration,
            failureReason = buildFailureReason,
            allowFailure = buildAllowFailure ?: false,
        )

    private fun BuildEvent.toPipelineMetadata() =
        PipelineMetadata(
            projectName = project?.name ?: repository?.name,
            projectWebUrl = project?.webUrl ?: repository?.homepage,
            ref = ref,
            commitSha = sha,
            commitMessage = commit?.message,
            userName = user?.name,
        )

    private fun logMissingTrackedPipeline(
        logger: KLogger,
        pipelineId: Long,
    ) {
        logger.error {
            "Bug: TrackedPipeline #$pipelineId is null immediately after addJobToTrackedPipeline(). " +
                "This indicates a bug in WebHookService.addJobToTrackedPipeline()."
        }
    }

    private fun logTerminalJobOnlyPipelineIfNeeded(
        logger: KLogger,
        pipelineId: Long,
        trackedPipeline: TrackedPipeline,
    ) {
        val allJobsTerminal = trackedPipeline.jobs.values.all { job -> job.status in JOB_TERMINAL_STATUSES }
        if (allJobsTerminal && trackedPipeline.jobs.isNotEmpty()) {
            logger.debug { "Pipeline #$pipelineId (job-only): all ${trackedPipeline.jobs.size} jobs in terminal state" }
        }
    }

    private suspend fun handleGenericEvent(
        event: org.gitlab4j.api.webhook.Event,
        chatDetails: ChatDetails,
        ctx: EventProcessingContext,
    ) {
        val messageId =
            ctx.telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                    messageId = null,
                    text = ctx.formatter.formatEventMessage(event),
                    parseMode = PARSE_MODE,
                    disableWebPagePreview = true,
                ),
            )
        ctx.logger.debug { "Sent message $messageId for ${event.objectKind}" }
    }

    private suspend fun handlePushEvent(
        installationId: java.util.UUID,
        event: PushEvent,
        chatDetails: ChatDetails,
        ctx: EventProcessingContext,
    ) {
        val projectId = event.projectId ?: event.project?.id
        val branch = if (event.ref?.startsWith("refs/heads/") == true) {
            event.ref.removePrefix("refs/heads/").trim()
        } else {
            null
        }
        val afterSha = event.after

        val isBranchDelete = afterSha.isNullOrBlank() || afterSha.startsWith("00000000")
        val mrIid = if (projectId != null && !branch.isNullOrBlank()) {
            if (isBranchDelete) {
                ctx.installationRepository.clearActiveMr(installationId, projectId, branch)
                null
            } else {
                ctx.installationRepository.upsertLatestPushSha(installationId, projectId, branch, afterSha)
                val activeMr = ctx.installationRepository.getActiveMrForBranch(installationId, projectId, branch)
                activeMr?.mrIid
            }
        } else {
            null
        }

        val messageId =
            ctx.telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                    messageId = null,
                    text = ctx.formatter.formatPushEventMessage(event, mrIid),
                    parseMode = PARSE_MODE,
                    disableWebPagePreview = true,
                ),
            )
        ctx.logger.debug { "Sent message $messageId for push event on branch $branch" }
    }

    private suspend fun handleMergeRequestEvent(
        installationId: java.util.UUID,
        event: MergeRequestEvent,
        chatDetails: ChatDetails,
        ctx: EventProcessingContext,
    ) {
        val state = event.toMergeRequestState()
        val reviewerChange = ReviewerChangeExtractor.extract(event.changes)

        if (state.projectId != null && state.mrIid != null) {
            cacheMergeRequestState(installationId, state, event, ctx)
            skipRedundantMergeRequestUpdate(installationId, state, event, ctx)
        }

        val existingMessageId = if (state.projectId != null && state.mrIid != null) {
            ctx.webhookService.getMrMessageId(installationId, state.projectId, state.mrIid)
        } else {
            null
        }

        if (
            state.action == "update" &&
            existingMessageId == null &&
            reviewerChange.isEmpty() &&
            !eventFilter.hasStructuralChanges(event.changes)
        ) {
            ctx.logger.debug { "Skipping non-actionable MR update for !${state.mrIid}" }
            throw SkipEventException()
        }

        val messageId = ctx.telegramService.sendMessage(
            Message(
                chatId = chatDetails.chatId,
                threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                messageId = existingMessageId,
                text = ctx.formatter.formatEventMessage(event),
                parseMode = PARSE_MODE,
                disableWebPagePreview = true,
            ),
        )

        if (state.projectId != null && state.mrIid != null) {
            when (state.action) {
                "close", "merge", "destroy", "delete" ->
                    ctx.webhookService.clearMrMessageId(installationId, state.projectId, state.mrIid)
                else -> ctx.webhookService.setMrMessageId(installationId, state.projectId, state.mrIid, messageId)
            }
        }

        sendReviewerChangeReplies(reviewerChange, chatDetails, messageId, event, ctx)
    }

    private fun MergeRequestEvent.toMergeRequestState() = MergeRequestState(
        projectId = objectAttributes?.sourceProjectId
            ?: project?.id
            ?: objectAttributes?.targetProjectId,
        mrIid = objectAttributes?.iid,
        authorUsername = user?.username,
        reviewers = reviewers.orEmpty().mapNotNull { it.username },
        sourceBranch = objectAttributes?.sourceBranch?.trim(),
        lastCommitSha = objectAttributes?.lastCommit?.id,
        action = objectAttributes?.action?.lowercase(),
    )

    private suspend fun cacheMergeRequestState(
        installationId: java.util.UUID,
        state: MergeRequestState,
        event: MergeRequestEvent,
        ctx: EventProcessingContext,
    ) {
        val existing = ctx.installationRepository.getMrParticipants(installationId, state.projectId!!, state.mrIid!!)
        val author = if (state.action == "open") {
            state.authorUsername ?: existing?.authorUsername
        } else {
            existing?.authorUsername ?: state.authorUsername
        }

        ctx.installationRepository.upsertMrParticipants(
            installationId = installationId,
            projectId = state.projectId,
            mrIid = state.mrIid,
            authorUsername = author,
            reviewerUsernames = state.reviewers,
        )
        ctx.logger.debug {
            "MR !${state.mrIid} (project ${state.projectId}): " +
                "cached author=$author, reviewers=${state.reviewers}"
        }

        if (state.sourceBranch.isNullOrBlank()) return
        when (state.action) {
            "open", "reopen", "update", "approved", "unapproved", "approval", "unapproval" -> {
                ctx.installationRepository.upsertActiveMr(
                    installationId = installationId,
                    projectId = state.projectId,
                    sourceBranch = state.sourceBranch,
                    mrIid = state.mrIid,
                    targetProjectId = event.objectAttributes?.targetProjectId,
                    lastCommitSha = state.lastCommitSha,
                )
            }
            "close", "merge", "destroy", "delete" -> {
                ctx.installationRepository.clearActiveMr(installationId, state.projectId, state.sourceBranch)
            }
        }
    }

    private suspend fun skipRedundantMergeRequestUpdate(
        installationId: java.util.UUID,
        state: MergeRequestState,
        event: MergeRequestEvent,
        ctx: EventProcessingContext,
    ) {
        if (state.sourceBranch.isNullOrBlank()) return
        val latestPushSha = ctx.installationRepository.getLatestPushSha(
            installationId,
            state.projectId!!,
            state.sourceBranch,
        )
        if (eventFilter.evaluate(event, latestPushSha) == FilterDecision.SKIP_REDUNDANT_PUSH_MR_UPDATE) {
            ctx.logger.debug { "Skipping redundant MR update for !${state.mrIid} on branch ${state.sourceBranch}" }
            throw SkipEventException()
        }
    }

    private suspend fun sendReviewerChangeReplies(
        change: ReviewerChange,
        chatDetails: ChatDetails,
        messageId: String,
        event: MergeRequestEvent,
        ctx: EventProcessingContext,
    ) {
        val mr = "!${event.objectAttributes?.iid ?: "?"}"
        listOfNotNull(
            change.added.takeIf(List<ReviewerIdentity>::isNotEmpty)
                ?.let { "${it.labels()} were added to review $mr." },
            change.removed.takeIf(List<ReviewerIdentity>::isNotEmpty)
                ?.let { "${it.labels()} were removed from review on $mr." },
        ).forEach { text ->
            ctx.telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                    text = text,
                    parseMode = PARSE_MODE,
                    replyToMessageId = messageId.toMessageIdOrNull("replyToMessageId", ctx.logger),
                ),
            )
        }
    }

    private fun List<ReviewerIdentity>.labels(): String = joinToString(" ") { it.label }

    private fun List<String>.handles(): String =
        map { it.trim().removePrefix("@") }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ") { "@$it" }

    private fun formatPipelineCompletionReply(
        status: String,
        targets: PipelineTargets,
    ): String {
        val message = randomMessageProvider.getMessageForStatus(status)
        return if (targets.isReviewer && status == "success") {
            val mrRef = targets.mrIid?.let { "!$it" } ?: "the merge request"
            val reviewerPrompt = randomMessageProvider.getReviewerPrompt(mrRef)
            "${targets.usernames.handles()} $reviewerPrompt $message".trim()
        } else {
            "${targets.usernames.handles()} $message".trim()
        }
    }
}
