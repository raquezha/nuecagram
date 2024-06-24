package net.raquezha.nuecagram.telegram

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import net.raquezha.nuecagram.telegram.TokenProviderImpl.TelegramBotToken
import org.gitlab4j.api.utils.JacksonJson

class TelegramServiceImpl(
    private val client: HttpClient,
    private val botToken: TelegramBotToken,
) : TelegramService {
    override suspend fun sendMessage(message: Message) {
        val jsonMessage = JacksonJson.toJsonString(message)
        client.post(getURLSendMessage(botToken.value)) {
            contentType(ContentType.Application.Json)
            setBody(jsonMessage)
        }
    }
}
