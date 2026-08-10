package net.raquezha.nuecagram.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

data class DatabaseConfig(
    val url: String,
    val username: String,
    val password: String,
) {
    companion object {
        fun fromEnvironment() =
            DatabaseConfig(
                requiredEnvironment("DATABASE_URL"),
                requiredEnvironment("DATABASE_USER"),
                requiredEnvironment("DATABASE_PASSWORD"),
            )

        private fun requiredEnvironment(name: String) =
            System.getenv(name)?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("$name missing")
    }
}

object DatabaseFactory {
    @Volatile
    private var dataSource: HikariDataSource? = null

    @Volatile
    private var database: Database? = null

    fun initialize(config: DatabaseConfig = DatabaseConfig.fromEnvironment()) {
        synchronized(this) {
            if (dataSource != null) return

            val source =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = config.url
                        username = config.username
                        setPassword(config.password)
                        driverClassName = "org.postgresql.Driver"
                    },
                )
            try {
                Flyway.configure().dataSource(source).load().migrate()
                database = Database.connect(source)
                dataSource = source
            } catch (exception: Exception) {
                source.close()
                throw exception
            }
        }
    }

    suspend fun <T> dbQuery(block: (java.sql.Connection) -> T): T =
        withContext(Dispatchers.IO) {
            val source = dataSource ?: throw IllegalStateException("DatabaseFactory is not initialized")
            source.connection.use(block)
        }

    suspend fun <T> dbTransaction(block: Transaction.() -> T): T =
        withContext(Dispatchers.IO) {
            transaction(
                db = database ?: throw IllegalStateException("DatabaseFactory is not initialized"),
                statement = block,
            )
        }

    suspend fun isReady() =
        withContext(Dispatchers.IO) {
            val source = dataSource ?: return@withContext false
            runCatching {
                source.connection.use { connection ->
                    connection.createStatement().use { statement -> statement.execute("SELECT 1") }
                }
            }.isSuccess
        }

    fun close() = synchronized(this) {
        dataSource?.close()
        dataSource = null
        database = null
    }

    fun install(application: Application) {
        initialize()
        application.monitor.subscribe(io.ktor.server.application.ApplicationStopped) { close() }
    }
}
