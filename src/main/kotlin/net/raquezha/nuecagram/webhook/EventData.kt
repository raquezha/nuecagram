package net.raquezha.nuecagram.webhook

import net.raquezha.nuecagram.webhook.NuecagramHeaders.CHAT_ID
import net.raquezha.nuecagram.webhook.NuecagramHeaders.GITLAB_EVENT
import net.raquezha.nuecagram.webhook.NuecagramHeaders.SECRET_TOKEN
import net.raquezha.nuecagram.webhook.NuecagramHeaders.TOPIC_ID
import org.gitlab4j.api.webhook.Event

data class EventData(
    val event: Event,
    val headerEvent: String,
    val headerSecretToken: String,
    val headerChatId: String,
    val headerTopicId: String?,
) {
    fun chatDetails() =
        ChatDetails(
            chatId = headerChatId,
            topicId = headerTopicId,
        )

    fun log(): String {
        val headerText = "Webhook Headers"
        val values =
            listOf(
                GITLAB_EVENT to event.objectKind, // Replace with a descriptive name
                SECRET_TOKEN to headerSecretToken, // Replace with actual header name (if applicable)
                CHAT_ID to headerChatId, // Replace with actual header name (if applicable)
                TOPIC_ID to headerTopicId,
            )
        // Calculate the maximum width needed
        val maxKeyLength = values.maxOf { it.first.length }
        val maxValueLength = values.maxOf { it.second?.length ?: 0 }
        val totalWidth = maxKeyLength + maxValueLength + 5 // Padding, separators, spaces

        // Center the header text and fill with '-'
        val preHeader = "\n" + "_".repeat(totalWidth + 2)
        val header = "\n|%-${totalWidth}s|\n".format(headerText.center(totalWidth, '-'))
        val valuePattern = "| %-${maxKeyLength}s | %-${maxValueLength}s |\n"
        val customSeparator1 = "|${"_".repeat(totalWidth)}|\n" // Example custom separator
        val customSeparator2 = "${"‾".repeat(totalWidth + 2)}\n" // Example custom separator

        // Format the values with null handling
        val formattedValues =
            values.joinToString("") { pair ->
                val (key, value) = pair
                valuePattern.format(key, value ?: "") // Use empty string for null values
            }

        return preHeader + header + formattedValues + customSeparator1 + customSeparator2
    }

    private fun String.center(
        width: Int,
        fillChar: Char = ' ',
    ): String {
        if (this.length >= width) return this
        val leftPadding = (width - this.length) / 2
        val rightPadding = width - this.length - leftPadding
        return fillChar.toString().repeat(leftPadding) + this + fillChar.toString().repeat(rightPadding)
    }
}
