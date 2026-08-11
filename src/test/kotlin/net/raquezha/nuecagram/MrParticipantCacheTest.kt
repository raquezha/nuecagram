package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import de.infix.testBalloon.framework.core.testSuite
import net.raquezha.nuecagram.db.DatabaseFactory
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.testing.postgresTest

val MrParticipantCacheTests by testSuite {
    postgresTest("upserts and retrieves MR participants by installation, project, and mr_iid") { config ->
        DatabaseFactory.initialize(config)
        val repository = InstallationRepository(DatabaseFactory)
        val installation = repository.createInstallation("https://gitlab.example.com", 101, 1001, null)

        val initial = repository.getMrParticipants(installation.id, 101L, 42L)
        assertThat(initial).isNull()

        repository.upsertMrParticipants(
            installationId = installation.id,
            projectId = 101L,
            mrIid = 42L,
            authorUsername = "alice",
            reviewerUsernames = listOf("bob", "charlie"),
        )

        val cached = repository.getMrParticipants(installation.id, 101L, 42L)
        assertThat(cached).isNotNull()
        assertThat(cached?.authorUsername).isEqualTo("alice")
        assertThat(cached?.reviewerUsernames).containsExactly("bob", "charlie").inOrder()

        // Update with new reviewers
        repository.upsertMrParticipants(
            installationId = installation.id,
            projectId = 101L,
            mrIid = 42L,
            authorUsername = "alice",
            reviewerUsernames = listOf("dave"),
        )

        val updated = repository.getMrParticipants(installation.id, 101L, 42L)
        assertThat(updated?.reviewerUsernames).containsExactly("dave")
    }
}
