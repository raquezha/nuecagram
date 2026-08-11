package net.raquezha.nuecagram.testing

import net.raquezha.nuecagram.db.DatabaseConfig
import net.raquezha.nuecagram.db.DatabaseFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

object TestDatabase {
    val container: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            start()
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching { stop() }
            })
        }
    }

    @Volatile
    private var isInitialized = false

    fun ensureInitialized() {
        if (!DockerClientFactory.instance().isDockerAvailable) return
        if (!isInitialized) {
            synchronized(this) {
                if (!isInitialized) {
                    val config = DatabaseConfig(
                        container.jdbcUrl,
                        container.username,
                        container.password,
                    )
                    DatabaseFactory.initialize(config)
                    isInitialized = true
                }
            }
        }
    }
}
