package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import net.raquezha.nuecagram.webhook.NuecagramHeaders.CHAT_ID
import net.raquezha.nuecagram.webhook.NuecagramHeaders.GITLAB_EVENT
import net.raquezha.nuecagram.webhook.NuecagramHeaders.SECRET_TOKEN
import net.raquezha.nuecagram.webhook.NuecagramHeaders.TOPIC_ID
import org.gitlab4j.api.GitLabApiException
import org.gitlab4j.api.utils.JacksonJson
import org.gitlab4j.api.webhook.BuildEvent
import org.gitlab4j.api.webhook.DeploymentEvent
import org.gitlab4j.api.webhook.Event
import org.gitlab4j.api.webhook.IssueEvent
import org.gitlab4j.api.webhook.JobEvent
import org.gitlab4j.api.webhook.MergeRequestEvent
import org.gitlab4j.api.webhook.NoteEvent
import org.gitlab4j.api.webhook.PipelineEvent
import org.gitlab4j.api.webhook.PushEvent
import org.gitlab4j.api.webhook.ReleaseEvent
import org.gitlab4j.api.webhook.TagPushEvent
import org.gitlab4j.api.webhook.WikiPageEvent

class WebHookServiceImpl(
    private var secretToken: String? = null,
    private var logger: KLogger,
) : WebHookService {
    private val jacksonJson: JacksonJson = JacksonJson()

    private val supportedEvents =
        setOf(
            IssueEvent.X_GITLAB_EVENT,
            JobEvent.JOB_HOOK_X_GITLAB_EVENT,
            BuildEvent.JOB_HOOK_X_GITLAB_EVENT,
            MergeRequestEvent.X_GITLAB_EVENT,
            NoteEvent.X_GITLAB_EVENT,
            PipelineEvent.X_GITLAB_EVENT,
            PushEvent.X_GITLAB_EVENT,
            TagPushEvent.X_GITLAB_EVENT,
            WikiPageEvent.X_GITLAB_EVENT,
            DeploymentEvent.X_GITLAB_EVENT,
            ReleaseEvent.X_GITLAB_EVENT,
        )

    override suspend fun handleRequest(call: ApplicationCall): EventData {
        return try {
            val webhookData = call.getWebhookData()
            handleSecretToken(webhookData.headerSecretToken)
            handleEvents(webhookData.headerEvent)
            webhookData
        } catch (exception: Exception) {
            val errorMessage =
                String.format(
                    "Error processing webhook!\n[%s] %s",
                    exception.javaClass.simpleName,
                    exception.message,
                )
            logger.error { errorMessage }
            throw GitLabApiException(errorMessage)
        }
    }

    private fun handleSecretToken(secretToken: String?) {
        if (!isValidSecretToken(secretToken)) {
            val message = "$SECRET_TOKEN mismatch!"
            logger.error { message }
            throw GitLabApiException(message)
        }
    }

    override fun getSecretToken(): String? {
        return this.secretToken
    }

    override fun setSecretToken(secretToken: String?) {
        this.secretToken = secretToken
    }

    private suspend fun ApplicationCall.getWebhookData(): EventData {
        val event = jacksonJson.unmarshal(Event::class.java, receiveText())
        event.requestUrl = request.uri
        event.requestQueryString = request.queryString()
        event.requestSecretToken = secretToken

        return EventData(
            event = event,
            headerEvent = request.headers[GITLAB_EVENT]?.trim() ?: throw GitLabApiException("missing '$GITLAB_EVENT' header!"),
            headerSecretToken =
            request.headers[SECRET_TOKEN]?.trim(
                ' ',
                '[',
                ']',
            ) ?: throw GitLabApiException("missing '$SECRET_TOKEN' header!"),
            headerChatId = request.headers[CHAT_ID]?.trim() ?: throw GitLabApiException("missing '$CHAT_ID' header!"),
            headerTopicId = request.headers[TOPIC_ID]?.trim(),
        ).apply {
        }
    }

    private fun handleEvents(eventName: String?) {
        if (eventName !in supportedEvents) {
            val message = "$eventName event is not yet supported."
            logger.error { message }
            throw GitLabApiException(message)
        }
    }
}
