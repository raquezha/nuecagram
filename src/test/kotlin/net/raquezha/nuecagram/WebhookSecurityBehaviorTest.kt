package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.testSuite
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.testing.Scenario
import net.raquezha.nuecagram.testing.postgresTest

private class SecurityScenarioContext {
    lateinit var repo: InstallationRepository
    lateinit var instId: UUID
    var str: String = ""
    var rotId: UUID? = null
}

val WebhookSecurityBehaviorTest by testSuite {
    postgresTest("webhook token security lifecycle rotation and confirmation") {
        val repository = InstallationRepository(DatabaseFactory)
        Scenario(
            "token issuance rotation and confirmation lifecycle",
            context = { SecurityScenarioContext() },
        ) {
            Given("an active installation") {
                val pId = 301L
                val cId = 20001L
                val inst = repository.createInstallation("https://example.com", pId, cId, null)
                instId = inst.id
                repo = repository

                val expiry = Instant.now().plusSeconds(600)
                val sec = runBlocking { repo.issueWebhookSecret(instId, expiry) }
                str = sec.raw
            }
            When("webhook token is validated") {
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
        }
    }
}
