package net.raquezha

import com.google.common.truth.Truth.assertThat
import net.raquezha.nuecagram.webhook.FilterDecision
import net.raquezha.nuecagram.webhook.ReviewerChangeExtractor
import net.raquezha.nuecagram.webhook.WebhookEventFilter
import org.gitlab4j.api.models.Assignee
import org.gitlab4j.api.models.Reviewer
import org.gitlab4j.api.webhook.ChangeContainer
import org.gitlab4j.api.webhook.EventLabel
import org.gitlab4j.api.webhook.MergeRequestChanges
import org.gitlab4j.api.webhook.MergeRequestEvent
import org.gitlab4j.api.webhook.PushEvent
import org.junit.Test

@Suppress("TooManyFunctions")
class WebhookEventFilterTest {

    private val filter = WebhookEventFilter()

    @Test
    fun `evaluates non-MR events as PROCESS`() {
        val pushEvent = PushEvent()
        val decision = filter.evaluate(pushEvent, "sha123")
        assertThat(decision).isEqualTo(FilterDecision.PROCESS)
    }

    @Test
    fun `evaluates MR lifecycle actions open, close, merge as PROCESS`() {
        val actions = listOf("open", "close", "merge", "reopen", "approved", "unapproved")
        for (action in actions) {
            val event = MergeRequestEvent().apply {
                objectAttributes = MergeRequestEvent.ObjectAttributes().apply {
                    this.action = action
                    lastCommit = org.gitlab4j.api.webhook.EventCommit().apply { id = "sha123" }
                }
            }
            val decision = filter.evaluate(event, "sha123")
            assertThat(decision).isEqualTo(FilterDecision.PROCESS)
        }
    }

    @Test
    fun `evaluates MR update with title change as PROCESS`() {
        val event = createMrUpdateEvent("sha123", MergeRequestChanges().apply {
            title = ChangeContainer<String>().apply {
                previous = "Old Title"
                current = "New Title"
            }
        })
        val decision = filter.evaluate(event, "sha123")
        assertThat(decision).isEqualTo(FilterDecision.PROCESS)
    }

    @Test
    fun `evaluates MR update with description or label change as PROCESS`() {
        val descEvent = createMrUpdateEvent("sha123", MergeRequestChanges().apply {
            description = ChangeContainer<String>().apply {
                previous = "Old Desc"
                current = "New Desc"
            }
        })
        assertThat(filter.evaluate(descEvent, "sha123")).isEqualTo(FilterDecision.PROCESS)

        val labelEvent = createMrUpdateEvent("sha123", MergeRequestChanges().apply {
            labels = ChangeContainer<List<EventLabel>>().apply {
                previous = emptyList()
                current = listOf(EventLabel().apply { title = "Bug" })
            }
        })
        assertThat(filter.evaluate(labelEvent, "sha123")).isEqualTo(FilterDecision.PROCESS)
    }

    @Test
    fun `evaluates MR update with assignee or reviewer change as PROCESS`() {
        val assigneeEvent = createMrUpdateEvent("sha123", MergeRequestChanges().apply {
            assignees = ChangeContainer<List<Assignee>>().apply {
                previous = emptyList()
                current = listOf(Assignee().apply { name = "Bob" })
            }
        })
        assertThat(filter.evaluate(assigneeEvent, "sha123")).isEqualTo(FilterDecision.PROCESS)

        val reviewerEvent = createMrUpdateEvent("sha123", MergeRequestChanges().apply {
            reviewers = ChangeContainer<List<Reviewer>>().apply {
                previous = emptyList()
                current = listOf(Reviewer().apply { name = "Alice" })
            }
        })
        assertThat(filter.evaluate(reviewerEvent, "sha123")).isEqualTo(FilterDecision.PROCESS)
    }

    @Test
    fun `extracts raw reviewer changes and processes them`() {
        val removal = ReviewerChangeExtractor.extract(rawReviewerChanges(listOf("bob"), emptyList()))
        assertThat(removal.added).isEmpty()
        assertThat(removal.removed.map { it.label }).containsExactly("@bob")

        val replacement = ReviewerChangeExtractor.extract(rawReviewerChanges(listOf("bob"), listOf("charlie")))
        assertThat(replacement.added.map { it.label }).containsExactly("@charlie")
        assertThat(replacement.removed.map { it.label }).containsExactly("@bob")

        val event = createMrUpdateEvent("sha123", rawReviewerChanges(emptyList(), listOf("bob")))
        assertThat(filter.evaluate(event, "sha123")).isEqualTo(FilterDecision.PROCESS)
    }

    @Test
    fun `evaluates MR update with extra structural changes as PROCESS`() {
        val event = createMrUpdateEvent("sha123", MergeRequestChanges().apply {
            set("target_branch", ChangeContainer<Any>().apply {
                previous = "main"
                current = "develop"
            })
        })
        val decision = filter.evaluate(event, "sha123")
        assertThat(decision).isEqualTo(FilterDecision.PROCESS)
    }

    @Test
    fun `evaluates MR update with unchanged extra map fields as SKIP`() {
        val event = createMrUpdateEvent("sha123", MergeRequestChanges().apply {
            set("target_branch", ChangeContainer<Any>().apply {
                previous = "main"
                current = "main"
            })
        })
        val decision = filter.evaluate(event, "sha123")
        assertThat(decision).isEqualTo(FilterDecision.SKIP_REDUNDANT_PUSH_MR_UPDATE)
    }

    @Test
    fun `evaluates MR update without structural changes and matching push SHA as SKIP`() {
        val event = createMrUpdateEvent("sha123", MergeRequestChanges().apply {
            updatedAt = ChangeContainer<java.util.Date>()
        })
        val decision = filter.evaluate(event, "sha123")
        assertThat(decision).isEqualTo(FilterDecision.SKIP_REDUNDANT_PUSH_MR_UPDATE)
    }

    @Test
    fun `evaluates MR update with case insensitive matching push SHA as SKIP`() {
        val event = createMrUpdateEvent("ABC1234", MergeRequestChanges().apply {
            updatedAt = ChangeContainer<java.util.Date>()
        })
        val decision = filter.evaluate(event, "abc1234")
        assertThat(decision).isEqualTo(FilterDecision.SKIP_REDUNDANT_PUSH_MR_UPDATE)
    }

    @Test
    fun `evaluates MR update without structural changes and mismatched push SHA as PROCESS`() {
        val event = createMrUpdateEvent("sha123", MergeRequestChanges())
        val decision = filter.evaluate(event, "different_sha")
        assertThat(decision).isEqualTo(FilterDecision.PROCESS)
    }

    @Test
    fun `evaluates MR update with null push SHA as PROCESS`() {
        val event = createMrUpdateEvent("sha123", MergeRequestChanges())
        val decision = filter.evaluate(event, null)
        assertThat(decision).isEqualTo(FilterDecision.PROCESS)
    }

    private fun rawReviewerChanges(
        previous: List<String>,
        current: List<String>,
    ): MergeRequestChanges = MergeRequestChanges().apply {
        set("reviewers", ChangeContainer<Any>().apply {
            this.previous = previous.mapIndexed { index, username -> rawReviewer(index, username) }
            this.current = current.mapIndexed { index, username -> rawReviewer(index, username) }
        })
    }

    private fun rawReviewer(index: Int, username: String): Map<String, Any> =
        mapOf("id" to index, "username" to username)

    private fun createMrUpdateEvent(commitSha: String, mrChanges: MergeRequestChanges): MergeRequestEvent =
        MergeRequestEvent().apply {
            objectAttributes = MergeRequestEvent.ObjectAttributes().apply {
                action = "update"
                lastCommit = org.gitlab4j.api.webhook.EventCommit().apply { id = commitSha }
            }
            changes = mrChanges
        }
}
