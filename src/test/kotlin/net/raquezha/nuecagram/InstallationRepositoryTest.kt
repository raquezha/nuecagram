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
import net.raquezha.nuecagram.testing.postgresTest

val InstallationRepositoryTests by testSuite {
    postgresTest("persists only digests and hashes for issued credentials") { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = InstallationRepository()
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
            DatabaseFactory.close()
        }
    }

    postgresTest("verifies secrets and consumes one-time links atomically") { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = InstallationRepository()
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
        } finally {
            DatabaseFactory.close()
        }
    }

    postgresTest(
        "expires links, rotates secrets, cleans up old credentials, and writes audit events",
    ) { config ->
        try {
            DatabaseFactory.initialize(config)
            val repository = InstallationRepository()
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
            assertThat(repository.cleanupExpiredManagementLinks()).isEqualTo(1)
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
            DatabaseFactory.close()
        }
    }
}
