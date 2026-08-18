package net.raquezha.nuecagram.db

import java.util.UUID
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll

private const val PLATFORM_ADMIN_SEARCH_FIELDS = 3
private val PLATFORM_ADMIN_SEARCH_SQL =
    """
    (
        LOWER(CAST(i.id AS text)) LIKE ? OR
        LOWER(i.gitlab_base_url) LIKE ? OR
        LOWER(CAST(i.gitlab_project_id AS text)) LIKE ?
    )
    """.trimIndent()

class PlatformAdminReadRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
) {
    suspend fun installations(): List<InstallationAdminContext> = databaseFactory.dbTransaction {
        installationWithMuteQuery()
            .orderBy(Installations.createdAt to SortOrder.DESC)
            .map { it.toAdminContext() }
    }

    suspend fun installationsPage(
        search: String? = null,
        status: String? = null,
        limit: Int = 20,
        offset: Long = 0,
    ): PlatformAdminInstallationsPage =
        databaseFactory.dbQuery { connection ->
            val filter = installationsFilter(status, search?.trim()?.takeIf(String::isNotEmpty)?.lowercase())
            val fromSql = installationsFromSql(filter.whereClauses)
            PlatformAdminInstallationsPage(
                items = selectInstallations(connection, fromSql, filter.params, limit, offset),
                totalCount = countInstallations(connection, fromSql, filter.params),
            )
        }

    suspend fun auditEvents(limit: Int = 200): List<PlatformAdminAuditRecord> = databaseFactory.dbTransaction {
        AuditEvents.selectAll()
            .orderBy(AuditEvents.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                PlatformAdminAuditRecord(
                    row[AuditEvents.installationId],
                    row[AuditEvents.action],
                    row[AuditEvents.createdAt].toInstant(),
                )
            }
    }

    suspend fun auditEventsPage(
        action: String? = null,
        limit: Int = 20,
        offset: Long = 0,
    ): PlatformAdminAuditPage =
        databaseFactory.dbQuery { connection ->
            val filter = auditFilter(action)
            val fromSql = auditFromSql(filter.whereClauses)
            PlatformAdminAuditPage(
                items = selectAuditEvents(connection, fromSql, filter.params, limit, offset),
                totalCount = countAuditEvents(connection, fromSql, filter.params),
            )
        }

    private fun auditFilter(action: String?): AuditFilter {
        val whereClauses = mutableListOf<String>()
        val params = mutableListOf<Any>()

        when (action?.trim()?.lowercase()) {
            "setup" -> {
                whereClauses += "action = ?"
                params += "telegram_setup"
            }
            "rotate" -> {
                whereClauses += "action IN (?, ?)"
                params += "telegram_rotate"
                params += "management_rotate"
            }
            "status change", "status_change", "status-change" -> {
                whereClauses += "action IN (?, ?)"
                params += "telegram_mute"
                params += "telegram_unmute"
            }
        }

        return AuditFilter(whereClauses, params)
    }

    private fun auditFromSql(whereClauses: List<String>): String {
        val whereSql =
            whereClauses.takeIf(List<String>::isNotEmpty)
                ?.joinToString(prefix = " WHERE ", separator = " AND ")
                .orEmpty()
        return "FROM audit_events $whereSql"
    }

    private fun countAuditEvents(
        connection: java.sql.Connection,
        fromSql: String,
        params: List<Any>,
    ): Long =
        connection.prepareStatement("SELECT COUNT(*) $fromSql").use { statement ->
            bindParams(statement, params)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else 0L }
        }

    private fun selectAuditEvents(
        connection: java.sql.Connection,
        fromSql: String,
        params: List<Any>,
        limit: Int,
        offset: Long,
    ): List<PlatformAdminAuditRecord> =
        connection.prepareStatement(
            """
            SELECT installation_id, action, created_at
            $fromSql
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
        ).use { statement ->
            bindParams(statement, params)
            statement.setInt(params.size + 1, limit.coerceAtLeast(1))
            statement.setLong(params.size + 2, offset.coerceAtLeast(0))
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val instId = result.getObject("installation_id", UUID::class.java)
                        val act = result.getString("action")
                        val created = result.getTimestamp("created_at").toInstant()
                        add(PlatformAdminAuditRecord(instId, act, created))
                    }
                }
            }
        }

    private fun installationsFilter(status: String?, search: String?): InstallationsFilter {
        val whereClauses = mutableListOf<String>()
        val params = mutableListOf<Any>()

        when (status?.trim()?.lowercase()) {
            "active" -> whereClauses += "COALESCE(m.muted, FALSE) = FALSE"
            "muted" -> whereClauses += "COALESCE(m.muted, FALSE) = TRUE"
        }

        if (search != null) {
            whereClauses += PLATFORM_ADMIN_SEARCH_SQL
            val searchTerm = "%$search%"
            repeat(PLATFORM_ADMIN_SEARCH_FIELDS) { params += searchTerm }
        }

        return InstallationsFilter(whereClauses, params)
    }

    private fun installationsFromSql(whereClauses: List<String>): String {
        val whereSql =
            whereClauses.takeIf(List<String>::isNotEmpty)
                ?.joinToString(prefix = " WHERE ", separator = " AND ")
                .orEmpty()
        return """
            FROM installations i
            LEFT JOIN mute_states m ON i.id = m.installation_id
            $whereSql
        """.trimIndent()
    }

    private fun countInstallations(
        connection: java.sql.Connection,
        fromSql: String,
        params: List<Any>,
    ): Long =
        connection.prepareStatement("SELECT COUNT(*) $fromSql").use { statement ->
            bindParams(statement, params)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else 0L }
        }

    private fun selectInstallations(
        connection: java.sql.Connection,
        fromSql: String,
        params: List<Any>,
        limit: Int,
        offset: Long,
    ): List<InstallationAdminContext> =
        connection.prepareStatement(
            """
            SELECT i.id, i.gitlab_base_url, i.gitlab_project_id, i.telegram_chat_id,
                i.telegram_topic_id, COALESCE(m.muted, FALSE) AS muted
            $fromSql
            ORDER BY i.created_at DESC, i.id DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
        ).use { statement ->
            bindParams(statement, params)
            statement.setInt(params.size + 1, limit.coerceAtLeast(1))
            statement.setLong(params.size + 2, offset.coerceAtLeast(0))
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.toInstallationAdminContext())
                }
            }
        }
}

private data class AuditFilter(
    val whereClauses: List<String>,
    val params: List<Any>,
)

private data class InstallationsFilter(
    val whereClauses: List<String>,
    val params: List<Any>,
)

private fun installationWithMuteQuery() =
    Installations.join(
        MuteStates,
        JoinType.LEFT,
        Installations.id,
        MuteStates.installationId,
    ).selectAll()

private fun org.jetbrains.exposed.v1.core.ResultRow.toAdminContext() = InstallationAdminContext(
    id = this[Installations.id],
    gitlabBaseUrl = this[Installations.gitlabBaseUrl],
    gitlabProjectId = this[Installations.gitlabProjectId],
    telegramChatId = this[Installations.telegramChatId],
    telegramTopicId = this[Installations.telegramTopicId],
    muted = getOrNull(MuteStates.muted) ?: false,
)

private fun java.sql.ResultSet.toInstallationAdminContext() = InstallationAdminContext(
    id = getObject("id", UUID::class.java),
    gitlabBaseUrl = getString("gitlab_base_url"),
    gitlabProjectId = getObject("gitlab_project_id") as Long?,
    telegramChatId = getLong("telegram_chat_id"),
    telegramTopicId = getObject("telegram_topic_id") as Long?,
    muted = getBoolean("muted"),
)

private fun bindParams(statement: java.sql.PreparedStatement, params: List<Any>) {
    params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
}
