package net.raquezha.nuecagram.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.*
import net.raquezha.nuecagram.ConfigWithSecrets
import org.apache.http.HttpException

private const val TELEGRAM_API_BASE_URL = "https://api.telegram.org/bot"
private const val METHOD_GET_CHAT_MEMBER = "getChatMember"
private const val METHOD_SEND_MESSAGE = "sendMessage"
private const val METHOD_EDIT_MESSAGE_TEXT = "editMessageText"
private const val METHOD_ANSWER_CALLBACK_QUERY = "answerCallbackQuery"

class TelegramServiceImpl(
    private val client: HttpClient,
    private val config: ConfigWithSecrets,
) : TelegramService {
    private val mapper = ObjectMapper()

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

        val responseBody = response.bodyAsText()
        val responseJson = mapper.readTree(responseBody)
        val result = responseJson.get("result") ?: return null
        return result.get("status")?.asText()
    }

    override suspend fun sendMessage(message: Message): String {
        val jsonMessage = mapper.writeValueAsString(message)
        val endpoint = if (message.messageId.isNullOrBlank()) METHOD_SEND_MESSAGE else METHOD_EDIT_MESSAGE_TEXT
        val response = client.post(telegramEndpoint(endpoint)) {
            contentType(ContentType.Application.Json)
            setBody(jsonMessage)
        }

        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to send message: ${response.status}")
        }

        val responseBody = response.bodyAsText()
        val responseJson = mapper.readTree(responseBody)

        val result =
            responseJson.get("result")
                ?: throw HttpException("Telegram API response missing 'result' field: $responseBody")

        val messageIdNode =
            result.get("message_id")
                ?: throw HttpException("Telegram API response missing 'message_id' field: $responseBody")

        return messageIdNode.asInt().toString()
    }

    override suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String?,
        showAlert: Boolean,
    ): Boolean {
        val payload = mutableMapOf<String, Any>(
            "callback_query_id" to callbackQueryId,
            "show_alert" to showAlert,
        )
        if (text != null) {
            payload["text"] = text
        }
        val jsonPayload = mapper.writeValueAsString(payload)
        val response = client.post(telegramEndpoint(METHOD_ANSWER_CALLBACK_QUERY)) {
            contentType(ContentType.Application.Json)
            setBody(jsonPayload)
        }

        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to answer callback query: ${response.status}")
        }

        return true
    }

    private fun telegramEndpoint(method: String): String =
        "$TELEGRAM_API_BASE_URL${config.botApi}/$method"
}
