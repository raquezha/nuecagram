package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.testSuite
import java.time.Instant
import java.time.temporal.ChronoUnit
import net.raquezha.nuecagram.db.AuthSessionRepository
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.testing.postgresTest

val AuthSessionRepositoryTests by testSuite {
    postgresTest("issues and verifies management links and sessions correctly") { config ->
        try {
            DatabaseFactory.initialize(config)
            val authRepo = AuthSessionRepository(DatabaseFactory)
            val installationRepo = InstallationRepository(DatabaseFactory)
            val installation = installationRepo.createInstallation(
                repoName = "repo-auth-test",
                chatName = null,
                gitlabBaseUrl = "https://gitlab.com",
                gitlabProjectId = 300L,
                telegramChatId = 3000L,
                telegramTopicId = null,
            )

            val link = authRepo.issueManagementLink(installation.id, Instant.now().plus(30, ChronoUnit.MINUTES))
            val session = authRepo.exchangeManagementLinkForSession(link.raw, Instant.now().plus(8, ChronoUnit.HOURS))

            assertThat(session).isNotNull()
            val verified = authRepo.verifyManagementSession(session!!.raw)
            assertThat(verified).isNotNull()
            assertThat(verified?.installationId).isEqualTo(installation.id)
            assertThat(authRepo.verifyManagementCsrf(verified!!, session.csrf)).isTrue()
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }

    postgresTest("issues and verifies platform admin sessions and nonces") { config ->
        try {
            DatabaseFactory.initialize(config)
            val authRepo = AuthSessionRepository(DatabaseFactory)

            val adminSession = authRepo.issuePlatformAdminSession(Instant.now().plus(1, ChronoUnit.HOURS))
            val verifiedAdmin = authRepo.verifyPlatformAdminSession(adminSession.raw)
            assertThat(verifiedAdmin).isNotNull()
            assertThat(authRepo.verifyPlatformAdminCsrf(verifiedAdmin!!, adminSession.csrf)).isTrue()

            val nonce = authRepo.issueLaunchNonce(
                telegramChatId = 12345L,
                telegramTopicId = null,
                telegramUserId = 67890L,
                expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES),
            )
            val consumed = authRepo.consumeLaunchNonce(nonce.raw, 67890L)
            assertThat(consumed).isNotNull()
            assertThat(consumed?.telegramChatId).isEqualTo(12345L)
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }
}
