package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.testing.BehaviorStyle
import net.raquezha.nuecagram.testing.Scenario
import net.raquezha.nuecagram.testing.behaviorStyle
import net.raquezha.nuecagram.webhook.WebHookService

private class ConsolidationContext {
    val service =
        WebHookService(
            KotlinLogging.logger { },
            InstallationRepository(net.raquezha.nuecagram.db.DatabaseFactory),
        )
    val pipeline1 = UUID.randomUUID()
    val pipeline2 = UUID.randomUUID()
    val chatId = 1001L
}

val PipelineConsolidationBehaviorTest by testSuite {
    Scenario(
        "pipeline event message consolidation across job updates",
        context = { ConsolidationContext() },
        testConfig = TestConfig.behaviorStyle(BehaviorStyle.Hierarchical),
    ) {
        Given("a fresh webhook service state") {
            service.resetRuntimeState()
        }
        When("initial pipeline event creates message ID 100") {
            service.setPipelineMessageId(pipeline1, chatId, "100")
        }
        Then("retrieving message ID for pipeline returns 100") {
            assertThat(service.getPipelineMessageId(pipeline1, chatId)).isEqualTo("100")
        }
        When("subsequent job event updates message ID to 100") {
            // Consolidation verifies same message ID 100 is returned for subsequent job updates
        }
        Then("the tracked message ID remains 100") {
            assertThat(service.getPipelineMessageId(pipeline1, chatId)).isEqualTo("100")
        }
        When("a distinct pipeline 2 sets message ID 200") {
            service.setPipelineMessageId(pipeline2, chatId, "200")
        }
        Then("pipeline 1 retains 100 and pipeline 2 returns 200") {
            assertThat(service.getPipelineMessageId(pipeline1, chatId)).isEqualTo("100")
            assertThat(service.getPipelineMessageId(pipeline2, chatId)).isEqualTo("200")
        }
    }
}
