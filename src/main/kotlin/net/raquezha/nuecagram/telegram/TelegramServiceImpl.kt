package net.raquezha.nuecagram.telegram

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.raquezha.nuecagram.ConfigWithSecrets
import org.apache.http.HttpException

private const val TELEGRAM_API_BASE_URL = "https://api.telegram.org/bot"
private const val METHOD_GET_ME = "getMe"
private const val METHOD_SET_WEBHOOK = "setWebhook"
private const val METHOD_SET_CHAT_MENU_BUTTON = "setChatMenuButton"
private const val METHOD_GET_CHAT_MEMBER = "getChatMember"
private const val METHOD_SET_MY_COMMANDS = "setMyCommands"
private const val METHOD_SEND_MESSAGE = "sendMessage"
private const val METHOD_EDIT_MESSAGE_TEXT = "editMessageText"
private const val METHOD_ANSWER_CALLBACK_QUERY = "answerCallbackQuery"

private val telegramJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
private data class TelegramApiResponse<T>(
    val ok: Boolean = false,
    val result: T? = null,
    val description: String? = null,
)

@Serializable
private data class TelegramMessageResult(
    @SerialName("message_id")
    val messageId: Long,
)

@Serializable
private data class TelegramChatMemberResult(
    val status: String? = null,
)

@Serializable
private data class SetWebhookPayload(
    val url: String,
    @SerialName("secret_token")
    val secretToken: String? = null,
)

@Serializable
private data class SetChatMenuButtonPayload(
    @SerialName("menu_button")
    val menuButton: MenuButton? = null,
)

@Serializable
private data class SetMyCommandsPayload(
    val commands: List<BotCommand>,
)

@Serializable
private data class AnswerCallbackQueryPayload(
    @SerialName("callback_query_id")
    val callbackQueryId: String,
    val text: String? = null,
    @SerialName("show_alert")
    val showAlert: Boolean = false,
)

class TelegramServiceImpl(
    private val client: HttpClient,
    private val config: ConfigWithSecrets,
) : TelegramService {

    override suspend fun getMe(): TelegramUser? {
        val response = client.get(telegramEndpoint(METHOD_GET_ME))
        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to validate bot token: ${response.status}")
        }
        val apiResponse = telegramJson.decodeFromString<TelegramApiResponse<TelegramUser>>(response.bodyAsText())
        return apiResponse.result
    }

    override suspend fun setWebhook(
        url: String,
        headerToken: String?,
    ): Boolean {
        val payload = SetWebhookPayload(url = url, secretToken = headerToken?.takeIf(String::isNotBlank))
        val response = client.post(telegramEndpoint(METHOD_SET_WEBHOOK)) {
            contentType(ContentType.Application.Json)
            setBody(telegramJson.encodeToString(payload))
        }
        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to set webhook URL: ${response.status}")
        }
        return true
    }

    override suspend fun setChatMenuButton(menuButton: MenuButton?): Boolean {
        val payload = SetChatMenuButtonPayload(menuButton = menuButton)
        val response = client.post(telegramEndpoint(METHOD_SET_CHAT_MENU_BUTTON)) {
            contentType(ContentType.Application.Json)
            setBody(telegramJson.encodeToString(payload))
        }
        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to set chat menu button: ${response.status}")
        }
        return true
    }

    override suspend fun setMyCommands(commands: List<BotCommand>): Boolean {
        val payload = SetMyCommandsPayload(commands = commands)
        val response = client.post(telegramEndpoint(METHOD_SET_MY_COMMANDS)) {
            contentType(ContentType.Application.Json)
            setBody(telegramJson.encodeToString(payload))
        }
        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to set bot commands: ${response.status}")
        }
        return true
    }

    override suspend fun chatMemberStatus(
        chatId: Long,
        userId: Long,
    ): String? {
        val response = client.get(telegramEndpoint(METHOD_GET_CHAT_MEMBER)) {
            parameter("chat_id", chatId)
            parameter("user_id", userId)
        }
        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to get chat member: ${response.status}")
        }

        val apiResponse =
            telegramJson.decodeFromString<TelegramApiResponse<TelegramChatMemberResult>>(response.bodyAsText())
        return apiResponse.result?.status
    }

    override suspend fun sendMessage(message: Message): String {
        val endpoint = if (message.messageId.isNullOrBlank()) METHOD_SEND_MESSAGE else METHOD_EDIT_MESSAGE_TEXT
        val response = client.post(telegramEndpoint(endpoint)) {
            contentType(ContentType.Application.Json)
            setBody(telegramJson.encodeToString(message))
        }

        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to send message: ${response.status}")
        }

        val responseBody = response.bodyAsText()
        val apiResponse = runCatching {
            telegramJson.decodeFromString<TelegramApiResponse<TelegramMessageResult>>(responseBody)
        }.getOrElse {
            throw HttpException("Telegram API response error: $responseBody", it)
        }

        val result = apiResponse.result
            ?: throw HttpException("Telegram API response missing 'result' field: $responseBody")

        return result.messageId.toString()
    }

    override suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String?,
        showAlert: Boolean,
    ): Boolean {
        val payload = AnswerCallbackQueryPayload(
            callbackQueryId = callbackQueryId,
            text = text,
            showAlert = showAlert,
        )
        val response = client.post(telegramEndpoint(METHOD_ANSWER_CALLBACK_QUERY)) {
            contentType(ContentType.Application.Json)
            setBody(telegramJson.encodeToString(payload))
        }

        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to answer callback query: ${response.status}")
        }

        return true
    }

    private fun telegramEndpoint(method: String): String {
        val sanitizedToken = config.botApi.trim().removePrefix("bot")
        return "$TELEGRAM_API_BASE_URL$sanitizedToken/$method"
    }
}
