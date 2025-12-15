package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import net.raquezha.nuecagram.webhook.NuecagramHeaders.CHAT_ID
import net.raquezha.nuecagram.webhook.NuecagramHeaders.GITLAB_EVENT
import net.raquezha.nuecagram.webhook.NuecagramHeaders.SECRET_TOKEN
import net.raquezha.nuecagram.webhook.NuecagramHeaders.TOPIC_ID
import org.gitlab4j.api.GitLabApiException
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

class WebHookServiceImpl(
    private var secretToken: String? = null,
    private var logger: KLogger,
) : WebHookService {
    private val jacksonJson: JacksonJson = JacksonJson()

    companion object {
        /** Maximum allowed payload size in bytes (1 MB) to prevent DoS attacks */
        private const val MAX_PAYLOAD_SIZE = 1_048_576
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

    /**
     * Entry for tracking job/build message IDs with timestamp for cleanup.
     */
    private data class JobEntry(
        val messageId: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val runningJobsIdMap = ConcurrentHashMap<Long, JobEntry>()
    private val pipelineMessageIdMap = ConcurrentHashMap<Long, String>()
    private val trackedPipelines = ConcurrentHashMap<Long, TrackedPipeline>()

    override suspend fun handleRequest(call: ApplicationCall): EventData =
        try {
            val webhookData = call.getWebhookData()
            handleSecretToken(webhookData.headerSecretToken)
            handleEvents(webhookData.headerEvent)
            webhookData
        } catch (exception: Exception) {
            val errorMessage =
                String.format(
                    "Error processing webhook!\n[%s] %s",
                    exception.javaClass.simpleName,
                    exception.message,
                )
            logger.error { errorMessage }
            throw GitLabApiException(errorMessage)
        }

    override fun getMessageIdOfEvent(buildEventId: Long): String? = runningJobsIdMap[buildEventId]?.messageId

    override fun setMessageIdOfEvent(
        buildEventId: Long,
        messageId: String,
    ) {
        runningJobsIdMap[buildEventId] = JobEntry(messageId)
    }

    override fun clearMessageIdOfEvent(buildEventId: Long) {
        runningJobsIdMap.remove(buildEventId)
    }

    override fun getPipelineMessageId(pipelineId: Long): String? = pipelineMessageIdMap[pipelineId]

    override fun setPipelineMessageId(
        pipelineId: Long,
        messageId: String,
    ) {
        pipelineMessageIdMap[pipelineId] = messageId
    }

    override fun clearPipelineMessageId(pipelineId: Long) {
        pipelineMessageIdMap.remove(pipelineId)
    }

    // ========== Job-Only Mode Tracking ==========

    override fun getTrackedPipeline(pipelineId: Long): TrackedPipeline? = trackedPipelines[pipelineId]

    override fun addJobToTrackedPipeline(
        pipelineId: Long,
        jobInfo: JobInfo,
        metadata: PipelineMetadata?,
    ) {
        val existing = trackedPipelines[pipelineId]
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
            trackedPipelines[pipelineId] = newTracked
        }
        logger.debug { "Added job ${jobInfo.id} to tracked pipeline $pipelineId. Total jobs: ${trackedPipelines[pipelineId]?.jobs?.size}" }
    }

    override fun markPipelineEventReceived(pipelineId: Long) {
        val existing = trackedPipelines[pipelineId]
        if (existing != null) {
            existing.setHasPipelineEvent(true)
        } else {
            trackedPipelines[pipelineId] =
                TrackedPipeline(
                    messageId = null,
                    hasPipelineEvent = true,
                )
        }
        logger.debug { "Marked pipeline $pipelineId as having PipelineEvent" }
    }

    override fun hasPipelineEvent(pipelineId: Long): Boolean = trackedPipelines[pipelineId]?.hasPipelineEvent == true

    override fun updateTrackedPipelineMessageId(
        pipelineId: Long,
        messageId: String,
    ) {
        val existing = trackedPipelines[pipelineId]
        if (existing != null) {
            existing.setMessageId(messageId)
        }
        // Also update the simple map for backward compatibility
        pipelineMessageIdMap[pipelineId] = messageId
    }

    override fun clearTrackedPipeline(pipelineId: Long) {
        trackedPipelines.remove(pipelineId)
        pipelineMessageIdMap.remove(pipelineId)
        logger.debug { "Cleared tracking for pipeline $pipelineId" }
    }

    override fun cleanupStaleEntries(maxAgeMs: Long) {
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

    private fun handleSecretToken(secretToken: String?) {
        if (!isValidSecretToken(secretToken)) {
            val message = "$SECRET_TOKEN mismatch!"
            logger.error { message }
            throw GitLabApiException(message)
        }
    }

    override fun getSecretToken(): String? = this.secretToken

    override fun setSecretToken(secretToken: String?) {
        this.secretToken = secretToken
    }

    private suspend fun ApplicationCall.getWebhookData(): EventData {
        val body = receiveText()
        if (body.length > MAX_PAYLOAD_SIZE) {
            throw GitLabApiException("Payload too large: ${body.length} bytes (max: $MAX_PAYLOAD_SIZE)")
        }

        val event = jacksonJson.unmarshal(Event::class.java, body)
        event.requestUrl = request.uri
        event.requestQueryString = request.queryString()
        event.requestSecretToken = secretToken

        return EventData(
            event = event,
            headerEvent =
                request.headers[GITLAB_EVENT]?.trim()
                    ?: throw GitLabApiException("missing '$GITLAB_EVENT' header!"),
            headerSecretToken =
                request.headers[SECRET_TOKEN]?.trim(
                    ' ',
                    '[',
                    ']',
                ) ?: throw GitLabApiException("missing '$SECRET_TOKEN' header!"),
            headerChatId = request.headers[CHAT_ID]?.trim() ?: throw GitLabApiException("missing '$CHAT_ID' header!"),
            headerTopicId = request.headers[TOPIC_ID]?.trim(),
        )
    }

    private fun handleEvents(eventName: String?) {
        if (eventName !in supportedEvents) {
            val message = "$eventName event is not yet supported."
            logger.error { message }
            throw GitLabApiException(message)
        }
    }
}
