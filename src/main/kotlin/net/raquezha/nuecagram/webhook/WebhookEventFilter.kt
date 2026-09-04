package net.raquezha.nuecagram.webhook

import org.gitlab4j.api.webhook.ChangeContainer
import org.gitlab4j.api.webhook.Event
import org.gitlab4j.api.webhook.MergeRequestChanges
import org.gitlab4j.api.webhook.MergeRequestEvent

enum class FilterDecision {
    PROCESS,
    SKIP_REDUNDANT_PUSH_MR_UPDATE,
}

class WebhookEventFilter {

    fun evaluate(
        event: Event,
        latestPushSha: String?,
    ): FilterDecision {
        if (event !is MergeRequestEvent) return FilterDecision.PROCESS

        val action = event.objectAttributes?.action?.lowercase() ?: return FilterDecision.PROCESS
        if (action != "update") return FilterDecision.PROCESS

        if (hasStructuralChanges(event.changes)) {
            return FilterDecision.PROCESS
        }

        val lastCommitSha = event.objectAttributes?.lastCommit?.id
        return if (!lastCommitSha.isNullOrBlank() && lastCommitSha == latestPushSha) {
            FilterDecision.SKIP_REDUNDANT_PUSH_MR_UPDATE
        } else {
            FilterDecision.PROCESS
        }
    }

    fun hasStructuralChanges(changes: MergeRequestChanges?): Boolean {
        if (changes == null) return false
        return hasTypedFieldChanges(changes) || hasExtraMapChanges(changes.any())
    }

    private fun hasTypedFieldChanges(changes: MergeRequestChanges): Boolean {
        if (changes.title?.hasChanged() == true) return true
        if (changes.description?.hasChanged() == true) return true
        if (changes.labels?.hasChanged() == true) return true
        if (changes.assignees?.hasChanged() == true) return true
        if (changes.reviewers?.hasChanged() == true) return true
        if (changes.state?.hasChanged() == true) return true
        return changes.milestoneId?.hasChanged() == true
    }

    private fun <T> ChangeContainer<T>.hasChanged(): Boolean = previous != current

    private fun hasExtraMapChanges(extraChanges: Map<String, Any>?): Boolean {
        if (extraChanges.isNullOrEmpty()) return false
        val structuralKeys = setOf(
            "target_branch",
            "draft",
            "work_in_progress",
            "title",
            "description",
            "labels",
            "assignees",
            "reviewers",
            "milestone_id",
        )
        return extraChanges.keys.any { it in structuralKeys }
    }
}
