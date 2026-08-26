package net.raquezha.nuecagram.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TelegramUpdate(
    @SerialName("update_id")
    val updateId: Long,
    val message: Message? = null,
    @SerialName("callback_query")
    val callbackQuery: CallbackQuery? = null,
) {
    @Serializable
    data class CallbackQuery(
        val id: String,
        val from: User,
        val message: Message? = null,
        val data: String? = null,
    )

    @Serializable
    data class Message(
        @SerialName("message_id")
        val messageId: Long? = null,
        val text: String? = null,
        val chat: Chat,
        val from: User? = null,
        @SerialName("message_thread_id")
        val messageThreadId: Long? = null,
    )

    @Serializable
    data class Chat(
        val id: Long,
        val type: String,
        val title: String? = null,
    )

    @Serializable
    data class User(
        val id: Long,
    )
}
