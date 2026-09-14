package net.raquezha.nuecagram.telegram

import com.google.common.truth.Truth.assertThat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import net.raquezha.nuecagram.ConfigWithSecrets
import org.apache.http.HttpException
import org.junit.Test

class TelegramServiceImplTest {
    private fun checkResponse(
        status: HttpStatusCode,
        body: String,
        expectedId: String? = null,
        replacementStatus: HttpStatusCode = HttpStatusCode.OK,
        expectedCalls: Int = 1,
        editing: Boolean = true,
    ) = testApplication {
        val requests = mutableListOf<String>()
        externalServices {
            hosts("https://api.telegram.org") {
                routing {
                    post("/bottest/editMessageText") {
                        requests += call.receiveText()
                        call.respondText(body, ContentType.Application.Json, status)
                    }
                    post("/bottest/sendMessage") {
                        requests += call.receiveText()
                        call.respondText(
                            if (editing) """{"ok":true,"result":{"message_id":99}}""" else body,
                            ContentType.Application.Json,
                            if (editing) replacementStatus else status,
                        )
                    }
                }
            }
        }
        val config = mockk<ConfigWithSecrets>()
        every { config.botApi } returns "test"
        val productionClient = createClient { expectSuccess = true }
        val service = TelegramServiceImpl(productionClient, config)
        val result = runCatching {
            service.sendMessage(
                Message(
                    chatId = "123",
                    threadId = 456L,
                    text = "hello",
                    messageId = "42".takeIf { editing },
                    disableNotification = true,
                ),
            )
        }
        when (expectedId) {
            null -> assertThat(result.exceptionOrNull()).isInstanceOf(HttpException::class.java)
            else -> assertThat(result.getOrThrow()).isEqualTo(expectedId)
        }
        assertThat(requests).hasSize(expectedCalls)
        if (expectedCalls == 2) {
            assertThat(requests.last()).doesNotContain("message_id")
            assertThat(requests.last()).contains("\"message_thread_id\":456")
            assertThat(requests.last()).contains("\"disable_notification\":true")
        }
    }

    @Test
    fun unchangedEditIsSuccessful() = checkResponse(
        HttpStatusCode.BadRequest,
        """{"ok":false,"description":"Bad Request: message is not modified: same content"}""",
        expectedId = "42",
    )

    @Test
    fun deletedCardIsReplacedOnceInSameTopicSilently() = checkResponse(
        HttpStatusCode.BadRequest,
        """{"ok":false,"description":"Bad Request: message to edit not found"}""",
        expectedId = "99",
        expectedCalls = 2,
    )

    @Test
    fun failedReplacementIsNotRetriedRecursively() = checkResponse(
        HttpStatusCode.BadRequest,
        """{"ok":false,"description":"Bad Request: message to edit not found"}""",
        replacementStatus = HttpStatusCode.Forbidden,
        expectedCalls = 2,
    )

    @Test
    fun newMessageCannotBeMistakenForUnchangedEdit() = checkResponse(
        HttpStatusCode.BadRequest,
        """{"ok":false,"description":"Bad Request: message is not modified"}""",
        editing = false,
    )

    @Test
    fun serverFailureDoesNotTriggerReplacement() = checkResponse(
        HttpStatusCode.InternalServerError,
        """{"ok":false,"description":"Bad Request: message to edit not found"}""",
    )

    @Test
    fun rateLimitDoesNotTriggerReplacement() = checkResponse(
        HttpStatusCode.TooManyRequests,
        """{"ok":false,"description":"Too Many Requests: retry after 3"}""",
    )

    @Test
    fun invalidMessageIdIsNotTreatedAsDeletion() = checkResponse(
        HttpStatusCode.BadRequest,
        """{"ok":false,"description":"Bad Request: message_id_invalid"}""",
    )

    @Test
    fun malformedBodyIsNotTreatedAsSuccess() = checkResponse(
        HttpStatusCode.BadRequest,
        "message is not modified",
    )

    @Test
    fun unsuccessfulEnvelopeIsNotAcceptedWithHttp200() = checkResponse(
        HttpStatusCode.OK,
        """{"ok":false,"result":{"message_id":42}}""",
    )

    @Test
    fun forbiddenEditDoesNotPostAnotherCard() = checkResponse(
        HttpStatusCode.Forbidden,
        """{"ok":false,"description":"Forbidden: bot was kicked from the chat"}""",
    )

    @Test
    fun missingResultDoesNotReturnAnInventedMessageId() = checkResponse(
        HttpStatusCode.OK,
        """{"ok":true}""",
    )

    @Test
    fun successfulEditReturnsId() = checkResponse(
        HttpStatusCode.OK,
        """{"ok":true,"result":{"message_id":42}}""",
        expectedId = "42",
    )
}
