package net.raquezha.nuecagram.telegram

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.*
import net.raquezha.nuecagram.ConfigWithSecrets
import org.apache.http.HttpException
import org.gitlab4j.api.utils.JacksonJson

class TelegramServiceImpl(
    private val client: HttpClient,
    private val config: ConfigWithSecrets,
) : TelegramService {
    override suspend fun chatMemberStatus(
        chatId: Long,
        userId: Long,
    ): String? {
        val response =
            client.get(
                "https://api.telegram.org/bot${config.botApi}/getChatMember?chat_id=$chatId&user_id=$userId",
            )
        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to get chat member: ${response.status}")
        }

        val responseBody = response.bodyAsText()
        val responseJson = JacksonJson.toJsonNode(responseBody)
        val result = responseJson.get("result") ?: return null
        return result.get("status")?.asText()
    }

    override suspend fun sendMessage(message: Message): String {
        val jsonMessage = JacksonJson.toJsonString(message)
        val response =
            when {
                message.messageId.isNullOrBlank() -> {
                    client.post(getURLSendMessage(config.botApi)) {
                        contentType(ContentType.Application.Json)
                        setBody(jsonMessage)
                    }
                }
                else -> {
                    client.post(getURLEditMessage(config.botApi)) {
                        contentType(ContentType.Application.Json)
                        setBody(jsonMessage)
                    }
                }
            }

        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to send message: ${response.status}")
        }

        val responseBody = response.bodyAsText()
        val responseJson = JacksonJson.toJsonNode(responseBody)

        val result =
            responseJson.get("result")
                ?: throw HttpException("Telegram API response missing 'result' field: $responseBody")

        val messageIdNode =
            result.get("message_id")
                ?: throw HttpException("Telegram API response missing 'message_id' field: $responseBody")

        return messageIdNode.asInt().toString()
    }
}
