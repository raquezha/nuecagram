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
    private const val DEFAULT_MAX_POOL_SIZE = 20
    private const val DEFAULT_MIN_IDLE = 2
    private const val DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L

    @Volatile
    private var dataSource: HikariDataSource? = null

    @Volatile
    private var database: Database? = null

    @Volatile
    private var initializedUrl: String? = null

    @Volatile
    private var lastConfig: DatabaseConfig? = null

    fun initialize(config: DatabaseConfig = DatabaseConfig.fromEnvironment()) {
        synchronized(this) {
            lastConfig = config
            if (dataSource != null && database != null && initializedUrl == config.url) return
            // Re-initializing or URL changed — close old pool first
            if (dataSource != null) {
                runCatching { dataSource?.close() }
                dataSource = null
                database = null
            }

            val source =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = config.url
                        username = config.username
                        setPassword(config.password)
                        driverClassName = "org.postgresql.Driver"
                        maximumPoolSize = DEFAULT_MAX_POOL_SIZE
                        minimumIdle = DEFAULT_MIN_IDLE
                        connectionTimeout = DEFAULT_CONNECTION_TIMEOUT_MS
                    },
                )
            try {
                Flyway.configure().dataSource(source).load().migrate()
                database = Database.connect(source)
                dataSource = source
                initializedUrl = config.url
            } catch (exception: Exception) {
                exception.printStackTrace()
                source.close()
                throw exception
            }
        }
    }

    suspend fun <T> dbQuery(block: (java.sql.Connection) -> T): T =
        withContext(Dispatchers.IO) {
            val source = dataSource ?: synchronized(this@DatabaseFactory) {
                dataSource ?: run {
                    lastConfig?.let { initialize(it) }
                    dataSource ?: throw IllegalStateException("DatabaseFactory is not initialized")
                }
            }
            source.connection.use(block)
        }

    suspend fun <T> dbTransaction(block: Transaction.() -> T): T =
        withContext(Dispatchers.IO) {
            val db = database ?: synchronized(this@DatabaseFactory) {
                database ?: run {
                    lastConfig?.let { initialize(it) }
                    database ?: throw IllegalStateException("DatabaseFactory is not initialized")
                }
            }
            transaction(
                db = db,
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
        initializedUrl = null
    }

    fun install(application: Application) {
        initialize()
        application.monitor.subscribe(io.ktor.server.application.ApplicationStopped) { close() }
    }
}
