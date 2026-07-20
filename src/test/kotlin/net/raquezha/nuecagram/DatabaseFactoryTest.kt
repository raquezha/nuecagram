package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import net.raquezha.nuecagram.db.DatabaseConfig
import net.raquezha.nuecagram.db.DatabaseFactory
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

class DatabaseFactoryTest {
    @Test
    fun migratesSchemaAndReportsReady() {
        assumeTrue(
            "Docker is required for PostgreSQL integration tests",
            DockerClientFactory.instance().isDockerAvailable,
        )
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
                assertThat(runBlocking { DatabaseFactory.isReady() }).isTrue()
            } finally {
                DatabaseFactory.close()
            }
        }
    }
}
