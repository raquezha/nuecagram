package net.raquezha.nuecagram.testing

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.TestSuiteScope
import de.infix.testBalloon.framework.core.disable
import de.infix.testBalloon.framework.shared.TestRegistering
import net.raquezha.nuecagram.db.DatabaseConfig
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

val dockerAvailable: Boolean by lazy {
    DockerClientFactory.instance().isDockerAvailable
}

/**
 * A domain-specific test runner that automatically spins up a PostgreSQL container,
 * skips the test if Docker is unavailable, and injects the database configuration.
 */
@TestRegistering
fun TestSuiteScope.postgresTest(
    name: String,
    action: suspend (DatabaseConfig) -> Unit,
) {
    test(
        name,
        testConfig = if (dockerAvailable) TestConfig else TestConfig.disable(),
    ) {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { postgres ->
            postgres.start()

            // Extract credentials from container
            val dbUrl = postgres.jdbcUrl
            val dbUser = postgres.username
            val dbPass = postgres.getPassword()

            val config = DatabaseConfig(dbUrl, dbUser, dbPass)
            action(config)
        }
    }
}
