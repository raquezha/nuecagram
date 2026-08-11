package net.raquezha.nuecagram.testing

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.TestSuiteScope
import de.infix.testBalloon.framework.core.disable
import de.infix.testBalloon.framework.shared.TestRegistering
import net.raquezha.nuecagram.db.DatabaseConfig
import net.raquezha.nuecagram.db.DatabaseFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

val dockerAvailable: Boolean by lazy {
    DockerClientFactory.instance().isDockerAvailable
}

private val sharedPostgresContainer by lazy {
    PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
        start()
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { stop() }
        })
    }
}

fun ensureSharedTestDatabase() {
    if (!dockerAvailable) return
    val config = DatabaseConfig(
        sharedPostgresContainer.jdbcUrl,
        sharedPostgresContainer.username,
        sharedPostgresContainer.password,
    )
    DatabaseFactory.initialize(config)
}

/**
 * A domain-specific test runner that uses a shared PostgreSQL container,
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
        ensureSharedTestDatabase()
        val config = DatabaseConfig(
            sharedPostgresContainer.jdbcUrl,
            sharedPostgresContainer.username,
            sharedPostgresContainer.password,
        )
        action(config)
    }
}
