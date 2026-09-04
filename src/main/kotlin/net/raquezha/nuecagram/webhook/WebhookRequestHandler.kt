package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.server.application.Application
import kotlinx.coroutines.channels.Channel
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import org.gitlab4j.api.webhook.BuildEvent
import org.gitlab4j.api.webhook.MergeRequestEvent
import org.gitlab4j.api.webhook.PipelineEvent
import org.gitlab4j.api.webhook.PushEvent
import net.raquezha.nuecagram.db.InstallationRepository
import org.koin.ktor.ext.inject

private data class EventProcessingContext(
    val webhookService: WebHookService,
    val installationRepository: InstallationRepository,
    val telegramService: TelegramService,
    val formatter: WebhookMessageFormatter,
    val logger: KLogger,
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
        val pipelineId = event.objectAttributes.id
        val status = event.objectAttributes.status

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
        val mrIid = event.mergeRequest?.iid
        val projectId = event.project?.id
        val cachedParticipants = if (mrIid != null && projectId != null) {
            ctx.installationRepository.getMrParticipants(installationId, projectId, mrIid)
        } else {
            null
        }

        val targetUsernames = when {
            status == "success" && cachedParticipants?.reviewerUsernames?.isNotEmpty() == true -> {
                cachedParticipants.reviewerUsernames
            }
            cachedParticipants?.authorUsername != null -> {
                listOf(cachedParticipants.authorUsername)
            }
            event.user?.username != null -> {
                listOf(event.user.username)
            }
            else -> {
                emptyList()
            }
        }

        if (targetUsernames.isNotEmpty()) {
            val replyText = formatPipelineCompletionReply(status, targetUsernames)
            ctx.telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                    text = replyText,
                    parseMode = PARSE_MODE,
                    replyToMessageId = messageId.toMessageIdOrNull("replyToMessageId", ctx.logger),
                ),
            )
            ctx.logger.debug { "Pipeline #$pipelineId: sent completion reply tagging $targetUsernames" }
        }
    }

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
        val branch = event.ref?.removePrefix("refs/heads/")
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
        event: org.gitlab4j.api.webhook.MergeRequestEvent,
        chatDetails: ChatDetails,
        ctx: EventProcessingContext,
    ) {
        val projectId = event.project?.id
            ?: event.objectAttributes?.targetProjectId
            ?: event.objectAttributes?.sourceProjectId
        val mrIid = event.objectAttributes?.iid
        val authorUsername = event.user?.username
        val reviewers = event.reviewers.orEmpty().mapNotNull { it.username }
        val sourceBranch = event.objectAttributes?.sourceBranch
        val lastCommitSha = event.objectAttributes?.lastCommit?.id
        val action = event.objectAttributes?.action?.lowercase()

        if (projectId != null && mrIid != null) {
            ctx.installationRepository.upsertMrParticipants(
                installationId = installationId,
                projectId = projectId,
                mrIid = mrIid,
                authorUsername = authorUsername,
                reviewerUsernames = reviewers,
            )
            ctx.logger.debug { "MR !$mrIid (project $projectId): cached author=$authorUsername, reviewers=$reviewers" }

            if (!sourceBranch.isNullOrBlank()) {
                when (action) {
                    "open", "reopen", "update", "approved", "unapproved" -> {
                        ctx.installationRepository.upsertActiveMr(
                            installationId = installationId,
                            projectId = projectId,
                            sourceBranch = sourceBranch,
                            mrIid = mrIid,
                            lastCommitSha = lastCommitSha,
                        )
                    }
                    "close", "merge", "destroy", "delete" -> {
                        ctx.installationRepository.clearActiveMr(
                            installationId = installationId,
                            projectId = projectId,
                            sourceBranch = sourceBranch,
                        )
                    }
                }

                val latestPushSha = ctx.installationRepository.getLatestPushSha(installationId, projectId, sourceBranch)
                val decision = eventFilter.evaluate(event, latestPushSha)
                if (decision == FilterDecision.SKIP_REDUNDANT_PUSH_MR_UPDATE) {
                    ctx.logger.debug { "Skipping redundant MR update for !$mrIid on branch $sourceBranch" }
                    throw SkipEventException()
                }
            }
        }

        handleGenericEvent(
            event = event,
            chatDetails = chatDetails,
            ctx = ctx,
        )
    }

    private fun formatPipelineCompletionReply(
        status: String,
        usernames: List<String>,
    ): String {
        val handles = usernames.joinToString(" ") { "@$it" }
        val message = randomMessageProvider.getMessageForStatus(status)
        return "$handles $message".trim()
    }
}
