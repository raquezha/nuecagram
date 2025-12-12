@file:Suppress("KotlinConstantConditions")

package net.raquezha.nuecagram.webhook

import io.ktor.server.application.ApplicationCall

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
     * the secret token is valid or there is no reference secret token to validate against,
     * otherwise returns false.
     *
     * @param secretToken the token to validate
     * @return true if the secret token is valid or there is no reference secret token to validate against
     */
    fun isValidSecretToken(secretToken: String?): Boolean {
        val ourSecretToken: String? = getSecretToken()
        return ourSecretToken.isNullOrBlank() || ourSecretToken == secretToken
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
}
