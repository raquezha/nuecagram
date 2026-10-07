package net.raquezha.nuecagram.webhook

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gitlab4j.api.webhook.NoteEvent
import org.gitlab4j.api.webhook.NoteEvent.NoteableType.COMMIT
import org.gitlab4j.api.webhook.NoteEvent.NoteableType.ISSUE
import org.gitlab4j.api.webhook.NoteEvent.NoteableType.MERGE_REQUEST
import org.gitlab4j.api.webhook.NoteEvent.NoteableType.SNIPPET

class NoteMessageFormatter {
    private val logger = KotlinLogging.logger {}

    fun formatNoteEvent(event: NoteEvent): String {
        val userName = event.user?.name ?: "Unknown"
        val projectName = event.project?.name ?: "Unknown"
        val noteUrl = event.objectAttributes?.url ?: ""
        val noteContent = event.objectAttributes?.note ?: ""

        val (contextInfo, contextTitle) = extractNoteContext(event) ?: throw SkipEventException()
        val clickableNote = noteUrl.link("comment")

        return buildString {
            append("💬 New $clickableNote on $contextInfo\n")
            append("${projectName.bold()}\n")
            append("\n")

            append("📌 ${contextTitle.take(FormatterConstants.MAX_CONTEXT_TITLE_LENGTH).italic()}")
            if (contextTitle.length > FormatterConstants.MAX_CONTEXT_TITLE_LENGTH) append("...")
            append("\n\n")

            val truncatedNote = noteContent.take(FormatterConstants.MAX_NOTE_LENGTH).trim()
            append("\"${truncatedNote.escapeHtml()}\"")
            if (noteContent.length > FormatterConstants.MAX_NOTE_LENGTH) append("...")
            append("\n\n")

            append("By ${userName.bold()}")
        }
    }

    private fun extractNoteContext(event: NoteEvent): Pair<String, String>? {
        val noteableType = event.objectAttributes?.noteableType

        return when (noteableType) {
            ISSUE -> extractIssueNoteContext(event)
            MERGE_REQUEST -> extractMergeRequestNoteContext(event)
            COMMIT -> extractCommitNoteContext(event)
            SNIPPET, null -> {
                logger.debug { "NoteEvent with noteableType=$noteableType, skipping" }
                null
            }
        }
    }

    private fun extractIssueNoteContext(event: NoteEvent): Pair<String, String>? {
        val issue =
            event.issue ?: run {
                logger.warn { "NoteEvent for ISSUE but issue object is null, skipping" }
                return null
            }
        return "Issue #${issue.iid}" to (issue.title ?: "Untitled")
    }

    private fun extractMergeRequestNoteContext(event: NoteEvent): Pair<String, String>? {
        val mr =
            event.mergeRequest ?: run {
                logger.warn { "NoteEvent for MERGE_REQUEST but mergeRequest object is null, skipping" }
                return null
            }
        return "MR !${mr.iid}" to (mr.title ?: "Untitled")
    }

    private fun extractCommitNoteContext(event: NoteEvent): Pair<String, String>? {
        val commit =
            event.commit ?: run {
                logger.warn { "NoteEvent for COMMIT but commit object is null, skipping" }
                return null
            }
        val shortSha = commit.id?.take(FormatterConstants.SHORT_SHA_LENGTH) ?: "unknown"
        val title = commit.title ?: commit.message?.lines()?.firstOrNull() ?: "No message"
        return "Commit $shortSha" to title
    }
}
