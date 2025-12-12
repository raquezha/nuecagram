package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.server.application.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import net.raquezha.nuecagram.telegram.Message
import net.raquezha.nuecagram.telegram.TelegramService
import org.gitlab4j.api.webhook.BuildEvent
import org.gitlab4j.api.webhook.PipelineEvent
import org.koin.ktor.ext.inject

class WebhookRequestHandler(
    private val application: Application,
) {
    private val queue = Channel<EventData>()

    suspend fun enqueue(eventData: EventData) {
        queue.send(eventData)
    }

    suspend fun processQueue() {
        val webhookService by application.inject<WebHookService>()
        val logger by application.inject<KLogger>()
        val telegramService by application.inject<TelegramService>()
        val formatter: WebhookMessageFormatter by application.inject()

        logger.debug { MESSAGE_PROCESSING }
        for (data in queue) {
            try {
                val event = data.event
                val chatDetails = data.chatDetails()

                when (event) {
                    is PipelineEvent -> {
                        handlePipelineEvent(
                            event = event,
                            chatDetails = chatDetails,
                            webhookService = webhookService,
                            telegramService = telegramService,
                            formatter = formatter,
                            logger = logger,
                        )
                    }
                    is BuildEvent -> {
                        handleBuildEvent(
                            event = event,
                            chatDetails = chatDetails,
                            webhookService = webhookService,
                            telegramService = telegramService,
                            formatter = formatter,
                            logger = logger,
                        )
                    }
                    else -> {
                        handleGenericEvent(
                            event = data.event,
                            chatDetails = chatDetails,
                            telegramService = telegramService,
                            formatter = formatter,
                            logger = logger,
                        )
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

    private suspend fun handlePipelineEvent(
        event: PipelineEvent,
        chatDetails: ChatDetails,
        webhookService: WebHookService,
        telegramService: TelegramService,
        formatter: WebhookMessageFormatter,
        logger: KLogger,
    ) {
        val pipelineId = event.objectAttributes.id
        val status = event.objectAttributes.status
        val existingMessageId = webhookService.getPipelineMessageId(pipelineId)

        coroutineScope {
            val sendMessageJob =
                async {
                    telegramService.sendMessage(
                        Message(
                            chatId = chatDetails.chatId,
                            threadId = chatDetails.topicId,
                            messageId = existingMessageId,
                            text = formatter.formatEventMessage(event),
                            parseMode = PARSE_MODE,
                            disableWebPagePreview = true,
                        ),
                    )
                }
            val messageId = sendMessageJob.await()
            logger.debug { "Pipeline #$pipelineId: sent/updated message $messageId" }

            when (status) {
                in PIPELINE_TERMINAL_STATUSES -> {
                    // Send reply tagging the author
                    val username = event.user?.username
                    if (username != null) {
                        val replyText = formatPipelineCompletionReply(status, username)
                        telegramService.sendMessage(
                            Message(
                                chatId = chatDetails.chatId,
                                threadId = chatDetails.topicId,
                                text = replyText,
                                parseMode = PARSE_MODE,
                                replyToMessageId = messageId,
                            ),
                        )
                        logger.debug { "Pipeline #$pipelineId: sent completion reply tagging @$username" }
                    }

                    webhookService.clearPipelineMessageId(pipelineId)
                    logger.debug { "Pipeline #$pipelineId finished ($status), cleared message tracking" }
                }
                else -> {
                    webhookService.setPipelineMessageId(pipelineId, messageId)
                    logger.debug { "Pipeline #$pipelineId ($status): tracking message $messageId" }
                }
            }
        }
    }

    private suspend fun handleBuildEvent(
        event: BuildEvent,
        chatDetails: ChatDetails,
        webhookService: WebHookService,
        telegramService: TelegramService,
        formatter: WebhookMessageFormatter,
        logger: KLogger,
    ) {
        // Skip individual job events - we handle jobs via PipelineEvent
        logger.debug { "Skipping BuildEvent #${event.buildId} - handled via PipelineEvent" }
        throw SkipEventException()
    }

    private suspend fun handleGenericEvent(
        event: org.gitlab4j.api.webhook.Event,
        chatDetails: ChatDetails,
        telegramService: TelegramService,
        formatter: WebhookMessageFormatter,
        logger: KLogger,
    ) {
        coroutineScope {
            val sendMessageJob =
                async {
                    telegramService.sendMessage(
                        Message(
                            chatId = chatDetails.chatId,
                            threadId = chatDetails.topicId,
                            messageId = null,
                            text = formatter.formatEventMessage(event),
                            parseMode = PARSE_MODE,
                            disableWebPagePreview = true,
                        ),
                    )
                }
            val messageId = sendMessageJob.await()
            logger.debug { "Sent message $messageId for ${event.objectKind}" }
        }
    }

    private fun formatPipelineCompletionReply(
        status: String,
        username: String,
    ): String {
        val message =
            when (status) {
                "success" -> getRandomSuccessMessage()
                "failed" -> getRandomFailedMessage()
                "canceled" -> getRandomCanceledMessage()
                "skipped" -> getRandomSkippedMessage()
                else -> "Pipeline finished!"
            }
        return "@$username $message"
    }

    private fun getRandomSuccessMessage(): String =
        listOf(
            "✅ Stop sipping that coffee, pipeline passed!",
            "✅ The pipeline passed! Time to mass sa chismis.",
            "✅ Pipeline passed! You're officially a 10x developer today.",
            "✅ Pipeline passed! Even your code is surprised.",
            "✅ Success! The CI gods have smiled upon you.",
            "✅ Pipeline passed! Quick, deploy before someone breaks it!",
            "✅ It worked?! I mean... of course it worked! ✅",
            "✅ Pipeline passed! You may now mass peacefully sa may 7/11.",
            "✅ All green! Your code is chef's kiss today. 👨‍🍳💋",
            "✅ Pipeline passed! This calls for mass sa beer!",
        ).random()

    private fun getRandomFailedMessage(): String =
        listOf(
            "❌ The pipeline has passed... away. RIP. 💀",
            "❌ Pipeline failed! Time to mass sa stackoverflow.",
            "❌ Pipeline failed! But hey, at least you're consistent.",
            "❌ Build machine said: 'Nah, I don't think so.' ❌",
            "❌ Pipeline failed! Have you tried turning it off and on again?",
            "❌ Failed! The code gods demand a sacrifice (your lunch break).",
            "❌ Pipeline failed! git blame time! 🔍",
            "❌ Oops! Your code took the day off. Pipeline failed!",
            "❌ Pipeline failed! May the force rebuild with you.",
            "❌ Failed! Time to mass sa debug mode. 🐛",
        ).random()

    private fun getRandomCanceledMessage(): String =
        listOf(
            "⛔ Pipeline canceled! Someone got cold feet.",
            "⛔ Pipeline canceled! Commitment issues detected.",
            "⛔ Canceled! The pipeline ghosted you. 👻",
            "⛔ Pipeline canceled! It's not you, it's the code.",
            "⛔ Abort mission! Pipeline canceled!",
        ).random()

    private fun getRandomSkippedMessage(): String =
        listOf(
            "⏭️ Pipeline skipped! It said 'not today.'",
            "⏭️ Skipped! The pipeline is feeling lazy.",
            "⏭️ Pipeline skipped! Maybe tomorrow?",
            "⏭️ Skipped! Your pipeline is on vacation mode. 🏖️",
            "⏭️ Pipeline said 'skip' like it's a YouTube ad.",
        ).random()

    companion object {
        const val PARSE_MODE = "HTML"
        const val MESSAGE_PROCESSING = "Queue started processing."
        const val MESSAGE_STOPPED = "Queue stopped processing."
        const val MESSAGE_ERROR = "Error processing webhook data."
        const val MESSAGE_SKIPPED = "This event is skipped."

        private val PIPELINE_TERMINAL_STATUSES = listOf("success", "failed", "canceled", "skipped")
    }
}
