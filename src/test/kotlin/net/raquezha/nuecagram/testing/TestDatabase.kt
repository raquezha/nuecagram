package net.raquezha.nuecagram.testing

import net.raquezha.nuecagram.db.DatabaseConfig
import net.raquezha.nuecagram.db.DatabaseFactory
import org.testcontainers.containers.PostgreSQLContainer

object TestDatabase {
    @Volatile
    private var containerInstance: PostgreSQLContainer<Nothing>? = null

    val container: PostgreSQLContainer<Nothing>
        get() {
            ensureInitialized()
            return containerInstance ?: throw IllegalStateException("TestDatabase container failed to initialize")
        }

    fun ensureInitialized() {
        if (containerInstance != null) return
        synchronized(this) {
            if (containerInstance != null) return
            val instance = PostgreSQLContainer<Nothing>("postgres:16-alpine")
            instance.start()
            containerInstance = instance

            val config = DatabaseConfig(
                instance.jdbcUrl,
                instance.username,
                instance.password,
            )
            DatabaseFactory.initialize(config)
        }
    }
}
