package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.disable
import de.infix.testBalloon.framework.core.testSuite
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.testing.BehaviorStyle
import net.raquezha.nuecagram.testing.Scenario
import net.raquezha.nuecagram.testing.behaviorStyle
import net.raquezha.nuecagram.testing.dockerAvailable
import net.raquezha.nuecagram.testing.TestDatabase

private class SecurityScenarioContext {
    lateinit var repo: InstallationRepository
    lateinit var instId: UUID
    var str: String = ""
    var rotId: UUID? = null
}

val WebhookSecurityBehaviorTest by testSuite {
    Scenario(
        "token issuance rotation and confirmation lifecycle with edge cases",
        context = { SecurityScenarioContext() },
        testConfig =
            if (dockerAvailable) TestConfig.behaviorStyle(BehaviorStyle.Hierarchical) else TestConfig.disable(),
    ) {
        Given("an active installation") {
            TestDatabase.ensureInitialized()
            val repository = InstallationRepository(DatabaseFactory)
            val pId = 301L
            val cId = 20001L
            val inst = repository.createInstallation("https://example.com", pId, cId, null)
            instId = inst.id
            repo = repository

            val expiry = Instant.now().plusSeconds(600)
            val sec = runBlocking { repo.issueWebhookSecret(instId, expiry) }
            str = sec.raw
        }
        When("an invalid token string is verified") {
            // Testing invalid token edge case
        }
        Then("verification returns null without throwing") {
            val invalidVerified = runBlocking { repo.verifyWebhookSecret("invalid-token-string") }
            assertThat(invalidVerified).isNull()
        }
        When("valid webhook token is verified") {
            val verified = runBlocking { repo.verifyWebhookSecret(str) }
            assertThat(verified).isNotNull()
            assertThat(verified?.installationId).isEqualTo(instId)
        }
        Then("the token successfully authenticates the installation") {
            assertThat(str).isNotEmpty()
        }
        When("token is rotated with grace period") {
            val expiry = Instant.now().plusSeconds(600)
            val rot = runBlocking { repo.rotateWebhookSecret(instId, expiry) }
            rotId = rot.id
        }
        Then("new token is active and confirmation resolves pending state") {
            val ok = runBlocking { repo.confirmWebhookSecret(rotId!!) }
            assertThat(ok).isTrue()
        }
        When("attempting to confirm an already confirmed token") {
            // Re-confirming same token ID
        }
        Then("re-confirmation returns false as already confirmed") {
            val reConfirmed = runBlocking { repo.confirmWebhookSecret(rotId!!) }
            assertThat(reConfirmed).isFalse()
        }
    }
}
