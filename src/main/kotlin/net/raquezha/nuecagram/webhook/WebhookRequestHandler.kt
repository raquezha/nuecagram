package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.server.application.Application
import kotlinx.coroutines.channels.Channel
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import org.gitlab4j.api.webhook.BuildEvent
import org.gitlab4j.api.webhook.MergeRequestEvent
import org.gitlab4j.api.webhook.PipelineEvent
import net.raquezha.nuecagram.db.InstallationRepository
import org.koin.ktor.ext.inject

class WebhookRequestHandler(
    private val application: Application,
    private val randomMessageProvider: RandomMessageProvider,
) {
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
        val webhookService by application.inject<WebHookService>()
        val installationRepository by application.inject<InstallationRepository>()
        val logger by application.inject<KLogger>()
        val telegramService by application.inject<TelegramService>()
        val formatter: WebhookMessageFormatter by application.inject()

        logger.debug { MESSAGE_PROCESSING }
        for (data in queue) {
            try {
                processEvent(
                    data = data,
                    webhookService = webhookService,
                    installationRepository = installationRepository,
                    telegramService = telegramService,
                    formatter = formatter,
                    logger = logger,
                )
            } catch (skipEx: SkipEventException) {
                logger.debug { MESSAGE_SKIPPED }
            } catch (e: Exception) {
                logger.error { "$MESSAGE_ERROR \n${e.message}" }
            }
        }
        logger.debug { MESSAGE_STOPPED }
    }

    private suspend fun processEvent(
        data: EventData,
        webhookService: WebHookService,
        installationRepository: InstallationRepository,
        telegramService: TelegramService,
        formatter: WebhookMessageFormatter,
        logger: KLogger,
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
                    webhookService = webhookService,
                    telegramService = telegramService,
                    formatter = formatter,
                    logger = logger,
                )
            }
            is BuildEvent -> {
                handleBuildEvent(
                    installationId = installationId,
                    event = event,
                    chatDetails = chatDetails,
                    webhookService = webhookService,
                    telegramService = telegramService,
                    formatter = formatter,
                    logger = logger,
                )
            }
            is MergeRequestEvent -> {
                handleMergeRequestEvent(
                    installationId = installationId,
                    event = event,
                    chatDetails = chatDetails,
                    installationRepository = installationRepository,
                    telegramService = telegramService,
                    formatter = formatter,
                    logger = logger,
                )
            }
            else -> {
                handleGenericEvent(
                    event = data.event,
                    chatDetails = chatDetails,
                    telegramService = telegramService,
                    formatter = formatter,
                    logger = logger,
                )
            }
        }
    }

    private suspend fun handlePipelineEvent(
        installationId: java.util.UUID,
        event: PipelineEvent,
        chatDetails: ChatDetails,
        webhookService: WebHookService,
        telegramService: TelegramService,
        formatter: WebhookMessageFormatter,
        logger: KLogger,
    ) {
        val pipelineId = event.objectAttributes.id
        val status = event.objectAttributes.status

        // Mark that we received a PipelineEvent for this pipeline
        // This tells BuildEvent handler to skip (both-enabled mode)
        webhookService.markPipelineEventReceived(installationId, pipelineId)

        // Cleanup stale entries periodically
        webhookService.cleanupStaleEntries()

        val existingMessageId = webhookService.getPipelineMessageId(installationId, pipelineId)

        val messageId =
            telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", logger),
                    messageId = existingMessageId,
                    text = formatter.formatEventMessage(event),
                    parseMode = PARSE_MODE,
                    disableWebPagePreview = true,
                ),
            )
        logger.debug { "Pipeline #$pipelineId: sent/updated message $messageId" }

        when (status) {
            in PIPELINE_TERMINAL_STATUSES -> {
                // Send reply tagging the author
                val username = event.user?.username
                if (username != null) {
                    val replyText = formatPipelineCompletionReply(status, username)
                    telegramService.sendMessage(
                        Message(
                            chatId = chatDetails.chatId,
                            threadId = chatDetails.topicId.toMessageIdOrNull("topicId", logger),
                            text = replyText,
                            parseMode = PARSE_MODE,
                            replyToMessageId = messageId.toMessageIdOrNull("replyToMessageId", logger),
                        ),
                    )
                    logger.debug { "Pipeline #$pipelineId: sent completion reply tagging @$username" }
                }

                // Clear all tracking for this pipeline
                webhookService.clearTrackedPipeline(installationId, pipelineId)
                logger.debug { "Pipeline #$pipelineId finished ($status), cleared all tracking" }
            }
            else -> {
                webhookService.setPipelineMessageId(installationId, pipelineId, messageId)
                logger.debug { "Pipeline #$pipelineId ($status): tracking message $messageId" }
            }
        }
    }

    private suspend fun handleBuildEvent(
        installationId: java.util.UUID,
        event: BuildEvent,
        chatDetails: ChatDetails,
        webhookService: WebHookService,
        telegramService: TelegramService,
        formatter: WebhookMessageFormatter,
        logger: KLogger,
    ) {
        val pipelineId = event.pipelineId
        val jobId = event.buildId
        val status = event.buildStatus

        // Cleanup stale entries periodically (prevents memory leak)
        webhookService.cleanupStaleEntries()

        // Check if PipelineEvent is handling this pipeline (both-enabled mode)
        if (webhookService.hasPipelineEvent(installationId, pipelineId)) {
            logger.debug { "Skipping BuildEvent #$jobId - PipelineEvent is handling pipeline #$pipelineId" }
            return
        }

        // Job-only mode: accumulate jobs and build consolidated message
        // This is a fallback for users who only enabled "Job events" in GitLab.
        // For best experience, users should enable "Pipeline events" instead.
        val isFirstJobForPipeline = webhookService.getTrackedPipeline(installationId, pipelineId) == null
        if (isFirstJobForPipeline) {
            logger.debug {
                "Job-only mode: Pipeline #$pipelineId has no PipelineEvent. " +
                    "Using job accumulation fallback."
            }
        }
        logger.debug { "Processing BuildEvent #$jobId for pipeline #$pipelineId in job-only mode" }

        val jobInfo = event.toJobInfo(jobId, status)
        val metadata = event.toPipelineMetadata()

        // Add job to tracked pipeline
        webhookService.addJobToTrackedPipeline(installationId, pipelineId, jobInfo, metadata)

        val trackedPipeline =
            webhookService.getTrackedPipeline(installationId, pipelineId)
                ?: return logMissingTrackedPipeline(logger, pipelineId)

        val existingMessageId = trackedPipeline.messageId

        val messageId =
            telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", logger),
                    messageId = existingMessageId,
                    text = formatter.formatJobOnlyPipelineMessage(trackedPipeline, pipelineId),
                    parseMode = PARSE_MODE,
                    disableWebPagePreview = true,
                ),
            )
        logger.debug { "Pipeline #$pipelineId (job-only): sent/updated message $messageId with ${trackedPipeline.jobs.size} jobs" }

        webhookService.updateTrackedPipelineMessageId(installationId, pipelineId, messageId)
        logTerminalJobOnlyPipelineIfNeeded(logger, pipelineId, trackedPipeline)
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
        telegramService: TelegramService,
        formatter: WebhookMessageFormatter,
        logger: KLogger,
    ) {
        val messageId =
            telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", logger),
                    messageId = null,
                    text = formatter.formatEventMessage(event),
                    parseMode = PARSE_MODE,
                    disableWebPagePreview = true,
                ),
            )
        logger.debug { "Sent message $messageId for ${event.objectKind}" }
    }

    private suspend fun handleMergeRequestEvent(
        installationId: java.util.UUID,
        event: org.gitlab4j.api.webhook.MergeRequestEvent,
        chatDetails: ChatDetails,
        installationRepository: InstallationRepository,
        telegramService: TelegramService,
        formatter: WebhookMessageFormatter,
        logger: KLogger,
    ) {
        val projectId = event.project?.id ?: event.objectAttributes?.targetProjectId
        val mrIid = event.objectAttributes?.iid
        val authorUsername = event.user?.username
        val reviewers = event.reviewers.orEmpty().mapNotNull { it.username }

        if (projectId != null && mrIid != null) {
            installationRepository.upsertMrParticipants(
                installationId = installationId,
                projectId = projectId,
                mrIid = mrIid,
                authorUsername = authorUsername,
                reviewerUsernames = reviewers,
            )
            logger.debug { "MR !$mrIid (project $projectId): cached author=$authorUsername, reviewers=$reviewers" }
        }

        handleGenericEvent(
            event = event,
            chatDetails = chatDetails,
            telegramService = telegramService,
            formatter = formatter,
            logger = logger,
        )
    }

    private fun formatPipelineCompletionReply(
        status: String,
        username: String,
    ): String {
        val message = randomMessageProvider.getMessageForStatus(status)
        return "@$username $message"
    }
}
