package net.raquezha.nuecagram.telegram

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Message(
    @get:JsonProperty("chat_id")
    @SerialName("chat_id")
    val chatId: String,
    @get:JsonProperty("text")
    @SerialName("text")
    val text: String,
    @get:JsonProperty("disable_web_page_preview")
    @SerialName("disable_web_page_preview")
    val disableWebPagePreview: Boolean = false,
    @SerialName("parse_mode")
    @get:JsonProperty("parse_mode")
    val parseMode: String = "HTML",
    @SerialName("message_thread_id")
    @get:JsonProperty("message_thread_id")
    val threadId: String? = null,
)
