package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testSuite
import java.sql.DriverManager
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.testing.BehaviorStyle
import net.raquezha.nuecagram.testing.Scenario
import net.raquezha.nuecagram.testing.behaviorStyle
import net.raquezha.nuecagram.testing.postgresTest

private class ReadinessScenario {
    var ready = false
}

val DatabaseFactoryTests by testSuite {
    Scenario(
        "database readiness before initialization",
        context = { ReadinessScenario() },
        testConfig = TestConfig.behaviorStyle(BehaviorStyle.Hierarchical),
    ) {
        Given("the database factory is closed") {
            DatabaseFactory.close()
        }
        When("readiness is checked") {
            ready = DatabaseFactory.isReady()
        }
        Then("it is unavailable") {
            assertThat(ready).isFalse()
        }
    }

    postgresTest("migrates schema and reports ready") { config ->
        try {
            DatabaseFactory.initialize(config)

            val tables =
                DriverManager.getConnection(
                    config.url,
                    config.username,
                    config.password,
                ).use { connection ->
                    connection.prepareStatement(
                        "SELECT tablename FROM pg_tables WHERE schemaname = 'public'",
                    ).use { statement ->
                        statement.executeQuery().use { result ->
                            buildSet {
                                while (result.next()) add(result.getString("tablename"))
                            }
                        }
                    }
                }

            assertThat(tables).containsAtLeast(
                "installations",
                "webhook_secrets",
                "management_links",
                "management_sessions",
                "platform_admin_sessions",
                "audit_events",
                "event_summaries",
                "mute_states",
            )
            assertThat(DatabaseFactory.isReady()).isTrue()
        } finally {
            DatabaseFactory.close()
        }
    }
}
