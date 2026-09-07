package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.testSuite
import java.util.UUID
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.db.WebhookStateRepository
import net.raquezha.nuecagram.testing.postgresTest

val WebhookStateRepositoryTests by testSuite {
    postgresTest("upserts and retrieves active merge requests and participants correctly") { config ->
        try {
            DatabaseFactory.initialize(config)
            val stateRepo = WebhookStateRepository(DatabaseFactory)
            val installationRepo = InstallationRepository(DatabaseFactory)
            val installation = installationRepo.createInstallation(
                repoName = "repo-1",
                chatName = null,
                gitlabBaseUrl = "https://gitlab.com",
                gitlabProjectId = 100L,
                telegramChatId = 1000L,
                telegramTopicId = null,
            )

            stateRepo.upsertMrParticipants(
                installationId = installation.id,
                projectId = 100L,
                mrIid = 42L,
                authorUsername = "alice",
                reviewerUsernames = listOf("bob", "charlie"),
            )

            val participants = stateRepo.getMrParticipants(installation.id, 100L, 42L)
            assertThat(participants).isNotNull()
            assertThat(participants?.authorUsername).isEqualTo("alice")
            assertThat(participants?.reviewerUsernames).containsExactly("bob", "charlie").inOrder()

            stateRepo.upsertActiveMr(
                installationId = installation.id,
                projectId = 100L,
                sourceBranch = "feature-branch",
                mrIid = 42L,
                lastCommitSha = "abc1234",
            )

            val activeMr = stateRepo.getActiveMrForBranch(installation.id, 100L, "feature-branch")
            assertThat(activeMr).isNotNull()
            assertThat(activeMr?.mrIid).isEqualTo(42L)
            assertThat(activeMr?.lastCommitSha).isEqualTo("abc1234")

            stateRepo.clearActiveMr(installation.id, 100L, "feature-branch")
            assertThat(stateRepo.getActiveMrForBranch(installation.id, 100L, "feature-branch")).isNull()
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }

    postgresTest("records latest push sha and handles event deduplication") { config ->
        try {
            DatabaseFactory.initialize(config)
            val stateRepo = WebhookStateRepository(DatabaseFactory)
            val installationRepo = InstallationRepository(DatabaseFactory)
            val installation = installationRepo.createInstallation(
                repoName = "repo-2",
                chatName = null,
                gitlabBaseUrl = "https://gitlab.com",
                gitlabProjectId = 200L,
                telegramChatId = 2000L,
                telegramTopicId = null,
            )

            stateRepo.upsertLatestPushSha(installation.id, 200L, "main", "sha-111")
            assertThat(stateRepo.getLatestPushSha(installation.id, 200L, "main")).isEqualTo("sha-111")

            val uuid = UUID.randomUUID().toString()
            val firstRecord = stateRepo.tryRecordProcessedEvent(uuid, installation.id, "push")
            val secondRecord = stateRepo.tryRecordProcessedEvent(uuid, installation.id, "push")
            assertThat(firstRecord).isTrue()
            assertThat(secondRecord).isFalse()

            stateRepo.clearProcessedWebhookEvents()
            val recordAfterClear = stateRepo.tryRecordProcessedEvent(uuid, installation.id, "push")
            assertThat(recordAfterClear).isTrue()
        } finally {
            // Pool cleaned up automatically on re-initialization
        }
    }
}
