package net.raquezha.nuecagram.webhook

import java.util.concurrent.ConcurrentHashMap

/**
 * Data class representing job information for tracking in job-only webhook mode.
 * Used to accumulate job data and build a consolidated pipeline view from individual job events.
 */
data class JobInfo(
    val id: Long,
    val name: String,
    val stage: String,
    val status: String,
    val duration: Float?,
    val failureReason: String?,
    val allowFailure: Boolean,
)

/**
 * Thread-safe class for tracking pipeline state across webhook events.
 * Includes message ID, accumulated jobs, and metadata for cleanup.
 *
 * All mutable state access is synchronized via [stateLock] to ensure thread safety.
 * This is intentionally NOT a data class to avoid issues with copy()
 * creating new job maps and losing accumulated jobs.
 */
class TrackedPipeline(
    messageId: String? = null,
    hasPipelineEvent: Boolean = false,
    projectName: String? = null,
    projectWebUrl: String? = null,
    ref: String? = null,
    commitSha: String? = null,
    commitMessage: String? = null,
    userName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    private val internalJobs = ConcurrentHashMap<Long, JobInfo>()
    private val stateLock = Any()

    // All mutable state - access only through synchronized methods
    private var _messageId: String? = messageId
    private var _hasPipelineEvent: Boolean = hasPipelineEvent
    private var _projectName: String? = projectName
    private var _projectWebUrl: String? = projectWebUrl
    private var _ref: String? = ref
    private var _commitSha: String? = commitSha
    private var _commitMessage: String? = commitMessage
    private var _userName: String? = userName

    /** Read-only view of jobs */
    val jobs: Map<Long, JobInfo> get() = internalJobs.toMap()

    /** Thread-safe getter for messageId */
    val messageId: String?
        get() = synchronized(stateLock) { _messageId }

    /** Thread-safe getter for hasPipelineEvent */
    val hasPipelineEvent: Boolean
        get() = synchronized(stateLock) { _hasPipelineEvent }

    /** Thread-safe getter for projectName */
    val projectName: String?
        get() = synchronized(stateLock) { _projectName }

    /** Thread-safe getter for projectWebUrl */
    val projectWebUrl: String?
        get() = synchronized(stateLock) { _projectWebUrl }

    /** Thread-safe getter for ref */
    val ref: String?
        get() = synchronized(stateLock) { _ref }

    /** Thread-safe getter for commitSha */
    val commitSha: String?
        get() = synchronized(stateLock) { _commitSha }

    /** Thread-safe getter for commitMessage */
    val commitMessage: String?
        get() = synchronized(stateLock) { _commitMessage }

    /** Thread-safe getter for userName */
    val userName: String?
        get() = synchronized(stateLock) { _userName }

    /** Add or update a job */
    fun putJob(job: JobInfo) {
        internalJobs[job.id] = job
    }

    /** Thread-safe setter for messageId */
    fun setMessageId(value: String?) {
        synchronized(stateLock) {
            _messageId = value
        }
    }

    /** Thread-safe setter for hasPipelineEvent */
    fun setHasPipelineEvent(value: Boolean) {
        synchronized(stateLock) {
            _hasPipelineEvent = value
        }
    }

    /**
     * Update metadata from PipelineMetadata.
     * Synchronized to ensure atomic updates across all metadata fields.
     */
    fun updateMetadata(metadata: PipelineMetadata) {
        synchronized(stateLock) {
            _projectName = metadata.projectName ?: _projectName
            _projectWebUrl = metadata.projectWebUrl ?: _projectWebUrl
            _ref = metadata.ref ?: _ref
            _commitSha = metadata.commitSha ?: _commitSha
            _commitMessage = metadata.commitMessage ?: _commitMessage
            _userName = metadata.userName ?: _userName
        }
    }
}
