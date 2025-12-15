@file:Suppress("KotlinConstantConditions")

package net.raquezha.nuecagram.webhook

import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest

interface WebHookService {
    /**
     * Get the secret token that received hook events should be validated against.
     *
     * @return the secret token that received hook events should be validated against
     */
    fun getSecretToken(): String?

    /**
     * Set the secret token that received hook events should be validated against.
     *
     * @param secretToken the secret token to verify against
     */
    fun setSecretToken(secretToken: String?)

    /**
     * Validate the provided secret token against the reference secret token. Returns true if
     * the secret token matches, otherwise returns false.
     * Security: If no reference secret token is configured, validation always fails.
     * Uses constant-time comparison to prevent timing attacks.
     *
     * @param secretToken the token to validate
     * @return true if the secret token matches the configured token
     */
    fun isValidSecretToken(secretToken: String?): Boolean {
        val ourSecretToken: String? = getSecretToken()
        // Security: Require a secret token to be configured
        if (ourSecretToken.isNullOrBlank()) return false
        if (secretToken == null) return false
        // Use constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(
            ourSecretToken.toByteArray(Charsets.UTF_8),
            secretToken.toByteArray(Charsets.UTF_8),
        )
    }

    suspend fun handleRequest(call: ApplicationCall): EventData

    fun getMessageIdOfEvent(buildEventId: Long): String?

    fun setMessageIdOfEvent(
        buildEventId: Long,
        messageId: String,
    )

    fun clearMessageIdOfEvent(buildEventId: Long)

    /**
     * Get the Telegram message ID for a pipeline.
     *
     * @param pipelineId the pipeline ID
     * @return the Telegram message ID, or null if not tracked
     */
    fun getPipelineMessageId(pipelineId: Long): String?

    /**
     * Set the Telegram message ID for a pipeline.
     *
     * @param pipelineId the pipeline ID
     * @param messageId the Telegram message ID
     */
    fun setPipelineMessageId(
        pipelineId: Long,
        messageId: String,
    )

    /**
     * Clear the tracked message ID for a pipeline (call on completion).
     *
     * @param pipelineId the pipeline ID
     */
    fun clearPipelineMessageId(pipelineId: Long)

    // ========== Job-Only Mode Tracking ==========

    /**
     * Get the tracked pipeline data for a pipeline ID.
     * Used in job-only mode to accumulate job data.
     *
     * @param pipelineId the pipeline ID
     * @return the tracked pipeline data, or null if not tracked
     */
    fun getTrackedPipeline(pipelineId: Long): TrackedPipeline?

    /**
     * Add or update a job in the tracked pipeline.
     * Creates a new TrackedPipeline if one doesn't exist.
     *
     * @param pipelineId the pipeline ID
     * @param jobInfo the job information to add/update
     * @param metadata optional metadata to update (project name, ref, etc.)
     */
    fun addJobToTrackedPipeline(
        pipelineId: Long,
        jobInfo: JobInfo,
        metadata: PipelineMetadata? = null,
    )

    /**
     * Mark that a PipelineEvent has been received for this pipeline.
     * When true, BuildEvents should be skipped (both-enabled mode).
     *
     * @param pipelineId the pipeline ID
     */
    fun markPipelineEventReceived(pipelineId: Long)

    /**
     * Check if a PipelineEvent has been received for this pipeline.
     *
     * @param pipelineId the pipeline ID
     * @return true if PipelineEvent is handling this pipeline
     */
    fun hasPipelineEvent(pipelineId: Long): Boolean

    /**
     * Update the message ID for a tracked pipeline.
     *
     * @param pipelineId the pipeline ID
     * @param messageId the Telegram message ID
     */
    fun updateTrackedPipelineMessageId(
        pipelineId: Long,
        messageId: String,
    )

    /**
     * Clear all tracking data for a pipeline.
     *
     * @param pipelineId the pipeline ID
     */
    fun clearTrackedPipeline(pipelineId: Long)

    /**
     * Cleanup stale pipeline tracking entries.
     * Called periodically to prevent memory leaks.
     *
     * @param maxAgeMs maximum age in milliseconds (default 2 hours)
     */
    fun cleanupStaleEntries(maxAgeMs: Long = DEFAULT_STALE_ENTRY_TTL_MS)

    companion object {
        /** Default TTL for stale entries: 2 hours in milliseconds */
        const val DEFAULT_STALE_ENTRY_TTL_MS = 2L * 60 * 60 * 1000
    }
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
