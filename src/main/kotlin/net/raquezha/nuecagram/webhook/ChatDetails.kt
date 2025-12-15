package net.raquezha.nuecagram.webhook

/**
 * Details for the Telegram chat where messages should be sent.
 */
data class ChatDetails(
    val chatId: String,
    val topicId: String? = null,
) {
    init {
        require(chatId.isNotBlank()) { "chatId cannot be blank" }
        require(chatId.startsWith("@") || chatId.toLongOrNull() != null) {
            "chatId must be a valid Telegram chat ID (numeric) or username (@...)"
        }
    }
}
