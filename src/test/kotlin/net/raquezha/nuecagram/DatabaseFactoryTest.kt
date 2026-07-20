package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.disable
import de.infix.testBalloon.framework.core.testSuite
import java.sql.DriverManager
import net.raquezha.nuecagram.db.DatabaseConfig
import net.raquezha.nuecagram.db.DatabaseFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

private val dockerAvailable = DockerClientFactory.instance().isDockerAvailable

val DatabaseFactoryTests by testSuite {
    test("reports unavailable before initialization") {
        DatabaseFactory.close()
        assertThat(DatabaseFactory.isReady()).isFalse()
    }

    test(
        "migrates schema and reports ready",
        testConfig = if (dockerAvailable) TestConfig else TestConfig.disable(),
    ) {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { postgres ->
            postgres.start()
            try {
                DatabaseFactory.initialize(
                    DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.getPassword()),
                )

                val tables =
                    DriverManager.getConnection(
                        postgres.jdbcUrl,
                        postgres.username,
                        postgres.getPassword(),
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
}
