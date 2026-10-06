package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import net.raquezha.nuecagram.webhook.ChatDetails
import net.raquezha.nuecagram.webhook.RenovatePipelineCards
import org.junit.Test

class RenovateCardRecoveryTest : BaseEventTestHelper() {
    @Test
    fun mrFirstIdentityAndBothPipelineOutcomesSurviveRestart() = runBlocking {
        val key = RenovatePipelineCards.Key(
            installation.id, installation.gitlabProjectId!!, "renovate/recovery", "sha-a",
        )
        val before = RenovatePipelineCards()
        before.recordMr(key, 12, "MR !12")
        before.cancelPending(key.installationId, key.projectId, key.branch, "999")
        val restarted = RenovatePipelineCards()
        assertThat(restarted.findExistingMessageId(key.installationId, key.projectId, key.branch)).isEqualTo("999")
        restarted.recordOutcome(
            key, 101172, "Failed validate: /pipelines/101172", Instant.parse("2026-01-01T00:01:00Z"),
        )
        restarted.recordOutcome(
            key, 101173, "Failed policy:changeset: /pipelines/101173", Instant.parse("2026-01-01T00:02:00Z"),
        )
        restarted.recordOutcome(key, 101172, "Running validate", Instant.parse("2026-01-01T00:00:00Z"))
        val card = RenovatePipelineCards().render(key)
        assertThat(card).contains("MR !12")
        assertThat(card).contains("Failed validate: /pipelines/101172")
        assertThat(card).contains("Failed policy:changeset: /pipelines/101173")
        assertThat(card).doesNotContain("Running validate")
        restarted.record(key, 101172, ChatDetails("123"), card)
        restarted.cancelPending(key.installationId, key.projectId, key.branch, "999")
    }
}
