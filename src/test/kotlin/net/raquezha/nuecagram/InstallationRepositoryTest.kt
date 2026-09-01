package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.testSuite
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.flywaydb.core.Flyway
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.PlatformAdminReadRepository
import net.raquezha.nuecagram.testing.postgresTest

val InstallationRepositoryTests by testSuite {
    postgresTest("backfills repository identity safely during V9 migration") { config ->
        try {
            DatabaseFactory.close()
            migrateToVersion(config, "8")

            val projectInstallationId = UUID.randomUUID()
            val pathInstallationId = UUID.randomUUID()
            val fallbackInstallationId = UUID.randomUUID()

            DriverManager.getConnection(config.url, config.username, config.password).use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO installations (id, gitlab_base_url, gitlab_project_id, telegram_chat_id, telegram_topic_id)
                    VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, projectInstallationId)
                    statement.setString(2, "https://gitlab.example.com/group/project")
                    statement.setObject(3, 1234L)
                    statement.setLong(4, 100L)
                    statement.setObject(5, null)
                    statement.setObject(6, pathInstallationId)
                    statement.setString(7, "https://gitlab.example.com/group/subgroup/project")
                    statement.setObject(8, null)
                    statement.setLong(9, 101L)
                    statement.setObject(10, null)
                    statement.setObject(11, fallbackInstallationId)
                    statement.setString(12, "   ")
                    statement.setObject(13, null)
                    statement.setLong(14, 102L)
                    statement.setObject(15, null)
                    statement.executeUpdate()
                }
            }

            migrateToLatest(config)

            DriverManager.getConnection(config.url, config.username, config.password).use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id, repo_name, chat_name,
                           is_nullable, column_default
                    FROM installations
                    JOIN information_schema.columns
                      ON table_name = 'installations'
                     AND column_name = 'repo_name'
                    WHERE id IN (?, ?, ?)
                    ORDER BY id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, fallbackInstallationId)
                    statement.setObject(2, pathInstallationId)
                    statement.setObject(3, projectInstallationId)
                    statement.executeQuery().use { result ->
                        val repoNames = mutableMapOf<UUID, String>()
                        var isNullable: String? = null
                        var defaultValue: String? = null
                        while (result.next()) {
                            repoNames[result.getObject("id", UUID::class.java)] = result.getString("repo_name")
                            assertThat(result.getString("chat_name")).isEqualTo(result.getString("repo_name"))
                            isNullable = result.getString("is_nullable")
                            defaultValue = result.getString("column_default")
                        }
                        assertThat(repoNames[projectInstallationId]).isEqualTo("Project #1234")
                        assertThat(repoNames[pathInstallationId]).isEqualTo("group/subgroup/project")
                        assertThat(repoNames[fallbackInstallationId]).isEqualTo("Unknown Repository")
                        assertThat(isNullable).isEqualTo("NO")
                        assertThat(defaultValue).contains("Unknown Repository")
                    }
                }
            }
        } finally {
            DatabaseFactory.close()
        }
    }

    postgresTest("persists repo identity fields and trims chatName") { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = repository()
            val installation =
                repository.createInstallation(
                    repoName = "backend/platform",
                    chatName = "  prod alerts  ",
                    gitlabBaseUrl = "https://gitlab.example.com/group/project",
                    gitlabProjectId = 44,
                    telegramChatId = 100,
                    telegramTopicId = 7,
                )

            assertThat(installation.repoName).isEqualTo("backend/platform")
            assertThat(installation.chatName).isEqualTo("prod alerts")

            val adminContext = repository.installationAdminContext(installation.id)
            assertThat(adminContext).isNotNull()
            assertThat(adminContext!!.repoName).isEqualTo("backend/platform")
            assertThat(adminContext.chatName).isEqualTo("prod alerts")
            assertThat(adminContext.displayName).isEqualTo("prod alerts")
            assertThat(adminContext.destinationDisplayName("Deployments")).isEqualTo("prod alerts (Deployments)")
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }

    postgresTest("rejects invalid repo names for new installations") { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = repository()

            val blankError = runCatching {
                repository.createInstallation(
                    repoName = "   ",
                    gitlabBaseUrl = "https://gitlab.example.com/group/project",
                    gitlabProjectId = 45,
                    telegramChatId = 100,
                    telegramTopicId = null,
                )
            }.exceptionOrNull()
            assertThat(blankError).isInstanceOf(IllegalArgumentException::class.java)

            val fallbackError = runCatching {
                repository.createInstallation(
                    repoName = "Unknown Repository",
                    gitlabBaseUrl = "https://gitlab.example.com/group/project",
                    gitlabProjectId = 46,
                    telegramChatId = 100,
                    telegramTopicId = null,
                )
            }.exceptionOrNull()
            assertThat(fallbackError).isInstanceOf(IllegalArgumentException::class.java)
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }

    postgresTest("persists only digests and hashes for issued credentials") { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = repository()
            val installation = repository.createInstallation("https://gitlab.example.com", 1, 100, null)
            val secret = repository.issueWebhookSecret(installation.id)
            val link = repository.issueManagementLink(installation.id, Instant.now().plus(30, ChronoUnit.MINUTES))

            DriverManager.getConnection(config.url, config.username, config.password).use { connection ->
                connection.prepareStatement(
                    "SELECT secret_hash, encode(secret_digest, 'hex') AS digest FROM webhook_secrets WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, secret.id)
                    statement.executeQuery().use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getString("secret_hash")).isNotEqualTo(secret.raw)
                        assertThat(result.getString("digest")).doesNotContain(secret.raw)
                    }
                }
                connection.prepareStatement(
                    "SELECT token_hash, encode(token_digest, 'hex') AS digest FROM management_links WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, link.id)
                    statement.executeQuery().use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getString("token_hash")).isNotEqualTo(link.raw)
                        assertThat(result.getString("digest")).doesNotContain(link.raw)
                    }
                }
            }
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }

    postgresTest("verifies secrets and consumes one-time links atomically") { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = repository()
            val installation = repository.createInstallation("https://gitlab.example.com", 2, 200, null)
            val secret = repository.issueWebhookSecret(installation.id)
            assertThat(repository.verifyWebhookSecret(secret.raw)?.installationId).isEqualTo(installation.id)
            assertThat(repository.verifyWebhookSecret(secret.raw + "-wrong")).isNull()

            val link = repository.issueManagementLink(installation.id, Instant.now().plus(30, ChronoUnit.MINUTES))
            val successes = coroutineScope {
                List(8) {
                    async { repository.consumeManagementLink(link.raw) }
                }.awaitAll().filterNotNull()
            }
            assertThat(successes).hasSize(1)
            assertThat(successes.single().installationId).isEqualTo(installation.id)
            assertThat(repository.consumeManagementLink(link.raw)).isNull()

            val sessionLink =
                repository.issueManagementLink(
                    installation.id,
                    Instant.now().plus(30, ChronoUnit.MINUTES),
                )
            val session = repository.exchangeManagementLinkForSession(
                sessionLink.raw,
                Instant.now().plus(8, ChronoUnit.HOURS),
            )
            assertThat(session).isNotNull()
            val verifiedSession = repository.verifyManagementSession(session!!.raw)
            assertThat(verifiedSession?.installationId).isEqualTo(installation.id)
            assertThat(repository.verifyManagementCsrf(verifiedSession!!, session.csrf)).isTrue()
            assertThat(repository.verifyManagementCsrf(verifiedSession, "invalid")).isFalse()
            assertThat(
                repository.exchangeManagementLinkForSession(
                    sessionLink.raw,
                    Instant.now().plus(8, ChronoUnit.HOURS),
                ),
            ).isNull()
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }

    postgresTest(
        "expires links, rotates secrets, cleans up old credentials, and writes audit events",
    ) { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = repository()
            val installation =
                repository.createInstallation("https://gitlab.example.com", 3, 300, 7)
            val expiredLink =
                repository.issueManagementLink(
                    installation.id,
                    Instant.now().minus(5, ChronoUnit.MINUTES),
                )
            assertThat(repository.consumeManagementLink(expiredLink.raw)).isNull()

            val original = repository.issueWebhookSecret(installation.id)
            val rotated = repository.rotateWebhookSecret(
                installation.id,
                graceUntil = Instant.now().minus(1, ChronoUnit.MINUTES),
            )
            assertThat(repository.verifyWebhookSecret(original.raw)).isNull()
            assertThat(repository.verifyWebhookSecret(rotated.raw)?.secretId).isEqualTo(rotated.id)
            assertThat(repository.confirmWebhookSecret(rotated.id)).isTrue()
            val expiredSessionLink =
                repository.issueManagementLink(
                    installation.id,
                    Instant.now().plus(30, ChronoUnit.MINUTES),
                )
            val session = repository.exchangeManagementLinkForSession(
                expiredSessionLink.raw,
                Instant.now().minus(1, ChronoUnit.MINUTES),
            )
            assertThat(session).isNotNull()
            val platformSession =
                repository.issuePlatformAdminSession(Instant.now().minus(1, ChronoUnit.MINUTES))
            assertThat(repository.verifyPlatformAdminSession(platformSession.raw)).isNull()
            assertThat(repository.cleanupExpiredManagementLinks()).isEqualTo(1)
            assertThat(repository.cleanupExpiredManagementSessions()).isEqualTo(1)
            assertThat(repository.cleanupExpiredPlatformAdminSessions()).isEqualTo(1)
            assertThat(repository.cleanupExpiredWebhookSecrets()).isEqualTo(1)

            val wrote = repository.writeAuditEvent(
                installationId = installation.id,
                actorType = "telegram",
                actorId = "42",
                action = "rotation.confirmed",
                metadataPatch = net.raquezha.nuecagram.db.AuditMetadataPatch(
                    actorUsername = "alice",
                    actorFirstName = "Alice",
                    identityDelta = net.raquezha.nuecagram.db.AuditIdentityDelta(
                        oldRepoName = "old/repo",
                        newRepoName = "new/repo",
                        oldNickname = "old room",
                        newNickname = "new room",
                    ),
                ),
            )
            val skipped = repository.writeAuditEvent(
                installationId = installation.id,
                actorType = "telegram",
                actorId = null,
                action = "rotation.skipped",
            )

            assertThat(wrote).isTrue()
            assertThat(skipped).isFalse()

            DriverManager.getConnection(config.url, config.username, config.password).use { connection ->
                connection.prepareStatement(
                    """
                    SELECT action,
                           metadata ->> 'installation_id' AS installation_id,
                           metadata ->> 'actor_id' AS actor_id,
                           metadata ->> 'username' AS username,
                           metadata ->> 'first_name' AS first_name,
                           metadata ->> 'repo_name' AS repo_name,
                           metadata ->> 'chat_id' AS chat_id,
                           metadata ->> 'topic_id' AS topic_id,
                           metadata ->> 'old_repo_name' AS old_repo_name,
                           metadata ->> 'new_repo_name' AS new_repo_name,
                           metadata ->> 'old_nickname' AS old_nickname,
                           metadata ->> 'new_nickname' AS new_nickname
                    FROM audit_events
                    WHERE installation_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, installation.id)
                    statement.executeQuery().use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getString("action")).isEqualTo("rotation.confirmed")
                        assertThat(result.getString("installation_id")).isEqualTo(installation.id.toString())
                        assertThat(result.getString("actor_id")).isEqualTo("42")
                        assertThat(result.getString("username")).isEqualTo("alice")
                        assertThat(result.getString("first_name")).isEqualTo("Alice")
                        assertThat(result.getString("repo_name")).isEqualTo("Project #3")
                        assertThat(result.getString("chat_id")).isEqualTo("300")
                        assertThat(result.getString("topic_id")).isEqualTo("7")
                        assertThat(result.getString("old_repo_name")).isEqualTo("old/repo")
                        assertThat(result.getString("new_repo_name")).isEqualTo("new/repo")
                        assertThat(result.getString("old_nickname")).isEqualTo("old room")
                        assertThat(result.getString("new_nickname")).isEqualTo("new room")
                    }
                }
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM audit_events WHERE installation_id = ? AND action = ?",
                ).use { statement ->
                    statement.setObject(1, installation.id)
                    statement.setString(2, "rotation.skipped")
                    statement.executeQuery().use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getInt(1)).isEqualTo(0)
                    }
                }
            }
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }

    postgresTest("filters and paginates platform admin installations in the database") { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = repository()
            val readRepository = platformAdminReadRepository()
            val suiteTag = "platform-admin-page"
            val alpha = repository.createInstallation("https://$suiteTag-alpha.example.com/root", 9101, 1001, null)
            val beta = repository.createInstallation("https://$suiteTag-beta.example.com/root", 9202, 1002, null)
            val gamma = repository.createInstallation("https://$suiteTag-gamma.example.com/root", 9303, 1003, null)
            repository.setMuted(beta.id, true)

            val firstPage = readRepository.installationsPage(search = suiteTag, limit = 2, offset = 0)
            val secondPage = readRepository.installationsPage(search = suiteTag, limit = 2, offset = 2)
            assertThat(firstPage.totalCount).isEqualTo(3)
            assertThat(firstPage.items).hasSize(2)
            assertThat(secondPage.totalCount).isEqualTo(3)
            assertThat(secondPage.items).hasSize(1)
            assertThat((firstPage.items + secondPage.items).map { it.id }.toSet())
                .containsExactly(alpha.id, beta.id, gamma.id)

            val mutedOnly = readRepository.installationsPage(search = suiteTag, status = "muted", limit = 20)
            assertThat(mutedOnly.totalCount).isEqualTo(1)
            assertThat(mutedOnly.items.map { it.id }).containsExactly(beta.id)

            val activeOnly = readRepository.installationsPage(search = suiteTag, status = "active", limit = 20)
            assertThat(activeOnly.totalCount).isEqualTo(2)
            assertThat(activeOnly.items.map { it.id }).containsExactly(alpha.id, gamma.id)

            val searchByUrl = readRepository.installationsPage(search = "$suiteTag-gamma", limit = 20)
            assertThat(searchByUrl.totalCount).isEqualTo(1)
            assertThat(searchByUrl.items.map { it.id }).containsExactly(gamma.id)

            val searchByProject = readRepository.installationsPage(search = "9202", limit = 20)
            assertThat(searchByProject.totalCount).isEqualTo(1)
            assertThat(searchByProject.items.map { it.id }).containsExactly(beta.id)

            val searchByRepoName = readRepository.installationsPage(search = "project #9303", limit = 20)
            assertThat(searchByRepoName.totalCount).isEqualTo(1)
            assertThat(searchByRepoName.items.map { it.id }).containsExactly(gamma.id)

            val searchById = readRepository.installationsPage(search = alpha.id.toString().take(8), limit = 20)
            assertThat(searchById.totalCount).isEqualTo(1)
            assertThat(searchById.items.map { it.id }).containsExactly(alpha.id)
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }

    postgresTest("finds installations by repo identity without breaking existing queries") { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = repository()
            val installation =
                repository.createInstallation(
                    repoName = "group/backend",
                    chatName = "ops room",
                    gitlabBaseUrl = "https://gitlab.example.com/group/backend",
                    gitlabProjectId = 6001,
                    telegramChatId = -1001,
                    telegramTopicId = null,
                )

            assertThat(repository.findInstallationByQuery("group/back", -1001, null)?.id).isEqualTo(installation.id)
            assertThat(repository.findInstallationByQuery("ops room", -1001, null)?.id).isEqualTo(installation.id)
            assertThat(repository.findInstallationByQuery("6001", -1001, null)?.id).isEqualTo(installation.id)
            assertThat(repository.findInstallationByQuery(installation.id.toString().take(8), -1001, null)?.id)
                .isEqualTo(installation.id)
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }

    postgresTest("records and lists installation admins newest first") { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = repository()
            val older = repository.createInstallation("https://admin-old.example.com", 101, -1001, null)
            val newer = repository.createInstallation("https://admin-new.example.com", 102, -1002, 9)
            val otherAdmin = repository.createInstallation("https://admin-other.example.com", 103, -1003, null)
            repository.setMuted(newer.id, true)

            repository.recordInstallationAdmin(older.id, 42, Instant.parse("2026-08-24T01:00:00Z"))
            repository.recordInstallationAdmin(newer.id, 42, Instant.parse("2026-08-24T02:00:00Z"))
            repository.recordInstallationAdmin(otherAdmin.id, 99, Instant.parse("2026-08-24T03:00:00Z"))

            val installations = repository.installationsForAdmin(42)

            assertThat(installations.map { it.id }).containsExactly(newer.id, older.id).inOrder()
            assertThat(installations.first().telegramTopicId).isEqualTo(9)
            assertThat(installations.first().muted).isTrue()
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }

    postgresTest("filters and paginates platform admin audit events in the database") { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = repository()
            val readRepository = platformAdminReadRepository()
            val inst = repository.createInstallation("https://audit-test.example.com", 111, 222, null)

            val initialAuditCount = readRepository.auditEventsPage(limit = 1).totalCount

            repository.writeAuditEvent(
                inst.id,
                "telegram",
                "1",
                "telegram_setup",
                metadataPatch = net.raquezha.nuecagram.db.AuditMetadataPatch(actorUsername = "alice"),
            )
            repository.writeAuditEvent(inst.id, "webapp_session", "1", "webapp_setup")
            repository.writeAuditEvent(inst.id, "telegram", "1", "telegram_rotate")
            repository.writeAuditEvent(inst.id, "management", "2", "management_rotate")
            repository.writeAuditEvent(inst.id, "webapp_session", "1", "webapp_rotate")
            repository.writeAuditEvent(inst.id, "telegram", "1", "telegram_mute")
            repository.writeAuditEvent(inst.id, "management", "2", "management_mute")
            repository.writeAuditEvent(
                inst.id,
                "webapp_session",
                "1",
                "webapp_identity_update",
                metadataPatch = net.raquezha.nuecagram.db.AuditMetadataPatch(
                    actorFirstName = "Alice",
                    identityDelta = net.raquezha.nuecagram.db.AuditIdentityDelta(
                        oldRepoName = "old/repo",
                        newRepoName = "new/repo",
                        oldNickname = "old room",
                        newNickname = null,
                    ),
                ),
            )
            repository.writeAuditEvent(null, "system", null, "system_ping")

            val allEvents = readRepository.auditEventsPage(limit = 3, offset = 0)
            assertThat(allEvents.totalCount).isEqualTo(initialAuditCount + 9)
            assertThat(allEvents.items).hasSize(3)

            val setupEvents = readRepository.auditEventsPage(action = "setup", limit = 10)
            assertThat(setupEvents.totalCount).isEqualTo(2)
            assertThat(setupEvents.items.map { it.action })
                .containsExactlyElementsIn(setOf("telegram_setup", "webapp_setup"))

            val rotateEvents = readRepository.auditEventsPage(action = "rotate", limit = 10)
            assertThat(rotateEvents.totalCount).isEqualTo(3)
            assertThat(rotateEvents.items.map { it.action })
                .containsExactlyElementsIn(setOf("management_rotate", "telegram_rotate", "webapp_rotate"))

            val statusEvents = readRepository.auditEventsPage(action = "status_change", limit = 10)
            assertThat(statusEvents.totalCount).isEqualTo(2)
            assertThat(statusEvents.items.map { it.action })
                .containsExactlyElementsIn(setOf("telegram_mute", "management_mute"))

            val identityEvent =
                readRepository.auditEventsPage(limit = 10).items.first { it.action == "webapp_identity_update" }
            assertThat(identityEvent.repository).isEqualTo("Project #111")
            assertThat(identityEvent.actor).isEqualTo("Alice")
            assertThat(identityEvent.chatDetails).isEqualTo("222")
            assertThat(identityEvent.details)
                .containsExactly("repo: old/repo -> new/repo", "chat: old room -> (blank)")

            val systemEvent = readRepository.auditEventsPage(limit = 10).items.first { it.action == "system_ping" }
            assertThat(systemEvent.repository).isEqualTo("System")
            assertThat(systemEvent.actor).isEqualTo("Unknown Actor")
            assertThat(systemEvent.chatDetails).isEqualTo("Unknown Chat")
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }
}

private fun repository(): InstallationRepository = InstallationRepository(DatabaseFactory)

private fun platformAdminReadRepository(): PlatformAdminReadRepository = PlatformAdminReadRepository(DatabaseFactory)

private fun migrateToVersion(config: net.raquezha.nuecagram.db.DatabaseConfig, version: String) {
    Flyway.configure()
        .dataSource(config.url, config.username, config.password)
        .target(version)
        .cleanDisabled(false)
        .load()
        .run {
            clean()
            migrate()
        }
}

private fun migrateToLatest(config: net.raquezha.nuecagram.db.DatabaseConfig) {
    Flyway.configure()
        .dataSource(config.url, config.username, config.password)
        .load()
        .migrate()
}
