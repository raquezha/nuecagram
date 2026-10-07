package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.server.application.Application
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val projectWebUrl: String? = null,
    val mrUrl: String? = null,
) {
    /**
     * Prefer GitLab's canonical MR URL (fork-safe), then construct from project web URL.
     * Escapes href/label the same way [WebhookMessageFormatter] link helpers do.
     */
    fun mrRef(): String {
        if (mrIid == null) return "the merge request"
        val label = "!$mrIid"
        val href = when {
            !mrUrl.isNullOrBlank() -> mrUrl.trim()
            !projectWebUrl.isNullOrBlank() -> "${projectWebUrl.trimEnd('/')}/-/merge_requests/$mrIid"
            else -> return label
        }
        return "<a href=\"${href.escapeHtml()}\">${label.escapeHtml()}</a>"
    }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}

@Suppress("TooManyFunctions")
class WebhookRequestHandler(
    private val application: Application? = null,
    private val randomMessageProvider: RandomMessageProvider,
    private val webhookService: WebHookService? = null,
    private val installationRepository: InstallationRepository? = null,
    private val telegramService: TelegramService? = null,
    private val formatter: WebhookMessageFormatter? = null,
    private val logger: KLogger? = null,
    renovateClock: java.time.Clock = java.time.Clock.systemUTC(),
) {
    private val eventFilter = WebhookEventFilter()
    private val renovateCards = RenovatePipelineCards(renovateClock)
    // ponytail: handler-local serialization; distributed send/adoption needs database coordination.
    private val deliveryMutex = Mutex()

    /** Buffered channel with capacity limit to prevent memory exhaustion */
    private val queue = Channel<EventData>(capacity = QUEUE_CAPACITY)

    companion object {
        const val PARSE_MODE = "HTML"
        const val MESSAGE_PROCESSING = "Queue started processing."
        const val MESSAGE_STOPPED = "Queue stopped processing."
        const val MESSAGE_ERROR = "Error processing webhook data."
        const val MESSAGE_SKIPPED = "This event is skipped."

        /** Maximum delivery retry attempts for transient failures before logging */
        const val MAX_EVENT_RETRIES = 3
        const val INITIAL_RETRY_BACKOFF_MS = 500L

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
        val app = application
        val ctx = EventProcessingContext(
            webhookService = webhookService ?: requireNotNull(app).inject<WebHookService>().value,
            installationRepository = installationRepository
                ?: requireNotNull(app).inject<InstallationRepository>().value,
            logger = logger ?: requireNotNull(app).inject<KLogger>().value,
            telegramService = telegramService ?: requireNotNull(app).inject<TelegramService>().value,
            formatter = formatter ?: requireNotNull(app).inject<WebhookMessageFormatter>().value,
        )

        ctx.logger.debug { MESSAGE_PROCESSING }
        kotlinx.coroutines.coroutineScope {
            val fallbackWorker = launch {
                var nextCleanupAt = 0L
                while (true) {
                    if (System.currentTimeMillis() >= nextCleanupAt) {
                        try {
                            renovateCards.cleanupStale()
                            nextCleanupAt = System.currentTimeMillis() + java.time.Duration.ofDays(1).toMillis()
                        } catch (cancellation: java.util.concurrent.CancellationException) {
                            throw cancellation
                        } catch (exception: Exception) {
                            ctx.logger.error(exception) { "Failed to clean stale Renovate CI cards" }
                        }
                    }
                    deliveryMutex.withLock {
                        try {
                            renovateCards.coordinateDelivery { deliverPendingRenovateCards(ctx) }
                        } catch (cancellation: java.util.concurrent.CancellationException) {
                            throw cancellation
                        } catch (exception: Exception) {
                            ctx.logger.error(exception) { "Failed to coordinate Renovate delivery" }
                        }
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }
            try {
                for (data in queue) {
                    deliveryMutex.withLock { processEventWithRetry(data, ctx) }
                }
            } finally {
                fallbackWorker.cancel()
            }
        }
        ctx.logger.debug { MESSAGE_STOPPED }
    }

    private suspend fun processEventWithRetry(
        data: EventData,
        ctx: EventProcessingContext,
    ) {
        var attempts = 0
        var backoffMs = INITIAL_RETRY_BACKOFF_MS
        while (attempts < MAX_EVENT_RETRIES) {
            attempts++
            try {
                val branch = when (val event = data.event) {
                    is PipelineEvent -> event.objectAttributes?.ref
                    is MergeRequestEvent -> event.objectAttributes?.sourceBranch
                    else -> null
                }
                when {
                    branch?.removePrefix("refs/heads/")?.startsWith("renovate/") == true ->
                        renovateCards.coordinateDelivery { processEvent(data = data, ctx = ctx) }
                    else -> processEvent(data = data, ctx = ctx)
                }
                return
            } catch (skipEx: SkipEventException) {
                ctx.logger.debug { MESSAGE_SKIPPED }
                return
            } catch (cancellation: java.util.concurrent.CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                val isTransient = isTransientFailure(e)
                if (isTransient && attempts < MAX_EVENT_RETRIES) {
                    ctx.logger.warn(e) {
                        "Transient error processing ${data.headerEvent} (attempt $attempts/$MAX_EVENT_RETRIES). " +
                            "Retrying in ${backoffMs}ms..."
                    }
                    kotlinx.coroutines.delay(backoffMs)
                    backoffMs *= 2
                } else {
                    ctx.logger.error(e) {
                        "$MESSAGE_ERROR after $attempts attempt(s): ${e.message}"
                    }
                    return
                }
            }
        }
    }

    private fun isTransientFailure(e: Throwable): Boolean = when (e) {
        is java.io.IOException -> true
        is io.ktor.client.plugins.ServerResponseException -> true
        is org.apache.http.HttpException -> {
            val msg = e.message.orEmpty()
            msg.contains("429") || msg.contains("500") || msg.contains("502") ||
                msg.contains("503") || msg.contains("504")
        }
        else -> false
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

        val finishedAtSeconds = attrs.finishedAt?.toInstant()?.epochSecond
        val lastFinishedAt = ctx.webhookService.getPipelineLastFinishedAt(installationId, pipelineId)
        if (lastFinishedAt != null && finishedAtSeconds != null && finishedAtSeconds < lastFinishedAt) {
            ctx.logger.warn {
                "Skipping out-of-order pipeline event for #$pipelineId " +
                    "(event finishedAt=$finishedAtSeconds < lastFinishedAt=$lastFinishedAt)"
            }
            return
        }

        ctx.webhookService.cleanupStaleEntries()
        ctx.webhookService.markPipelineEventReceived(installationId, pipelineId)

        val (mrIid, _) = findCachedMrParticipants(installationId, event, ctx)
        if (handleRenovateBranchPipeline(installationId, event, chatDetails, mrIid, ctx)) return
        val existingMessageId = resolvePipelineMessageId(installationId, pipelineId, event, mrIid, ctx)

        val messageId =
            ctx.telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                    messageId = existingMessageId,
                    text = ctx.formatter.formatEventMessage(event, mrIid),
                    parseMode = PARSE_MODE,
                    disableWebPagePreview = true,
                    disableNotification = true,
                ),
            )
        ctx.webhookService.setPipelineMessageId(installationId, pipelineId, messageId)
        ctx.logger.debug { "Pipeline #$pipelineId: sent/updated message $messageId" }

        dispatchPipelineReplies(installationId, pipelineId, status, event, chatDetails, messageId, ctx)
        ctx.logger.debug { "Pipeline #$pipelineId ($status): tracking message $messageId" }
    }

    private suspend fun resolvePipelineMessageId(
        installationId: java.util.UUID,
        pipelineId: Long,
        event: PipelineEvent,
        mrIid: Long?,
        ctx: EventProcessingContext,
    ): String? {
        val direct = ctx.webhookService.getPipelineMessageId(installationId, pipelineId)
        if (direct != null) return direct

        val projectId = event.project?.id
            ?: event.mergeRequest?.targetProjectId
            ?: event.mergeRequest?.sourceProjectId
            ?: return null

        val commitSha = event.objectAttributes?.sha?.takeIf(String::isNotBlank)
            ?: event.commit?.id?.takeIf(String::isNotBlank)

        return resolveMrPipelineMessageId(installationId, projectId, event, mrIid, ctx)
            ?: commitSha?.let { ctx.webhookService.getCommitMessageId(installationId, projectId, it) }
    }

    private suspend fun resolveMrPipelineMessageId(
        installationId: java.util.UUID,
        projectId: Long,
        event: PipelineEvent,
        mrIid: Long?,
        ctx: EventProcessingContext,
    ): String? = if (event.objectAttributes?.source == "merge_request_event" && mrIid != null) {
        ctx.webhookService.getMrMessageId(installationId, projectId, mrIid)
            ?: event.objectAttributes?.ref?.removePrefix("refs/heads/")
                ?.takeIf {
                    it.startsWith("renovate/") &&
                        event.user?.username?.matches(
                            Regex("""^(project|group)_\d+_bot.*""", RegexOption.IGNORE_CASE),
                        ) == true
                }?.let { renovateCards.findExistingMessageId(installationId, projectId, it) }
    } else {
        null
    }

    private suspend fun dispatchPipelineReplies(
        installationId: java.util.UUID,
        pipelineId: Long,
        status: String,
        event: PipelineEvent,
        chatDetails: ChatDetails,
        messageId: String,
        ctx: EventProcessingContext,
    ) {
        when (status) {
            in PIPELINE_TERMINAL_STATUSES -> handleTerminalPipelineReply(
                installationId, pipelineId, status, event, chatDetails, messageId, ctx,
            )
            "manual" -> handleManualWaitingPipelineReply(
                installationId, pipelineId, event, chatDetails, messageId, ctx,
            )
        }
    }

    private suspend fun handleRenovateBranchPipeline(
        installationId: java.util.UUID,
        event: PipelineEvent,
        chatDetails: ChatDetails,
        mrIid: Long?,
        ctx: EventProcessingContext,
    ): Boolean {
        val attrs = event.objectAttributes ?: return false
        val branch = attrs.ref?.removePrefix("refs/heads/")?.takeIf { it.startsWith("renovate/") } ?: return false
        val projectId = event.project?.id ?: return false
        val sha = attrs.sha?.takeIf(String::isNotBlank)
            ?: event.commit?.id?.takeIf(String::isNotBlank)
            ?: return false
        if (
            attrs.source !in listOf("push", "merge_request_event") ||
            event.user?.username?.matches(Regex("""^(project|group)_\d+_bot.*""", RegexOption.IGNORE_CASE)) != true
        ) return false

        val key = RenovatePipelineCards.Key(installationId, projectId, branch, sha)
        renovateCards.recordOutcome(
            key, attrs.id, ctx.formatter.formatEventMessage(event, mrIid),
            event.pipelineOutcomeTime(),
        )
        val text = renovateCards.render(key)
        val existingMrMessageId = mrIid?.let { ctx.webhookService.getMrMessageId(installationId, projectId, it) }
        val persistedMessageId = renovateCards.record(key, attrs.id, chatDetails, text)
        val cardId = existingMrMessageId ?: persistedMessageId
            ?: renovateCards.findExistingMessageId(installationId, projectId, branch)
        if (cardId != null) {
            ctx.telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                    messageId = cardId,
                    text = text,
                    parseMode = PARSE_MODE,
                    disableWebPagePreview = true,
                    disableNotification = true,
                ),
            )
            renovateCards.cancelPending(installationId, projectId, branch, cardId)
        }
        return true
    }

    private suspend fun deliverPendingRenovateCards(ctx: EventProcessingContext) {
        try {
            renovateCards.claimDue().forEach { card ->
                try {
                    val messageId = ctx.telegramService.sendMessage(
                        Message(
                            chatId = card.chatDetails.chatId,
                            threadId = card.chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                            text = card.text,
                            parseMode = PARSE_MODE,
                            disableWebPagePreview = true,
                            disableNotification = true,
                        ),
                    )
                    val latestText = persistDeliveredRenovateCard(card, messageId, ctx)
                    renovateCards.cancelPending(card.key.installationId, card.key.projectId, card.key.branch, messageId)
                    if (latestText != card.text) {
                        ctx.telegramService.sendMessage(
                            Message(
                                chatId = card.chatDetails.chatId,
                                threadId = card.chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                                messageId = messageId,
                                text = latestText,
                                parseMode = PARSE_MODE,
                                disableWebPagePreview = true,
                                disableNotification = true,
                            ),
                        )
                    }
                } catch (cancellation: java.util.concurrent.CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    ctx.logger.error(exception) { "Failed to deliver Renovate CI card ${card.key}" }
                }
            }
        } catch (cancellation: java.util.concurrent.CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            ctx.logger.error(exception) { "Failed to claim pending Renovate CI cards" }
        }
    }

    private suspend fun persistDeliveredRenovateCard(
        card: RenovatePipelineCards.DueCard,
        messageId: String,
        ctx: EventProcessingContext,
    ): String {
        while (true) {
            try {
                return renovateCards.markSent(card, messageId)
            } catch (exception: java.sql.SQLException) {
                ctx.logger.error(exception) { "Retrying persistence of delivered Renovate message $messageId" }
                kotlinx.coroutines.delay(1000)
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
        if (targets.usernames.isEmpty()) {
            return
        }

        val finishedAtSeconds = event.objectAttributes?.finishedAt?.toInstant()?.epochSecond
        val claim = ctx.webhookService.tryClaimPipelineTerminalStatus(
            installationId = installationId,
            pipelineId = pipelineId,
            status = status,
            finishedAtEpochSeconds = finishedAtSeconds,
        )

        when (claim) {
            is WebHookService.TerminalStatusClaim.AlreadyNotified -> {
                ctx.logger.debug { "Pipeline #$pipelineId already notified status $status, skipping duplicate reply" }
            }
            is WebHookService.TerminalStatusClaim.Claimed -> {
                val lastStatus = claim.previousStatus
                val isRecovery = status == "success" && lastStatus == "failed"
                val replyText = formatPipelineCompletionReply(status, targets, isRecovery)
                val isSilent = status in listOf("canceled", "skipped")

                try {
                    sendPipelineReply(
                        text = replyText,
                        chatDetails = chatDetails,
                        messageId = messageId,
                        disableNotification = isSilent,
                        ctx = ctx,
                    )
                    ctx.logger.debug {
                        "Pipeline #$pipelineId: sent completion reply ($status) tagging ${targets.usernames}"
                    }
                } catch (cancellation: java.util.concurrent.CancellationException) {
                    ctx.webhookService.rollbackPipelineTerminalStatus(installationId, pipelineId, lastStatus)
                    throw cancellation
                } catch (exception: Exception) {
                    ctx.webhookService.rollbackPipelineTerminalStatus(installationId, pipelineId, lastStatus)
                    throw exception
                }
            }
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
        if (ctx.webhookService.hasPipelineNotification(installationId, pipelineId, "manual_waiting")) return

        val targets = resolvePipelineTargetUsernames(installationId, "manual", event, ctx)
        if (targets.usernames.isNotEmpty()) {
            val mrRef = targets.mrRef()
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
            ctx.webhookService.tryMarkPipelineNotification(installationId, pipelineId, "manual_waiting")
            ctx.logger.debug { "Pipeline #$pipelineId: sent manual-waiting reply tagging ${targets.usernames}" }
        }
    }

    private fun isSuppressedPipelineEvent(event: PipelineEvent): Boolean {
        val scheduledRenovate = event.objectAttributes?.source == "schedule" &&
            event.builds.orEmpty().any { it.name == "maintain:renovate" }
        return scheduledRenovate ||
            event.user?.username?.isGitLabBotUser() == true ||
            event.user?.name?.isGitLabBotUser() == true
    }

    private suspend fun resolvePipelineTargetUsernames(
        installationId: java.util.UUID,
        status: String,
        event: PipelineEvent,
        ctx: EventProcessingContext,
    ): PipelineTargets {
        if (isSuppressedPipelineEvent(event)) {
            return PipelineTargets(emptyList())
        }

        val (mrIid, cachedParticipants) = findCachedMrParticipants(installationId, event, ctx)
        val rawReviewers = cachedParticipants?.reviewerUsernames.orEmpty()
            .filter { it.isNotBlank() && !it.isGitLabBotUser() }
            .distinct()
        val author = cachedParticipants?.authorUsername?.takeIf { it.isNotBlank() && !it.isGitLabBotUser() }
        val validReviewers = resolveReviewers(rawReviewers, author)
        val fallbackUser = event.user?.username?.takeIf { it.isNotBlank() && !it.isGitLabBotUser() }
            ?: extractCommitAuthorHandle(event)

        val pipelineId = event.objectAttributes?.id
        val isRecovery = status == "success" &&
            pipelineId != null &&
            ctx.webhookService.getPipelineLastTerminalStatus(installationId, pipelineId) == "failed"

        return selectPipelineTargets(
            status = status,
            validReviewers = validReviewers,
            author = author,
            fallbackUser = fallbackUser,
            isRecovery = isRecovery,
            event = event,
            mrIid = mrIid,
        )
    }

    private fun PipelineEvent.isDraftMr(): Boolean {
        val title = mergeRequest?.title?.trim() ?: return false
        val clean = title.lowercase()
        return clean.startsWith("draft:") ||
            clean.startsWith("[draft]") ||
            clean.startsWith("(draft)") ||
            clean.startsWith("wip:") ||
            clean.startsWith("[wip]") ||
            clean.startsWith("(wip)")
    }

    private fun selectPipelineTargets(
        status: String,
        validReviewers: List<String>,
        author: String?,
        fallbackUser: String?,
        isRecovery: Boolean,
        event: PipelineEvent,
        mrIid: Long?,
    ): PipelineTargets {
        val shouldNotifyReviewers = validReviewers.isNotEmpty() && !event.isDraftMr()
        val recoveryTarget = author ?: fallbackUser
        val projectWebUrl = event.project?.webUrl
        val mrUrl = event.mergeRequest?.url
        return when {
            status == "success" && shouldNotifyReviewers ->
                PipelineTargets(validReviewers, isReviewer = true, mrIid, projectWebUrl, mrUrl)
            status == "success" && isRecovery && recoveryTarget != null ->
                PipelineTargets(listOf(recoveryTarget), isReviewer = false, mrIid, projectWebUrl, mrUrl)
            status == "success" ->
                PipelineTargets(emptyList())
            status == "manual" && shouldNotifyReviewers ->
                PipelineTargets(validReviewers, isReviewer = true, mrIid, projectWebUrl, mrUrl)
            author != null ->
                PipelineTargets(listOf(author), isReviewer = false, mrIid, projectWebUrl, mrUrl)
            fallbackUser != null ->
                PipelineTargets(listOf(fallbackUser), isReviewer = false, mrIid, projectWebUrl, mrUrl)
            else ->
                PipelineTargets(emptyList())
        }
    }

    private suspend fun findCachedMrParticipants(
        installationId: java.util.UUID,
        event: PipelineEvent,
        ctx: EventProcessingContext,
    ): Pair<Long?, net.raquezha.nuecagram.db.models.MrParticipants?> {
        val projectId = event.project?.id
            ?: event.mergeRequest?.targetProjectId
            ?: event.mergeRequest?.sourceProjectId
        val branch = event.objectAttributes?.ref?.removePrefix("refs/heads/")?.trim()
        val activeMr = if (projectId != null && !branch.isNullOrBlank()) {
            ctx.installationRepository.getActiveMrForBranch(installationId, projectId, branch)
        } else {
            null
        }
        val targetProjectId = event.mergeRequest?.targetProjectId
            ?: event.mergeRequest?.sourceProjectId
            ?: activeMr?.targetProjectId
            ?: projectId
        val mrIid = event.extractMrIid(activeMr?.mrIid)
        val cached = if (mrIid != null && targetProjectId != null) {
            ctx.installationRepository.getMrParticipants(installationId, targetProjectId, mrIid)
                ?: if (targetProjectId != projectId && projectId != null) {
                    ctx.installationRepository.getMrParticipants(installationId, projectId, mrIid)
                } else {
                    null
                }
        } else {
            null
        }
        return mrIid to cached
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

    private fun extractCommitAuthorHandle(event: PipelineEvent): String? =
        event.commit?.author?.name?.takeIf {
            it.isNotBlank() && !it.contains(" ") && !it.isGitLabBotUser()
        } ?: event.commit?.author?.email?.takeIf(String::isNotBlank)
            ?.substringBefore("@")
            ?.takeIf { it.isNotBlank() && !it.contains(" ") && !it.isGitLabBotUser() }

    private suspend fun sendPipelineReply(
        text: String,
        chatDetails: ChatDetails,
        messageId: String,
        disableNotification: Boolean = false,
        ctx: EventProcessingContext,
    ) {
        ctx.telegramService.sendMessage(
            Message(
                chatId = chatDetails.chatId,
                threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                text = text,
                parseMode = PARSE_MODE,
                replyToMessageId = messageId.toMessageIdOrNull("replyToMessageId", ctx.logger),
                disableNotification = disableNotification,
                disableWebPagePreview = true,
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
                    disableNotification = true,
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

        val isBotPush = event.userUsername?.isGitLabBotUser() == true ||
            event.userName?.isGitLabBotUser() == true
        if (isBotPush) {
            ctx.logger.debug { "Skipping push notification bubble for bot push on $branch by ${event.userUsername}" }
            return
        }

        val isMainBranch = branch in listOf("main", "master", "production", "staging")
        val isSilentPush = !isMainBranch

        val messageId =
            ctx.telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                    messageId = null,
                    text = ctx.formatter.formatPushEventMessage(event, mrIid),
                    parseMode = PARSE_MODE,
                    disableWebPagePreview = true,
                    disableNotification = isSilentPush,
                ),
            )
        if (projectId != null) {
            if (!afterSha.isNullOrBlank()) {
                ctx.webhookService.setCommitMessageId(installationId, projectId, afterSha, messageId)
            }
            if (!branch.isNullOrBlank()) {
                ctx.webhookService.setBranchLatestMessageId(installationId, projectId, branch, messageId)
            }
            if (mrIid != null) {
                ctx.webhookService.setMrMessageId(installationId, projectId, mrIid, messageId)
            }
        }
        ctx.logger.debug { "Sent message $messageId for push event on branch $branch" }
    }

    private fun PipelineEvent.pipelineOutcomeTime(): java.time.Instant =
        objectAttributes?.finishedAt?.toInstant() ?: objectAttributes?.createdAt?.toInstant() ?: java.time.Instant.EPOCH

    private fun renovateMrKey(
        installationId: java.util.UUID,
        state: MergeRequestState,
        event: MergeRequestEvent,
    ): RenovatePipelineCards.Key? {
        val branch = state.sourceBranch?.takeIf { it.startsWith("renovate/") } ?: return null
        val projectId = state.projectId ?: return null
        if (state.mrIid == null) return null
        val username = event.user?.username ?: return null
        if (!username.matches(Regex("""^(project|group)_\d+_bot.*""", RegexOption.IGNORE_CASE))) return null
        return RenovatePipelineCards.Key(
            installationId, projectId, branch, event.objectAttributes?.lastCommit?.id.orEmpty(),
        )
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

        val renovateKey = renovateMrKey(installationId, state, event)
        if (renovateKey != null) {
            renovateCards.recordMr(renovateKey, requireNotNull(state.mrIid), ctx.formatter.formatEventMessage(event))
        }
        val existingMessageId = resolveMrMessageId(installationId, state, ctx)

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
                text = renovateKey?.let { renovateCards.render(it) } ?: ctx.formatter.formatEventMessage(event),
                parseMode = PARSE_MODE,
                disableWebPagePreview = true,
            ),
        )

        updateMrMessageTracking(installationId, state, messageId, ctx)

        sendReviewerChangeReplies(reviewerChange, chatDetails, messageId, event, ctx)
    }

    private suspend fun resolveMrMessageId(
        installationId: java.util.UUID,
        state: MergeRequestState,
        ctx: EventProcessingContext,
    ): String? {
        val projectId = state.projectId ?: return null
        val mrIid = state.mrIid ?: return null
        val renovateId = state.sourceBranch?.takeIf { it.startsWith("renovate/") }
            ?.let { renovateCards.findExistingMessageId(installationId, projectId, it) }

        return ctx.webhookService.getMrMessageId(installationId, projectId, mrIid)
            ?: renovateId
            ?: state.lastCommitSha?.takeIf(String::isNotBlank)?.let {
                ctx.webhookService.getCommitMessageId(installationId, projectId, it)
            }
            ?: state.sourceBranch?.takeIf(String::isNotBlank)?.let {
                ctx.webhookService.getBranchLatestMessageId(installationId, projectId, it)
            }
    }

    private suspend fun updateMrMessageTracking(
        installationId: java.util.UUID,
        state: MergeRequestState,
        messageId: String,
        ctx: EventProcessingContext,
    ) {
        val projectId = state.projectId ?: return
        val mrIid = state.mrIid ?: return
        when (state.action) {
            "close", "merge", "destroy", "delete" ->
                ctx.webhookService.clearMrMessageId(installationId, projectId, mrIid)
            else -> {
                ctx.webhookService.setMrMessageId(installationId, projectId, mrIid, messageId)
                if (state.sourceBranch?.startsWith("renovate/") == true) {
                    renovateCards.cancelPending(installationId, projectId, state.sourceBranch, messageId)
                }
            }
        }
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
        val addedHumans = change.added.filterNot {
            it.username?.isGitLabBotUser() == true || it.name?.isGitLabBotUser() == true
        }
        val removedHumans = change.removed.filterNot {
            it.username?.isGitLabBotUser() == true || it.name?.isGitLabBotUser() == true
        }
        listOfNotNull(
            addedHumans.takeIf(List<ReviewerIdentity>::isNotEmpty)
                ?.let { "${it.labels()} were added to review $mr." },
            removedHumans.takeIf(List<ReviewerIdentity>::isNotEmpty)
                ?.let { "${it.labels()} were removed from review on $mr." },
        ).forEach { text ->
            ctx.telegramService.sendMessage(
                Message(
                    chatId = chatDetails.chatId,
                    threadId = chatDetails.topicId.toMessageIdOrNull("topicId", ctx.logger),
                    text = text,
                    parseMode = PARSE_MODE,
                    replyToMessageId = messageId.toMessageIdOrNull("replyToMessageId", ctx.logger),
                    disableWebPagePreview = true,
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
        isRecovery: Boolean = false,
    ): String {
        return when {
            targets.isReviewer && status == "success" -> {
                val mrRef = targets.mrRef()
                val reviewerPrompt = randomMessageProvider.getReviewerPrompt(mrRef)
                val base = "${targets.usernames.handles()} $reviewerPrompt".trim()
                if (isRecovery) "$base Pipeline fixed!" else base
            }
            isRecovery && status == "success" -> {
                "${targets.usernames.handles()} Pipeline fixed! ✅".trim()
            }
            else -> {
                val message = randomMessageProvider.getMessageForStatus(status)
                val base = "${targets.usernames.handles()} $message".trim()
                if (isRecovery) "$base Pipeline fixed!" else base
            }
        }
    }

    internal fun String.isGitLabBotUser(): Boolean {
        val clean = trim().removePrefix("@").lowercase()
        if (clean.isBlank()) return true
        return clean.matches(Regex("""^(project|group)_\d+_bot.*""")) ||
            clean.matches(Regex("""^service[_-]account.*""")) ||
            clean.matches(Regex(""".*writeback.*""")) ||
            clean.matches(Regex("""^ci[_-].*""")) ||
            clean.matches(Regex(""".*token.*bot.*""")) ||
            clean in listOf(
                "gitlab-ci-token",
                "support-bot",
                "alert-bot",
                "automation-bot",
                "security-bot",
            )
    }
}
