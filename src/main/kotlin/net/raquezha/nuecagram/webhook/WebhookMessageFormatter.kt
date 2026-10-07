package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KLogger
import org.gitlab4j.api.GitLabApiException
import org.gitlab4j.api.webhook.BuildEvent
import org.gitlab4j.api.webhook.DeploymentEvent
import org.gitlab4j.api.webhook.Event
import org.gitlab4j.api.webhook.IssueEvent
import org.gitlab4j.api.webhook.MergeRequestEvent
import org.gitlab4j.api.webhook.NoteEvent
import org.gitlab4j.api.webhook.PipelineEvent
import org.gitlab4j.api.webhook.PushEvent
import org.gitlab4j.api.webhook.ReleaseEvent
import org.gitlab4j.api.webhook.TagPushEvent
import org.gitlab4j.api.webhook.WikiPageEvent
import org.koin.java.KoinJavaComponent.inject

class WebhookMessageFormatter(
    private val pipelineFormatter: PipelineMessageFormatter = PipelineMessageFormatter(),
    private val pushFormatter: PushMessageFormatter = PushMessageFormatter(),
    private val mrFormatter: MergeRequestMessageFormatter = MergeRequestMessageFormatter(),
    private val noteFormatter: NoteMessageFormatter = NoteMessageFormatter(),
    private val auxiliaryFormatter: AuxiliaryMessageFormatter = AuxiliaryMessageFormatter(),
) {
    private val logger by inject<KLogger>(KLogger::class.java)

    fun formatEventMessage(event: Event, mrIid: Long? = null): String =
        when (event) {
            is PipelineEvent -> pipelineFormatter.formatPipelineEvent(event, mrIid)
            is PushEvent -> pushFormatter.formatPushEventMessage(event, mrIid)
            is TagPushEvent -> auxiliaryFormatter.formatTagPushEvent(event)
            is WikiPageEvent -> auxiliaryFormatter.formatWikiPageEvent(event)
            is DeploymentEvent -> auxiliaryFormatter.formatDeployEventMessage(event)
            is ReleaseEvent -> auxiliaryFormatter.formatReleaseEventMessage(event)
            is IssueEvent -> auxiliaryFormatter.formatIssueEventMessage(event)
            is BuildEvent -> auxiliaryFormatter.formatBuildEventMessage(event)
            is MergeRequestEvent -> mrFormatter.formatMergeRequestEventMessage(event, squashedCommitCount = null)
            is NoteEvent -> noteFormatter.formatNoteEvent(event)
            else -> throwUnsupportedEventException(event)
        }

    fun formatUnifiedPushPipelineMessage(
        pushHeader: String,
        event: PipelineEvent,
        mrIid: Long? = null,
    ): String = pipelineFormatter.formatUnifiedPushPipelineMessage(pushHeader, event, mrIid)

    fun formatJobOnlyPipelineMessage(
        trackedPipeline: TrackedPipeline,
        pipelineId: Long,
    ): String = pipelineFormatter.formatJobOnlyPipelineMessage(trackedPipeline, pipelineId)

    fun formatPushEventMessage(
        event: PushEvent,
        mrIid: Long? = null,
    ): String = pushFormatter.formatPushEventMessage(event, mrIid)

    fun formatMergeRequestEventMessage(
        event: MergeRequestEvent,
        squashedCommitCount: Int? = null,
    ): String = mrFormatter.formatMergeRequestEventMessage(event, squashedCommitCount)

    private fun throwUnsupportedEventException(event: Event): Nothing {
        val message = "Unsupported event object_kind, object_kind=${event.objectKind}"
        logger.error { message }
        throw GitLabApiException(message)
    }
}
