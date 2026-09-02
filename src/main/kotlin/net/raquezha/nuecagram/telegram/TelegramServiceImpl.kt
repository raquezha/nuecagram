package net.raquezha.nuecagram.telegram

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
private const val METHOD_DELETE_MY_COMMANDS = "deleteMyCommands"
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
    @SerialName("allowed_updates")
    val allowedUpdates: List<String> = listOf("message", "callback_query", "my_chat_member"),
)

@Serializable
private data class SetChatMenuButtonPayload(
    @SerialName("menu_button")
    val menuButton: MenuButton? = null,
)

@Serializable
private data class SetMyCommandsPayload(
    val commands: List<BotCommand>,
    val scope: BotCommandScope? = null,
)

@Serializable
private data class DeleteMyCommandsPayload(
    val scope: BotCommandScope,
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

    override suspend fun getMe(): TelegramUser? =
        client.get(endpoint(METHOD_GET_ME))
            .requireOk("Failed to validate bot token")
            .decodeResult<TelegramUser>()

    override suspend fun setWebhook(url: String, headerToken: String?): Boolean {
        client.postJson(endpoint(METHOD_SET_WEBHOOK), SetWebhookPayload(url, headerToken?.takeIf(String::isNotBlank)))
            .requireOk("Failed to set webhook URL")
        return true
    }

    override suspend fun setChatMenuButton(menuButton: MenuButton?): Boolean {
        client.postJson(endpoint(METHOD_SET_CHAT_MENU_BUTTON), SetChatMenuButtonPayload(menuButton))
            .requireOk("Failed to set chat menu button")
        return true
    }

    override suspend fun setMyCommands(commands: List<BotCommand>, scope: BotCommandScope?): Boolean {
        client.postJson(endpoint(METHOD_SET_MY_COMMANDS), SetMyCommandsPayload(commands, scope))
            .requireOk("Failed to set bot commands")
        return true
    }

    override suspend fun deleteMyCommands(scope: BotCommandScope): Boolean {
        client.postJson(endpoint(METHOD_DELETE_MY_COMMANDS), DeleteMyCommandsPayload(scope))
            .requireOk("Failed to delete bot commands")
        return true
    }

    override suspend fun chatMemberStatus(chatId: Long, userId: Long): String? =
        client.get(endpoint(METHOD_GET_CHAT_MEMBER)) {
            parameter("chat_id", chatId)
            parameter("user_id", userId)
        }.requireOk("Failed to get chat member")
            .decodeResult<TelegramChatMemberResult>()
            ?.status

    override suspend fun sendMessage(message: Message): String {
        val method = if (message.messageId.isNullOrBlank()) METHOD_SEND_MESSAGE else METHOD_EDIT_MESSAGE_TEXT
        val response = client.postJson(endpoint(method), message)
            .requireOk("Failed to send message")
        val bodyText = response.bodyAsText()
        val apiResponse = runCatching {
            telegramJson.decodeFromString<TelegramApiResponse<TelegramMessageResult>>(bodyText)
        }.getOrElse { throw HttpException("Telegram API response error: $bodyText", it) }
        return apiResponse.result?.messageId?.toString()
            ?: throw HttpException("Telegram API response missing 'result' field: $bodyText")
    }

    override suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String?,
        showAlert: Boolean,
    ): Boolean {
        val payload = AnswerCallbackQueryPayload(callbackQueryId, text, showAlert)
        client.postJson(endpoint(METHOD_ANSWER_CALLBACK_QUERY), payload)
            .requireOk("Failed to answer callback query")
        return true
    }

    private fun endpoint(method: String): String =
        "$TELEGRAM_API_BASE_URL${config.botApi.trim().removePrefix("bot")}/$method"

    private suspend inline fun <reified T> HttpResponse.decodeApiResponse(): TelegramApiResponse<T> =
        telegramJson.decodeFromString(bodyAsText())

    private suspend inline fun <reified T> HttpResponse.decodeResult(): T? =
        decodeApiResponse<T>().result

    private fun HttpResponse.requireOk(context: String): HttpResponse =
        apply { if (status != HttpStatusCode.OK) throw HttpException("$context: $status") }

    private suspend inline fun <reified T : Any> HttpClient.postJson(url: String, payload: T): HttpResponse =
        post(url) {
            contentType(ContentType.Application.Json)
            setBody(telegramJson.encodeToString(payload))
        }
}
