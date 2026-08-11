package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.disable
import de.infix.testBalloon.framework.core.testSuite
import java.util.UUID
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.testing.BehaviorStyle
import net.raquezha.nuecagram.testing.Scenario
import net.raquezha.nuecagram.testing.behaviorStyle
import net.raquezha.nuecagram.testing.dockerAvailable
import net.raquezha.nuecagram.testing.TestDatabase

private class MrNotificationScenarioContext {
    lateinit var repository: InstallationRepository
    lateinit var installationId: UUID
    val projectId: Long = 200L
    val mrIid: Long = 42L
}

val MergeRequestBehaviorTest by testSuite {
    Scenario(
        "MR update caches author and reviewers with edge cases",
        context = { MrNotificationScenarioContext() },
        testConfig =
            if (dockerAvailable) TestConfig.behaviorStyle(BehaviorStyle.Hierarchical) else TestConfig.disable(),
    ) {
        Given("an active installation") {
            TestDatabase.ensureInitialized()
            val repository = InstallationRepository(DatabaseFactory)
            val inst = repository.createInstallation("https://example.com", 200, 10001, null)
            installationId = inst.id
            this.repository = repository
        }
        When("querying an uncached MR ID") {
            // No action needed; querying non-existent MR 999
        }
        Then("the repository returns null without throwing") {
            val uncached = repository.getMrParticipants(installationId, projectId, 999L)
            assertThat(uncached).isNull()
        }
        When("author alice and reviewers bob and charlie are cached for MR 42") {
            repository.upsertMrParticipants(
                installationId = installationId,
                projectId = projectId,
                mrIid = mrIid,
                authorUsername = "alice",
                reviewerUsernames = listOf("bob", "charlie"),
            )
        }
        Then("retrieving MR participants yields author alice and reviewers bob, charlie") {
            val cached = repository.getMrParticipants(installationId, projectId, mrIid)
            assertThat(cached).isNotNull()
            assertThat(cached?.authorUsername).isEqualTo("alice")
            assertThat(cached?.reviewerUsernames).containsExactly("bob", "charlie").inOrder()
        }
        When("reviewers change to dave for MR 42") {
            repository.upsertMrParticipants(
                installationId = installationId,
                projectId = projectId,
                mrIid = mrIid,
                authorUsername = "alice",
                reviewerUsernames = listOf("dave"),
            )
        }
        Then("the cache is updated to dave") {
            val updated = repository.getMrParticipants(installationId, projectId, mrIid)
            assertThat(updated?.reviewerUsernames).containsExactly("dave")
        }
        When("all reviewers are removed from MR 42") {
            repository.upsertMrParticipants(
                installationId = installationId,
                projectId = projectId,
                mrIid = mrIid,
                authorUsername = "alice",
                reviewerUsernames = emptyList(),
            )
        }
        Then("the cache reflects empty reviewers list") {
            val emptyReviewers = repository.getMrParticipants(installationId, projectId, mrIid)
            assertThat(emptyReviewers?.reviewerUsernames).isEmpty()
        }
    }
}
