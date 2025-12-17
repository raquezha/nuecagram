package net.raquezha.nuecagram.webhook

/**
 * Provides random messages for pipeline completion notifications.
 * Each category returns a randomly selected message appropriate for the pipeline status.
 */
interface RandomMessageProvider {
    /**
     * Get a random success message for when a pipeline passes.
     */
    fun getSuccessMessage(): String

    /**
     * Get a random failure message for when a pipeline fails.
     */
    fun getFailedMessage(): String

    /**
     * Get a random canceled message for when a pipeline is canceled.
     */
    fun getCanceledMessage(): String

    /**
     * Get a random skipped message for when a pipeline is skipped.
     */
    fun getSkippedMessage(): String

    /**
     * Get a message based on the pipeline status.
     * Falls back to a generic message for unknown statuses.
     */
    fun getMessageForStatus(status: String): String =
        when (status) {
            "success" -> getSuccessMessage()
            "failed" -> getFailedMessage()
            "canceled" -> getCanceledMessage()
            "skipped" -> getSkippedMessage()
            else -> "Pipeline finished!"
        }
}
