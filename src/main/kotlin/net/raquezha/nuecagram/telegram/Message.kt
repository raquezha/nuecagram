package net.raquezha.nuecagram.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TelegramUser(
    @SerialName("id")
    val id: Long = 0L,
    @SerialName("is_bot")
    val isBot: Boolean = false,
    @SerialName("first_name")
    val firstName: String = "",
    @SerialName("username")
    val username: String? = null,
)

@Serializable
data class MenuButton(
    @SerialName("type")
    val type: String = "web_app",
    @SerialName("text")
    val text: String? = null,
    @SerialName("web_app")
    val webApp: WebAppInfo? = null,
)

@Serializable
data class BotCommand(
    @SerialName("command")
    val command: String,
    @SerialName("description")
    val description: String,
)

@Serializable
data class BotCommandScope(
    @SerialName("type")
    val type: String,
)

@Serializable
data class WebAppInfo(
    @SerialName("url")
    val url: String,
)

@Serializable
data class InlineKeyboardButton(
    @SerialName("text")
    val text: String,
    @SerialName("url")
    val url: String? = null,
    @SerialName("web_app")
    val webApp: WebAppInfo? = null,
    @SerialName("callback_data")
    val callbackData: String? = null,
)

@Serializable
data class InlineKeyboardMarkup(
    @SerialName("inline_keyboard")
    val inlineKeyboard: List<List<InlineKeyboardButton>>,
)

@Serializable
data class Message(
    @SerialName("chat_id")
    val chatId: String,
    @SerialName("message_id")
    val messageId: String? = null,
    @SerialName("text")
    val text: String,
    @SerialName("disable_web_page_preview")
    val disableWebPagePreview: Boolean = false,
    @SerialName("parse_mode")
    val parseMode: String = "HTML",
    @SerialName("message_thread_id")
    val threadId: Long? = null,
    @SerialName("reply_to_message_id")
    val replyToMessageId: Long? = null,
    @SerialName("reply_markup")
    val replyMarkup: InlineKeyboardMarkup? = null,
)
