package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.server.application.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import org.gitlab4j.api.webhook.BuildEvent
import org.koin.ktor.ext.inject

class WebhookRequestHandler(
    private val application: Application,
) {
    suspend fun processQueue(queue: Channel<EventData>) {
        val webhookService by application.inject<WebHookService>()
        val logger by application.inject<KLogger>()
        val telegramService by application.inject<TelegramService>()
        val formatter: WebhookMessageFormatter by application.inject()

        logger.debug { MESSAGE_PROCESSING }
        for (data in queue) {
            try {
                val event = data.event
                val chatDetails = data.chatDetails()
                val messageId =
                    when (event) {
                        is BuildEvent -> webhookService.getMessageIdOfEvent(event.buildId)
                        else -> null
                    }
                coroutineScope {
                    val sendMessageJob =
                        async {
                            telegramService.sendMessage(
                                Message(
                                    chatId = chatDetails.chatId,
                                    threadId = chatDetails.topicId,
                                    messageId = messageId,
                                    text = formatter.formatEventMessage(event),
                                    parseMode = PARSE_MODE,
                                    disableWebPagePreview = true,
                                ),
                            )
                        }
                    val messageJob = sendMessageJob.await() // Ensure sendMessage completes before responding
                    logger.debug { "sent message $messageJob" }
                    if (event is BuildEvent) {
                        when {
                            event.buildStatus in listOf("success", "canceled", "failed") -> {
                                webhookService.clearMessageIdOfEvent(event.buildId)
                                logger.debug { "build #${event.buildId} finished, removed saved message id" }
                            }
                            else -> {
                                webhookService.setMessageIdOfEvent(event.buildId, messageJob)
                                logger.debug { "saved build #${event.buildId}'s message id $messageJob" }
                            }
                        }
                    }
                }
            } catch (skipEx: SkipEventException) {
                logger.debug { MESSAGE_SKIPPED }
            } catch (e: Exception) {
                logger.error { "$MESSAGE_ERROR \n${e.message}" }
            }
        }
        logger.debug { MESSAGE_STOPPED }
    }

    companion object {
        const val PARSE_MODE = "HTML"
        const val MESSAGE_PROCESSING = "Queue started processing."
        const val MESSAGE_STOPPED = "Queue stopped processing."
        const val MESSAGE_ERROR = "Error processing webhook data."
        const val MESSAGE_SKIPPED = "This event is skipped."
    }
}
