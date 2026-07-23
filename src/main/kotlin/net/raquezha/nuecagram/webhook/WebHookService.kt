package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import java.util.UUID
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.webhook.NuecagramHeaders.GITLAB_EVENT
import net.raquezha.nuecagram.webhook.NuecagramHeaders.GITLAB_TOKEN
import org.gitlab4j.api.utils.JacksonJson
import org.gitlab4j.api.webhook.BuildEvent
import org.gitlab4j.api.webhook.DeploymentEvent
import org.gitlab4j.api.webhook.Event
import org.gitlab4j.api.webhook.IssueEvent
import org.gitlab4j.api.webhook.JobEvent
import org.gitlab4j.api.webhook.MergeRequestEvent
import org.gitlab4j.api.webhook.NoteEvent
import org.gitlab4j.api.webhook.PipelineEvent
import org.gitlab4j.api.webhook.PushEvent
import org.gitlab4j.api.webhook.ReleaseEvent
import org.gitlab4j.api.webhook.TagPushEvent
import org.gitlab4j.api.webhook.WikiPageEvent
import java.util.concurrent.ConcurrentHashMap

class WebHookService(
    private val logger: KLogger,
    private val installationRepository: InstallationRepository,
    private val maxPayloadSizeBytes: Int = DEFAULT_MAX_PAYLOAD_SIZE,
    private val maxRequestsPerWindow: Int = DEFAULT_MAX_REQUESTS_PER_WINDOW,
    private val rateLimitWindowMs: Long = DEFAULT_RATE_LIMIT_WINDOW_MS,
) {
    private val jacksonJson: JacksonJson = JacksonJson()
    private val requestWindows = ConcurrentHashMap<String, RequestWindow>()

    companion object {
        const val DEFAULT_STALE_ENTRY_TTL_MS = 2L * 60 * 60 * 1000
        private const val DEFAULT_MAX_PAYLOAD_SIZE = 1_048_576
        private const val DEFAULT_MAX_REQUESTS_PER_WINDOW = 60
        private const val DEFAULT_RATE_LIMIT_WINDOW_MS = 60_000L
    }

    private val supportedEvents =
        setOf(
            IssueEvent.X_GITLAB_EVENT,
            JobEvent.JOB_HOOK_X_GITLAB_EVENT,
            BuildEvent.JOB_HOOK_X_GITLAB_EVENT,
            MergeRequestEvent.X_GITLAB_EVENT,
            NoteEvent.X_GITLAB_EVENT,
            PipelineEvent.X_GITLAB_EVENT,
            PushEvent.X_GITLAB_EVENT,
            TagPushEvent.X_GITLAB_EVENT,
            WikiPageEvent.X_GITLAB_EVENT,
            DeploymentEvent.X_GITLAB_EVENT,
            ReleaseEvent.X_GITLAB_EVENT,
        )

    private data class ParsedWebhookData(
        val event: Event,
        val headerEvent: String,
        val gitlabToken: String,
    )

    private data class JobEntry(
        val messageId: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private data class RequestWindow(
        var count: Int,
        val startedAt: Long,
    )

    private data class InstallationJobKey(
        val installationId: UUID,
        val jobId: Long,
    )

    private data class InstallationPipelineKey(
        val installationId: UUID,
        val pipelineId: Long,
    )

    private val runningJobsIdMap = ConcurrentHashMap<InstallationJobKey, JobEntry>()
    private val pipelineMessageIdMap = ConcurrentHashMap<InstallationPipelineKey, String>()
    private val trackedPipelines = ConcurrentHashMap<InstallationPipelineKey, TrackedPipeline>()

    suspend fun handleRequest(call: ApplicationCall): EventData {
        val clientId = call.clientId()
        if (isRateLimited(clientId)) {
            logger.warn { "Rate limit exceeded for webhook client $clientId" }
            throw WebhookRequestException(HttpStatusCode.TooManyRequests, "webhook rate limit exceeded")
        }

        val webhookData = call.getWebhookData()
        handleEvents(webhookData.headerEvent)

        val installation =
            installationRepository.resolveWebhookInstallation(webhookData.gitlabToken)
                ?: throw WebhookRequestException(HttpStatusCode.Unauthorized, "invalid X-Gitlab-Token header")

        installationRepository.confirmWebhookSecret(installation.secretId)

        if (installation.muted) {
            throw SkipEventException()
        }

        return EventData(
            installationId = installation.installationId,
            event = webhookData.event,
            headerEvent = webhookData.headerEvent,
            chatDetails = installation.chatDetails,
        )
    }

    fun isRateLimited(
        clientId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val window =
            requestWindows.compute(clientId) { _, existing ->
                when {
                    existing == null || nowMs - existing.startedAt >= rateLimitWindowMs -> RequestWindow(1, nowMs)
                    else -> {
                        existing.count += 1
                        existing
                    }
                }
            } ?: RequestWindow(1, nowMs)

        requestWindows.entries.removeIf { (_, existing) -> nowMs - existing.startedAt >= rateLimitWindowMs }
        return window.count > maxRequestsPerWindow
    }

    fun getMessageIdOfEvent(
        installationId: UUID,
        buildEventId: Long,
    ): String? = runningJobsIdMap[InstallationJobKey(installationId, buildEventId)]?.messageId

    fun setMessageIdOfEvent(
        installationId: UUID,
        buildEventId: Long,
        messageId: String,
    ) {
        runningJobsIdMap[InstallationJobKey(installationId, buildEventId)] = JobEntry(messageId)
    }

    fun clearMessageIdOfEvent(
        installationId: UUID,
        buildEventId: Long,
    ) {
        runningJobsIdMap.remove(InstallationJobKey(installationId, buildEventId))
    }

    fun getPipelineMessageId(
        installationId: UUID,
        pipelineId: Long,
    ): String? = pipelineMessageIdMap[InstallationPipelineKey(installationId, pipelineId)]

    fun setPipelineMessageId(
        installationId: UUID,
        pipelineId: Long,
        messageId: String,
    ) {
        pipelineMessageIdMap[InstallationPipelineKey(installationId, pipelineId)] = messageId
    }

    fun getTrackedPipeline(
        installationId: UUID,
        pipelineId: Long,
    ): TrackedPipeline? = trackedPipelines[InstallationPipelineKey(installationId, pipelineId)]

    fun addJobToTrackedPipeline(
        installationId: UUID,
        pipelineId: Long,
        jobInfo: JobInfo,
        metadata: PipelineMetadata?,
    ) {
        val key = InstallationPipelineKey(installationId, pipelineId)
        val existing = trackedPipelines[key]
        if (existing != null) {
            existing.putJob(jobInfo)
            // Update metadata if provided
            if (metadata != null) {
                existing.updateMetadata(metadata)
            }
        } else {
            val newTracked =
                TrackedPipeline(
                    messageId = null,
                    hasPipelineEvent = false,
                    projectName = metadata?.projectName,
                    projectWebUrl = metadata?.projectWebUrl,
                    ref = metadata?.ref,
                    commitSha = metadata?.commitSha,
                    commitMessage = metadata?.commitMessage,
                    userName = metadata?.userName,
                )
            newTracked.putJob(jobInfo)
            trackedPipelines[key] = newTracked
        }
        logger.debug {
            "Added job ${jobInfo.id} to tracked pipeline $pipelineId for installation $installationId. " +
                "Total jobs: ${trackedPipelines[key]?.jobs?.size}"
        }
    }

    fun markPipelineEventReceived(
        installationId: UUID,
        pipelineId: Long,
    ) {
        val key = InstallationPipelineKey(installationId, pipelineId)
        val existing = trackedPipelines[key]
        if (existing != null) {
            existing.setHasPipelineEvent(true)
        } else {
            trackedPipelines[key] =
                TrackedPipeline(
                    messageId = null,
                    hasPipelineEvent = true,
                )
        }
        logger.debug { "Marked pipeline $pipelineId for installation $installationId as having PipelineEvent" }
    }

    fun hasPipelineEvent(
        installationId: UUID,
        pipelineId: Long,
    ): Boolean = trackedPipelines[InstallationPipelineKey(installationId, pipelineId)]?.hasPipelineEvent == true

    fun updateTrackedPipelineMessageId(
        installationId: UUID,
        pipelineId: Long,
        messageId: String,
    ) {
        val key = InstallationPipelineKey(installationId, pipelineId)
        trackedPipelines[key]?.setMessageId(messageId)
        pipelineMessageIdMap[key] = messageId
    }

    fun clearTrackedPipeline(
        installationId: UUID,
        pipelineId: Long,
    ) {
        val key = InstallationPipelineKey(installationId, pipelineId)
        trackedPipelines.remove(key)
        pipelineMessageIdMap.remove(key)
        logger.debug { "Cleared tracking for pipeline $pipelineId in installation $installationId" }
    }

    fun resetRuntimeState() {
        requestWindows.clear()
        runningJobsIdMap.clear()
        pipelineMessageIdMap.clear()
        trackedPipelines.clear()
    }

    fun cleanupStaleEntries(maxAgeMs: Long = DEFAULT_STALE_ENTRY_TTL_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs

        // Cleanup stale tracked pipelines atomically
        var pipelinesRemoved = 0
        trackedPipelines.entries.removeIf { entry ->
            if (entry.value.createdAt < cutoff) {
                pipelineMessageIdMap.remove(entry.key)
                pipelinesRemoved++
                true
            } else {
                false
            }
        }

        // Cleanup stale job entries atomically
        var jobsRemoved = 0
        runningJobsIdMap.entries.removeIf { entry ->
            if (entry.value.createdAt < cutoff) {
                jobsRemoved++
                true
            } else {
                false
            }
        }

        val totalCleaned = pipelinesRemoved + jobsRemoved
        if (totalCleaned > 0) {
            logger.debug {
                "Cleaned up $totalCleaned stale entries " +
                    "($pipelinesRemoved pipelines, $jobsRemoved jobs)"
            }
        }
    }

    private suspend fun ApplicationCall.getWebhookData(): ParsedWebhookData {
        val body = receiveText()
        if (body.length > maxPayloadSizeBytes) {
            throw WebhookRequestException(
                HttpStatusCode.PayloadTooLarge,
                "payload too large: ${body.length} bytes (max: $maxPayloadSizeBytes)",
            )
        }

        val eventName =
            request.headers[GITLAB_EVENT]?.trim()
                ?: throw WebhookRequestException(HttpStatusCode.BadRequest, "missing '$GITLAB_EVENT' header")
        val gitlabToken =
            request.headers[GITLAB_TOKEN]?.trim()?.takeIf(String::isNotBlank)
                ?: throw WebhookRequestException(HttpStatusCode.Unauthorized, "missing 'X-Gitlab-Token' header")
        val event =
            runCatching { jacksonJson.unmarshal(Event::class.java, body) }.getOrElse {
                throw WebhookRequestException(HttpStatusCode.BadRequest, "invalid webhook payload")
            }
        event.requestUrl = request.uri
        event.requestQueryString = request.queryString()
        return ParsedWebhookData(event, eventName, gitlabToken)
    }

    private fun handleEvents(eventName: String) {
        if (eventName !in supportedEvents) {
            throw WebhookRequestException(HttpStatusCode.BadRequest, "$eventName event is not yet supported")
        }
    }

    private fun ApplicationCall.clientId(): String = request.origin.remoteHost
}

/**
 * Metadata for pipeline tracking in job-only mode.
 */
data class PipelineMetadata(
    val projectName: String?,
    val projectWebUrl: String?,
    val ref: String?,
    val commitSha: String?,
    val commitMessage: String?,
    val userName: String?,
)
