package net.raquezha.nuecagram.telegram

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.*
import net.raquezha.nuecagram.telegram.TokenProviderImpl.TelegramBotToken
import org.apache.http.HttpException
import org.gitlab4j.api.utils.JacksonJson

class TelegramServiceImpl(
    private val client: HttpClient,
    private val botToken: TelegramBotToken,
) : TelegramService {
    override suspend fun sendMessage(message: Message): String {
        val jsonMessage = JacksonJson.toJsonString(message)
        val response =
            when {
                message.messageId.isNullOrBlank() -> {
                    client.post(getURLSendMessage(botToken.value)) {
                        contentType(ContentType.Application.Json)
                        setBody(jsonMessage)
                    }
                }
                else -> {
                    client.post(getURLEditMessage(botToken.value)) {
                        contentType(ContentType.Application.Json)
                        setBody(jsonMessage)
                    }
                }
            }

        if (response.status != HttpStatusCode.OK) {
            throw HttpException("Failed to send message")
        }

        val responseJson = JacksonJson.toJsonNode(response.bodyAsText())
        return responseJson
            .get("result")
            .get("message_id")
            .asInt()
            .toString()
    }
}
