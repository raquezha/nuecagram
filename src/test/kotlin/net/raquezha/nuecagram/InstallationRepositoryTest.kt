package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.testSuite
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.PlatformAdminReadRepository
import net.raquezha.nuecagram.testing.postgresTest

val InstallationRepositoryTests by testSuite {
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

            repository.writeAuditEvent(
                installationId = installation.id,
                actorType = "system",
                actorId = "tests",
                action = "rotation.confirmed",
            )
            DriverManager.getConnection(config.url, config.username, config.password).use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM audit_events WHERE installation_id = ? AND action = ?",
                ).use { statement ->
                    statement.setObject(1, installation.id)
                    statement.setString(2, "rotation.confirmed")
                    statement.executeQuery().use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getInt(1)).isEqualTo(1)
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

            val searchById = readRepository.installationsPage(search = alpha.id.toString().take(8), limit = 20)
            assertThat(searchById.totalCount).isEqualTo(1)
            assertThat(searchById.items.map { it.id }).containsExactly(alpha.id)
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

            repository.writeAuditEvent(inst.id, "telegram", "1", "telegram_setup")
            repository.writeAuditEvent(inst.id, "telegram", "1", "telegram_rotate")
            repository.writeAuditEvent(inst.id, "management", "2", "management_rotate")
            repository.writeAuditEvent(inst.id, "telegram", "1", "telegram_mute")
            repository.writeAuditEvent(inst.id, "telegram", "1", "telegram_unmute")

            val allEvents = readRepository.auditEventsPage(limit = 2, offset = 0)
            assertThat(allEvents.totalCount).isEqualTo(initialAuditCount + 5)
            assertThat(allEvents.items).hasSize(2)

            val setupEvents = readRepository.auditEventsPage(action = "setup", limit = 10)
            assertThat(setupEvents.totalCount).isEqualTo(1)
            assertThat(setupEvents.items[0].action).isEqualTo("telegram_setup")

            val rotateEvents = readRepository.auditEventsPage(action = "rotate", limit = 10)
            assertThat(rotateEvents.totalCount).isEqualTo(2)
            assertThat(rotateEvents.items.map { it.action })
                .containsExactlyElementsIn(setOf("management_rotate", "telegram_rotate"))

            val statusEvents = readRepository.auditEventsPage(action = "status_change", limit = 10)
            assertThat(statusEvents.totalCount).isEqualTo(2)
            assertThat(statusEvents.items.map { it.action })
                .containsExactlyElementsIn(setOf("telegram_unmute", "telegram_mute"))
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }
}

private fun repository(): InstallationRepository = InstallationRepository(DatabaseFactory)

private fun platformAdminReadRepository(): PlatformAdminReadRepository = PlatformAdminReadRepository(DatabaseFactory)
