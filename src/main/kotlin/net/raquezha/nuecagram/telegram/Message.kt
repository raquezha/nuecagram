package net.raquezha.nuecagram.telegram

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WebAppInfo(
    @get:JsonProperty("url")
    @SerialName("url")
    val url: String,
)

@Serializable
data class InlineKeyboardButton(
    @get:JsonProperty("text")
    @SerialName("text")
    val text: String,
    @JsonInclude(Include.NON_NULL)
    @get:JsonProperty("url")
    @SerialName("url")
    val url: String? = null,
    @JsonInclude(Include.NON_NULL)
    @get:JsonProperty("web_app")
    @SerialName("web_app")
    val webApp: WebAppInfo? = null,
    @JsonInclude(Include.NON_NULL)
    @get:JsonProperty("callback_data")
    @SerialName("callback_data")
    val callbackData: String? = null,
)

@Serializable
data class InlineKeyboardMarkup(
    @get:JsonProperty("inline_keyboard")
    @SerialName("inline_keyboard")
    val inlineKeyboard: List<List<InlineKeyboardButton>>,
)

@Serializable
data class Message(
    @get:JsonProperty("chat_id")
    @SerialName("chat_id")
    val chatId: String,
    @JsonInclude(Include.NON_NULL)
    @get:JsonProperty("message_id")
    @SerialName("message_id")
    val messageId: String? = null,
    @get:JsonProperty("text")
    @SerialName("text")
    val text: String,
    @get:JsonProperty("disable_web_page_preview")
    @SerialName("disable_web_page_preview")
    val disableWebPagePreview: Boolean = false,
    @SerialName("parse_mode")
    @get:JsonProperty("parse_mode")
    val parseMode: String = "HTML",
    @JsonInclude(Include.NON_NULL)
    @SerialName("message_thread_id")
    @get:JsonProperty("message_thread_id")
    val threadId: Long? = null,
    @JsonInclude(Include.NON_NULL)
    @SerialName("reply_to_message_id")
    @get:JsonProperty("reply_to_message_id")
    val replyToMessageId: Long? = null,
    @JsonInclude(Include.NON_NULL)
    @SerialName("reply_markup")
    @get:JsonProperty("reply_markup")
    val replyMarkup: InlineKeyboardMarkup? = null,
)
