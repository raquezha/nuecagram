package net.raquezha.nuecagram.webhook

import org.gitlab4j.api.models.Reviewer
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
        return if (!lastCommitSha.isNullOrBlank() && lastCommitSha.equals(latestPushSha, ignoreCase = true)) {
            FilterDecision.SKIP_REDUNDANT_PUSH_MR_UPDATE
        } else {
            FilterDecision.PROCESS
        }
    }

    fun hasStructuralChanges(changes: MergeRequestChanges?): Boolean {
        if (changes == null) return false
        return hasTypedFieldChanges(changes) ||
            ReviewerChangeExtractor.extract(changes).isNotEmpty() ||
            hasExtraMapChanges(changes.any())
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
            "milestone_id",
        )
        return extraChanges.entries.any { (key, container) ->
            key in structuralKeys && hasContainerChanged(container)
        }
    }

    private fun hasContainerChanged(container: Any?): Boolean =
        when (container) {
            is ChangeContainer<*> -> container.previous != container.current
            is Map<*, *> -> container["previous"] != container["current"]
            null -> false
            else -> true
        }
}

data class ReviewerChange(
    val added: List<ReviewerIdentity>,
    val removed: List<ReviewerIdentity>,
) {
    fun isEmpty(): Boolean = added.isEmpty() && removed.isEmpty()
    fun isNotEmpty(): Boolean = !isEmpty()
}

data class ReviewerIdentity(
    val key: String,
    val username: String?,
    val name: String?,
) {
    val label: String = username?.takeIf(String::isNotBlank)?.let { "@${it.removePrefix("@")}" }
        ?: name?.takeIf(String::isNotBlank)
        ?: key
}

object ReviewerChangeExtractor {
    fun extract(changes: MergeRequestChanges?): ReviewerChange {
        if (changes == null) return ReviewerChange(emptyList(), emptyList())
        return extractTyped(changes.reviewers).takeIf(ReviewerChange::isNotEmpty)
            ?: extractRaw(changes.any()?.get("reviewers"))
    }

    private fun extractTyped(reviewers: ChangeContainer<List<Reviewer>>?): ReviewerChange {
        if (reviewers == null) return ReviewerChange(emptyList(), emptyList())
        return diff(
            previous = reviewers.previous.orEmpty().mapNotNull { it.reviewerIdentity() },
            current = reviewers.current.orEmpty().mapNotNull { it.reviewerIdentity() },
        )
    }

    private fun extractRaw(container: Any?): ReviewerChange {
        val (previous, current) = when (container) {
            is ChangeContainer<*> -> container.previous to container.current
            is Map<*, *> -> container["previous"] to container["current"]
            else -> null to null
        }
        return diff(previous.toReviewerIdentities(), current.toReviewerIdentities())
    }

    private fun diff(
        previous: List<ReviewerIdentity>,
        current: List<ReviewerIdentity>,
    ): ReviewerChange {
        val previousByKey = previous.associateBy { it.key }
        val currentByKey = current.associateBy { it.key }
        return ReviewerChange(
            added = (currentByKey.keys - previousByKey.keys).sorted().mapNotNull(currentByKey::get),
            removed = (previousByKey.keys - currentByKey.keys).sorted().mapNotNull(previousByKey::get),
        )
    }

    private fun Any?.toReviewerIdentities(): List<ReviewerIdentity> = when (this) {
        is Iterable<*> -> mapNotNull { it.toReviewerIdentity() }
        null -> emptyList()
        else -> listOfNotNull(toReviewerIdentity())
    }

    private fun Any?.toReviewerIdentity(): ReviewerIdentity? = when (this) {
        is Reviewer -> reviewerIdentity()
        is Map<*, *> -> mapReviewerIdentity()
        is Number -> ReviewerIdentity(toString(), null, null)
        is String -> takeIf(String::isNotBlank)?.let { ReviewerIdentity(it, it, null) }
        else -> null
    }

    private fun Map<*, *>.mapReviewerIdentity(): ReviewerIdentity? {
        val username = stringValue("username")
        val name = stringValue("name")
        val id = stringValue("id")
        val key = username ?: name ?: id ?: return null
        return ReviewerIdentity(key, username, name)
    }

    private fun Map<*, *>.stringValue(key: String): String? =
        this[key]?.toString()?.takeIf(String::isNotBlank)

    private fun Reviewer.reviewerIdentity(): ReviewerIdentity? {
        val username = username?.takeIf(String::isNotBlank)
        val name = name?.takeIf(String::isNotBlank)
        val key = username ?: name ?: id?.toString() ?: return null
        return ReviewerIdentity(key, username, name)
    }
}
