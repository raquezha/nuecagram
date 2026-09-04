package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import net.raquezha.nuecagram.webhook.FilterDecision
import net.raquezha.nuecagram.webhook.WebhookEventFilter
import org.gitlab4j.api.webhook.ChangeContainer
import org.gitlab4j.api.webhook.MergeRequestChanges
import org.gitlab4j.api.webhook.MergeRequestEvent
import org.gitlab4j.api.webhook.PushEvent
import org.junit.Test

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
        val event = MergeRequestEvent().apply {
            objectAttributes = MergeRequestEvent.ObjectAttributes().apply {
                action = "update"
                lastCommit = org.gitlab4j.api.webhook.EventCommit().apply { id = "sha123" }
            }
            changes = MergeRequestChanges().apply {
                title = ChangeContainer<String>().apply {
                    previous = "Old Title"
                    current = "New Title"
                }
            }
        }
        val decision = filter.evaluate(event, "sha123")
        assertThat(decision).isEqualTo(FilterDecision.PROCESS)
    }

    @Test
    fun `evaluates MR update with extra structural changes as PROCESS`() {
        val event = MergeRequestEvent().apply {
            objectAttributes = MergeRequestEvent.ObjectAttributes().apply {
                action = "update"
                lastCommit = org.gitlab4j.api.webhook.EventCommit().apply { id = "sha123" }
            }
            changes = MergeRequestChanges().apply {
                set("target_branch", ChangeContainer<Any>().apply {
                    previous = "main"
                    current = "develop"
                })
            }
        }
        val decision = filter.evaluate(event, "sha123")
        assertThat(decision).isEqualTo(FilterDecision.PROCESS)
    }

    @Test
    fun `evaluates MR update without structural changes and matching push SHA as SKIP`() {
        val event = MergeRequestEvent().apply {
            objectAttributes = MergeRequestEvent.ObjectAttributes().apply {
                action = "update"
                lastCommit = org.gitlab4j.api.webhook.EventCommit().apply { id = "sha123" }
            }
            changes = MergeRequestChanges().apply {
                updatedAt = ChangeContainer<java.util.Date>()
            }
        }
        val decision = filter.evaluate(event, "sha123")
        assertThat(decision).isEqualTo(FilterDecision.SKIP_REDUNDANT_PUSH_MR_UPDATE)
    }

    @Test
    fun `evaluates MR update without structural changes and mismatched push SHA as PROCESS`() {
        val event = MergeRequestEvent().apply {
            objectAttributes = MergeRequestEvent.ObjectAttributes().apply {
                action = "update"
                lastCommit = org.gitlab4j.api.webhook.EventCommit().apply { id = "sha123" }
            }
            changes = MergeRequestChanges()
        }
        val decision = filter.evaluate(event, "different_sha")
        assertThat(decision).isEqualTo(FilterDecision.PROCESS)
    }

    @Test
    fun `evaluates MR update with null push SHA as PROCESS`() {
        val event = MergeRequestEvent().apply {
            objectAttributes = MergeRequestEvent.ObjectAttributes().apply {
                action = "update"
                lastCommit = org.gitlab4j.api.webhook.EventCommit().apply { id = "sha123" }
            }
            changes = MergeRequestChanges()
        }
        val decision = filter.evaluate(event, null)
        assertThat(decision).isEqualTo(FilterDecision.PROCESS)
    }
}
