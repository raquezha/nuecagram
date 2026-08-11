package net.raquezha.nuecagram.testing

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.TestSuiteScope
import de.infix.testBalloon.framework.core.disable
import de.infix.testBalloon.framework.shared.TestRegistering
import net.raquezha.nuecagram.db.DatabaseConfig
import org.testcontainers.DockerClientFactory

val dockerAvailable: Boolean by lazy {
    DockerClientFactory.instance().isDockerAvailable
}

/**
 * A domain-specific test runner that uses the shared PostgreSQL test database,
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
        TestDatabase.ensureInitialized()
        val config = DatabaseConfig(
            TestDatabase.container.jdbcUrl,
            TestDatabase.container.username,
            TestDatabase.container.password,
        )
        action(config)
    }
}
