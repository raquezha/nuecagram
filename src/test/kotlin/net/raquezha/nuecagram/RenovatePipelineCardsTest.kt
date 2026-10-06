package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.webhook.ChatDetails
import net.raquezha.nuecagram.webhook.EventData
import net.raquezha.nuecagram.webhook.RandomMessageProvider
import net.raquezha.nuecagram.webhook.RenovatePipelineCards
import net.raquezha.nuecagram.webhook.WebHookService
import net.raquezha.nuecagram.webhook.WebhookMessageFormatter
import net.raquezha.nuecagram.webhook.WebhookRequestHandler
import org.gitlab4j.api.utils.JacksonJson
import org.gitlab4j.api.webhook.PipelineEvent
import org.junit.Test

class RenovatePipelineCardsTest : BaseEventTestHelper() {
    private class AdjustableClock(now: Instant) : Clock() {
        private val current = AtomicReference(now)
        override fun instant(): Instant = current.get()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        fun advanceSeconds(seconds: Long) { current.updateAndGet { it.plusSeconds(seconds) } }
    }

    @Test
    fun testWorkerDeliversFallbackAfterTimeout() = runBlocking {
        DatabaseFactory.dbQuery { connection ->
            connection.prepareStatement("DELETE FROM renovate_pipeline_cards").use { it.executeUpdate() }
        }
        val clock = AdjustableClock(Instant.now())
        val logger = KotlinLogging.logger {}
        val handler = WebhookRequestHandler(
            randomMessageProvider = RandomMessageProvider(),
            webhookService = WebHookService(logger, installationRepository),
            installationRepository = installationRepository,
            telegramService = telegramService,
            formatter = WebhookMessageFormatter(),
            logger = logger,
            renovateClock = clock,
        )
        val payload = PipelineEventWebhookTest.SAMPLE_PAYLOAD_SUCCESS
            .replace("\"ref\": \"main\"", "\"ref\": \"renovate/gradle-and-kotlin-dependencies\"")
            .replace("\"username\": \"raquezha\"", "\"username\": \"project_599_bot_token\"")
        val event = JacksonJson().unmarshal(PipelineEvent::class.java, payload)
        val job = launch { handler.processQueue() }
        try {
            handler.enqueue(EventData(installation.id, event, EVENT_PIPELINE, ChatDetails("-123")))
            // Wait until the webhook has actually been persisted before advancing the fake clock.
            var staged = false
            for (attempt in 1..50) {
                staged = DatabaseFactory.dbQuery { connection ->
                    connection.prepareStatement(
                        "SELECT 1 FROM renovate_pipeline_cards WHERE installation_id = ? AND commit_sha = ?",
                    ).use { statement ->
                        statement.setObject(1, installation.id)
                        statement.setString(2, event.commit.id)
                        statement.executeQuery().use { it.next() }
                    }
                }
                if (staged) break
                delay(20)
            }
            assertThat(staged).isTrue()
            assertThat(sentMessages()).isEmpty()
            clock.advanceSeconds(121)
            for (attempt in 1..50) {
                if (sentMessages().isNotEmpty()) break
                delay(50)
            }
            assertThat(sentMessages()).hasSize(1)
            assertThat(sentMessages().single().text).contains("Pipeline")
        } finally {
            handler.close()
            job.join()
        }
    }

    @Test
    fun testBotBranchPipelineWaitsForMrBeforeSendingCard() = testApplication {
        configureTestApplication()
        val payload = PipelineEventWebhookTest.SAMPLE_PAYLOAD_SUCCESS
            .replace("\"ref\": \"main\"", "\"ref\": \"renovate/gradle-and-kotlin-dependencies\"")
            .replace("\"username\": \"raquezha\"", "\"username\": \"project_599_bot_token\"")
        postWebhook(EVENT_PIPELINE, payload)
        delay(200)
        assertThat(sentMessages()).isEmpty()
    }

    @Test
    fun testRenovateBotPushDoesNotSendStandaloneBubble() = testApplication {
        configureTestApplication()
        val payload = """
{
  "object_kind": "push",
  "event_name": "push",
  "before": "00000000",
  "after": "abc1234",
  "ref": "refs/heads/renovate/gradle-and-kotlin-dependencies",
  "user_name": "RENOVATE",
  "user_username": "project_599_bot_token",
  "project_id": ${installation.gitlabProjectId},
  "project": { "id": ${installation.gitlabProjectId}, "name": "customer-app", "web_url": "https://gitlab.com/android-team/customer-app" },
  "commits": [
    { "id": "abc1234", "title": "Update dependencies", "url": "https://gitlab.com/android-team/customer-app/-/commit/abc1234" }
  ],
  "total_commits_count": 1
}
        """.trimIndent()
        postWebhook(EVENT_PUSH, payload)
        delay(200)
        assertThat(sentMessages()).isEmpty()
    }

    @Test
    fun testRenovateMrAdoptsExistingStandaloneCard() = testApplication {
        configureTestApplication()
        val branch = "renovate/gradle-and-kotlin-dependencies"
        val sha = "def5678"
        val projectId = installation.gitlabProjectId!!
        val key = RenovatePipelineCards.Key(installation.id, projectId, branch, sha)
        val destination = ChatDetails(installation.telegramChatId.toString(), installation.telegramTopicId.toString())
        val cards = RenovatePipelineCards()

        // Simulate a standalone card previously delivered for this Renovate branch
        cards.record(key, 101172, destination, "Standalone card")
        val dueCards = RenovatePipelineCards(Clock.fixed(Instant.now().plusSeconds(125), ZoneOffset.UTC))
        val due = dueCards.claimDue(100).single { it.key == key }
        cards.markSent(due, "999")

        // Now post a Merge Request event for the same branch
        val mrPayload = """
{
  "object_kind": "merge_request",
  "event_type": "merge_request",
  "user": { "id": 1, "name": "RENOVATE", "username": "project_599_bot_token" },
  "project": { "id": ${installation.gitlabProjectId}, "name": "customer-app", "web_url": "https://gitlab.com/android-team/customer-app" },
  "object_attributes": {
    "id": 99,
    "iid": 12,
    "title": "Update dependencies",
    "source_branch": "$branch",
    "target_branch": "main",
    "action": "open",
    "last_commit": { "id": "$sha", "message": "Update dependencies" }
  }
}
        """.trimIndent()

        postWebhook(EVENT_MERGE, mrPayload)
        delay(200)

        // It should edit the existing message 999 rather than create a new message bubble
        val messages = sentMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages.single().messageId).isEqualTo("999")
    }

    @Test
    fun testRenovateMrPipelineUpdatesSameMrCard() = testApplication {
        configureTestApplication()
        val branch = "renovate/gradle-and-kotlin-dependencies"
        val sha = "def5678"
        val projectId = installation.gitlabProjectId!!

        // Post Merge Request event
        val mrPayload = """
{
  "object_kind": "merge_request",
  "event_type": "merge_request",
  "user": { "id": 1, "name": "RENOVATE", "username": "project_599_bot_token" },
  "project": { "id": $projectId, "name": "customer-app", "web_url": "https://gitlab.com/android-team/customer-app" },
  "object_attributes": {
    "id": 99,
    "iid": 12,
    "title": "Update dependencies",
    "source_branch": "$branch",
    "target_branch": "main",
    "action": "open",
    "last_commit": { "id": "$sha", "message": "Update dependencies" }
  }
}
        """.trimIndent()
        postWebhook(EVENT_MERGE, mrPayload)
        delay(200)

        val mrMessages = sentMessages()
        assertThat(mrMessages).hasSize(1)
        assertThat(mrMessages.first().text).contains("Merge Request")

        // Post MR Pipeline event
        val pipelinePayload = PipelineEventWebhookTest.SAMPLE_PAYLOAD_MR_SUCCESS
            .replace("\"id\": 105", "\"id\": $projectId")
            .replace("\"target_project_id\": 105", "\"target_project_id\": $projectId")
            .replace("\"iid\": 2923", "\"iid\": 12")
            .replace("\"username\": \"alice\"", "\"username\": \"project_599_bot_token\"")

        postWebhook(EVENT_PIPELINE, pipelinePayload)
        delay(200)

        // The pipeline event should update message "1" (the initial MR card)
        val finalMessages = sentMessages()
        assertThat(finalMessages.mapNotNull { it.messageId }).contains("1")
    }

    @Test
    fun testPendingCardSurvivesRestartAndOnlyOneWorkerClaimsIt() = runBlocking {
        val now = Instant.now()
        val key = RenovatePipelineCards.Key(
            installation.id,
            599L,
            "renovate/gradle-and-kotlin-dependencies",
            UUID.randomUUID().toString(),
        )
        val destination = ChatDetails("-123", "456")
        val beforeRestart = RenovatePipelineCards(Clock.fixed(now, ZoneOffset.UTC))
        assertThat(beforeRestart.record(key, 101172, destination, "Running #101172")).isNull()
        assertThat(beforeRestart.claimDue()).isEmpty()
        assertThat(beforeRestart.record(key, 101172, destination, "Failed #101172 validate")).isNull()

        // The coordinator is recreated after the deadline, as it would be after a restart.
        val afterRestart = RenovatePipelineCards(Clock.fixed(now.plusSeconds(121), ZoneOffset.UTC))
        val claimed = afterRestart.claimDue().single { it.key == key }
        assertThat(claimed.text).isEqualTo("Failed #101172 validate")
        assertThat(claimed.chatDetails).isEqualTo(destination)
        assertThat(afterRestart.claimDue().none { it.key == key }).isTrue()
        assertThat(afterRestart.markSent(claimed, "42")).isEqualTo("Failed #101172 validate")
        // A database response can be lost after committing the receipt; persisting it again must be safe.
        assertThat(afterRestart.markSent(claimed, "42")).isEqualTo("Failed #101172 validate")

        assertThat(RenovatePipelineCards(Clock.fixed(now.plusSeconds(122), ZoneOffset.UTC))
            .record(key, 101172, destination, "Passed #101172")).isEqualTo("42")
        assertThat(afterRestart.claimDue().none { it.key == key }).isTrue()
    }

    @Test
    fun testKnownMrWithoutMessageIdentityRecoversPendingCard() = runBlocking {
        val now = Instant.now()
        val sha = UUID.randomUUID().toString()
        val key = RenovatePipelineCards.Key(installation.id, 599L, "renovate/has-mr", sha)
        RenovatePipelineCards(Clock.fixed(now, ZoneOffset.UTC))
            .record(key, 101172, ChatDetails("-123"), "Failed validate")
        installationRepository.upsertActiveMr(installation.id, 599L, key.branch, 12L, lastCommitSha = sha)

        val afterDeadline = RenovatePipelineCards(Clock.fixed(now.plusSeconds(121), ZoneOffset.UTC))
        assertThat(afterDeadline.claimDue().any { it.key == key }).isTrue()
    }

    @Test
    fun testPendingCardDoesNotAppearBeforeDeadlineEvenWithMultipleStatuses() = runBlocking {
        val now = Instant.now()
        val key = RenovatePipelineCards.Key(installation.id, 599L, "renovate/retries", UUID.randomUUID().toString())
        val pending = RenovatePipelineCards(Clock.fixed(now, ZoneOffset.UTC))
        pending.record(key, 101174, ChatDetails("-123"), "Running")
        val stillPending = RenovatePipelineCards(Clock.fixed(now.plusSeconds(119), ZoneOffset.UTC))
        stillPending.record(key, 101174, ChatDetails("-123"), "Failed after retry")
        assertThat(stillPending.claimDue().none { it.key == key }).isTrue()
        assertThat(RenovatePipelineCards(Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC))
            .claimDue().any { it.key == key }).isTrue()
    }
}
