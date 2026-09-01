package net.raquezha.nuecagram.db

import java.util.UUID
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll

private const val PLATFORM_ADMIN_SEARCH_FIELDS = 5
private const val SHORT_INSTALLATION_ID_LENGTH = 8
private val PLATFORM_ADMIN_SEARCH_SQL =
    """
    (
        LOWER(CAST(i.id AS text)) LIKE ? OR
        LOWER(i.gitlab_base_url) LIKE ? OR
        LOWER(CAST(i.gitlab_project_id AS text)) LIKE ? OR
        LOWER(i.repo_name) LIKE ? OR
        LOWER(COALESCE(i.chat_name, '')) LIKE ?
    )
    """.trimIndent()
private val AUDIT_SETUP_ACTIONS = listOf("telegram_setup", "telegram_webapp_launch", "webapp_setup")
private val AUDIT_ROTATE_ACTIONS = listOf("telegram_rotate", "management_rotate", "webapp_rotate")
private val AUDIT_STATUS_ACTIONS =
    listOf(
        "telegram_mute",
        "telegram_unmute",
        "management_mute",
        "management_unmute",
        "webapp_mute",
        "webapp_unmute",
    )
private const val BLANK_VALUE_LABEL = "(blank)"

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

    suspend fun auditEvents(limit: Int = 200): List<PlatformAdminAuditRecord> =
        auditEventsPage(limit = limit).items

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
            "setup" -> whereClauses += actionInSql(AUDIT_SETUP_ACTIONS, params)
            "rotate" -> whereClauses += actionInSql(AUDIT_ROTATE_ACTIONS, params)
            "status change", "status_change", "status-change" ->
                whereClauses += actionInSql(AUDIT_STATUS_ACTIONS, params)
        }

        return AuditFilter(whereClauses, params)
    }

    private fun actionInSql(actions: List<String>, params: MutableList<Any>): String {
        params.addAll(actions)
        return actions.joinToString(prefix = "action IN (", postfix = ")", separator = ", ") { "?" }
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
            SELECT installation_id,
                   actor_id,
                   action,
                   created_at,
                   metadata ->> 'repo_name' AS repo_name,
                   metadata ->> 'username' AS username,
                   metadata ->> 'first_name' AS first_name,
                   metadata ->> 'chat_id' AS chat_id,
                   metadata ->> 'topic_id' AS topic_id,
                   metadata ->> 'nickname' AS nickname,
                   metadata ->> 'old_repo_name' AS old_repo_name,
                   metadata ->> 'new_repo_name' AS new_repo_name,
                   metadata ->> 'old_nickname' AS old_nickname,
                   metadata ->> 'new_nickname' AS new_nickname
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
                    while (result.next()) add(result.toPlatformAdminAuditRecord())
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
            SELECT i.id, i.repo_name, i.chat_name, i.gitlab_base_url, i.gitlab_project_id, i.telegram_chat_id,
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
    repoName = this[Installations.repoName],
    chatName = this[Installations.chatName],
    gitlabBaseUrl = this[Installations.gitlabBaseUrl],
    gitlabProjectId = this[Installations.gitlabProjectId],
    telegramChatId = this[Installations.telegramChatId],
    telegramTopicId = this[Installations.telegramTopicId],
    muted = getOrNull(MuteStates.muted) ?: false,
)

private fun java.sql.ResultSet.toInstallationAdminContext() = InstallationAdminContext(
    id = getObject("id", UUID::class.java),
    repoName = getString("repo_name"),
    chatName = getString("chat_name"),
    gitlabBaseUrl = getString("gitlab_base_url"),
    gitlabProjectId = getObject("gitlab_project_id") as Long?,
    telegramChatId = getLong("telegram_chat_id"),
    telegramTopicId = getObject("telegram_topic_id") as Long?,
    muted = getBoolean("muted"),
)

private fun java.sql.ResultSet.toPlatformAdminAuditRecord(): PlatformAdminAuditRecord {
    val installationId = getObject("installation_id", UUID::class.java)
    val chatId = getString("chat_id")
    val topicId = getString("topic_id")
    val nickname = getString("nickname")
    val oldRepoName = getString("old_repo_name")
    val newRepoName = getString("new_repo_name")
    val oldNickname = getString("old_nickname")
    val newNickname = getString("new_nickname")

    return PlatformAdminAuditRecord(
        installationId = installationId,
        action = getString("action"),
        createdAt = getTimestamp("created_at").toInstant(),
        repository = getString("repo_name").orRepositoryFallback(installationId),
        actor = actorLabel(getString("username"), getString("first_name"), getString("actor_id")),
        chatDetails = chatLabel(chatId, topicId),
        details = buildList {
            nickname?.takeIf(String::isNotBlank)?.let { add("nickname: $it") }
            aliasDeltaLine("repo", oldRepoName, newRepoName)?.let(::add)
            aliasDeltaLine("chat", oldNickname, newNickname)?.let(::add)
        },
    )
}

private fun String?.orRepositoryFallback(installationId: UUID?): String =
    this?.takeIf(String::isNotBlank)
        ?: installationId?.toString()?.take(SHORT_INSTALLATION_ID_LENGTH)
        ?: "System"

private fun actorLabel(username: String?, firstName: String?, actorId: String?): String =
    username?.takeIf(String::isNotBlank)?.let { "@$it" }
        ?: firstName?.takeIf(String::isNotBlank)
        ?: actorId?.takeIf(String::isNotBlank)
        ?: "Unknown Actor"

private fun chatLabel(chatId: String?, topicId: String?): String =
    chatId?.takeIf(String::isNotBlank)?.let {
        topicId?.takeIf(String::isNotBlank)?.let { topic -> "$it / topic $topic" } ?: it
    } ?: "Unknown Chat"

private fun aliasDeltaLine(label: String, oldValue: String?, newValue: String?): String? {
    if (oldValue == null && newValue == null) return null
    val from = oldValue.orEmpty().ifBlank { BLANK_VALUE_LABEL }
    val to = newValue.orEmpty().ifBlank { BLANK_VALUE_LABEL }
    return if (from == to) null else "$label: $from -> $to"
}

private fun bindParams(statement: java.sql.PreparedStatement, params: List<Any>) {
    params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
}
