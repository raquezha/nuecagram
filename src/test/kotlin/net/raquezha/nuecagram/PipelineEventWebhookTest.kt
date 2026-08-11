package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.server.testing.testApplication
import net.raquezha.nuecagram.di.testAppModule
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin

class PipelineEventWebhookTest : BaseEventTestHelper() {
    @Test
    fun testWebhookPipelineEvents() =
        testApplication {
            configureTestApplication()

            // Test running pipeline
            val runningResponse = postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_RUNNING)
            assertThat(runningResponse).isEqualTo("Webhook received successfully")

            // Test failed pipeline
            val failedResponse = postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_FAILED)
            assertThat(failedResponse).isEqualTo("Webhook received successfully")

            // Test success pipeline
            val successResponse = postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_SUCCESS)
            assertThat(successResponse).isEqualTo("Webhook received successfully")
        }

    @Test
    fun testMrPipelineSuccessPingsReviewers() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            kotlinx.coroutines.runBlocking {
                installationRepository.upsertMrParticipants(
                    installationId = installation.id,
                    projectId = 105L,
                    mrIid = 2923L,
                    authorUsername = "alice",
                    reviewerUsernames = listOf("bob", "charlie"),
                )
            }

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_SUCCESS)

            val completionReply = kotlinx.coroutines.runBlocking {
                var found: net.raquezha.nuecagram.telegram.Message? = null
                for (i in 1..100) {
                    found = mockTelegramService.sentMessages().find { it.text.contains("@bob") }
                    if (found != null) break
                    kotlinx.coroutines.delay(50)
                }
                found
            }

            assertThat(completionReply).isNotNull()
            assertThat(completionReply?.text).contains("@bob @charlie")
        }

    @Test
    fun testMrPipelineFailurePingsCreator() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = (telegramService as net.raquezha.nuecagram.telegram.MockTelegramService)
            mockTelegramService.reset()

            kotlinx.coroutines.runBlocking {
                installationRepository.upsertMrParticipants(
                    installationId = installation.id,
                    projectId = 105L,
                    mrIid = 2924L,
                    authorUsername = "alice",
                    reviewerUsernames = listOf("bob"),
                )
            }

            postWebhook(EVENT_PIPELINE, SAMPLE_PAYLOAD_MR_FAILED)

            val completionReply = kotlinx.coroutines.runBlocking {
                var found: net.raquezha.nuecagram.telegram.Message? = null
                for (i in 1..100) {
                    found = mockTelegramService.sentMessages().find { it.text.contains("@alice") }
                    if (found != null) break
                    kotlinx.coroutines.delay(50)
                }
                found
            }

            assertThat(completionReply).isNotNull()
            assertThat(completionReply?.text).contains("@alice")
            assertThat(completionReply?.text).doesNotContain("@bob")
        }

    companion object {
        val SAMPLE_PAYLOAD_MR_SUCCESS =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 8888,
    "iid": 2923,
    "source": "merge_request_event",
    "status": "success",
    "ref": "feature-branch",
    "stages": ["test"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": "2024-06-19 02:25:18 UTC",
    "duration": 300,
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/8888"
  },
  "merge_request": {
    "id": 999,
    "iid": 2923,
    "title": "Add feature",
    "source_branch": "feature-branch",
    "target_branch": "main",
    "state": "opened",
    "url": "https://gitlab.com/android-team/customer-app/-/merge_requests/2923"
  },
  "user": {
    "id": 38,
    "name": "Alice Author",
    "username": "alice"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "web_url": "https://gitlab.com/android-team/customer-app"
  }
}
""".trimIndent()

        val SAMPLE_PAYLOAD_MR_FAILED =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 8889,
    "iid": 2924,
    "source": "merge_request_event",
    "status": "failed",
    "ref": "feature-branch",
    "stages": ["test"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": "2024-06-19 02:25:18 UTC",
    "duration": 300,
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/8889"
  },
  "merge_request": {
    "id": 1000,
    "iid": 2924,
    "title": "Add feature",
    "source_branch": "feature-branch",
    "target_branch": "main",
    "state": "opened",
    "url": "https://gitlab.com/android-team/customer-app/-/merge_requests/2924"
  },
  "user": {
    "id": 38,
    "name": "Alice Author",
    "username": "alice"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "web_url": "https://gitlab.com/android-team/customer-app"
  }
}
""".trimIndent()
        @BeforeClass
        @JvmStatic
        fun setUpClass() {

            if (GlobalContext.getOrNull() == null) {
                startKoin {
                    modules(testAppModule())
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            stopKoin()
        }

        val SAMPLE_PAYLOAD_RUNNING =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 53479,
    "iid": 2923,
    "name": null,
    "ref": "main",
    "tag": false,
    "sha": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "before_sha": "0000000000000000000000000000000000000000",
    "source": "push",
    "status": "running",
    "detailed_status": "running",
    "stages": ["prepare", "test", "deploy"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": null,
    "duration": null,
    "queued_duration": 1,
    "variables": [],
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/53479"
  },
  "merge_request": null,
  "user": {
    "id": 38,
    "name": "raquezha",
    "username": "raquezha",
    "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
    "email": "[REDACTED]"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "description": "This is awesome",
    "web_url": "https://gitlab.com/android-team/customer-app",
    "avatar_url": null,
    "git_ssh_url": "git@gitlab.com:android-team/customer-app.git",
    "git_http_url": "https://gitlab.com/android-team/customer-app.git",
    "namespace": "customer-app",
    "visibility_level": 0,
    "path_with_namespace": "android-team/customer-app",
    "default_branch": "main",
    "ci_config_path": null
  },
  "commit": {
    "id": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "message": "Add new feature",
    "title": "Add new feature",
    "timestamp": "2024-05-20T04:51:32+00:00",
    "url": "https://gitlab.com/android-team/customer-app/-/commit/e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "author": {
      "name": "raquezha",
      "email": "[REDACTED]"
    }
  },
  "builds": [
    {
      "id": 481131,
      "stage": "prepare",
      "name": "prepare",
      "status": "success",
      "created_at": "2024-06-19 03:05:35 UTC",
      "started_at": "2024-06-19 03:05:36 UTC",
      "finished_at": "2024-06-19 03:05:51 UTC",
      "duration": 14.930185,
      "queued_duration": 1.460193,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480895,
      "stage": "test",
      "name": "ktlint",
      "status": "running",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": "2024-06-19 03:05:52 UTC",
      "finished_at": null,
      "duration": null,
      "queued_duration": 0.4089,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480896,
      "stage": "test",
      "name": "detekt",
      "status": "pending",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": null,
      "finished_at": null,
      "duration": null,
      "queued_duration": null,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480897,
      "stage": "deploy",
      "name": "deploy",
      "status": "pending",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": null,
      "finished_at": null,
      "duration": null,
      "queued_duration": null,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    }
  ]
}
            """.trimIndent()

        val SAMPLE_PAYLOAD_FAILED =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 53480,
    "iid": 2924,
    "name": null,
    "ref": "main",
    "tag": false,
    "sha": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "before_sha": "0000000000000000000000000000000000000000",
    "source": "push",
    "status": "failed",
    "detailed_status": "failed",
    "stages": ["prepare", "test", "deploy"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": "2024-06-19 03:06:41 UTC",
    "duration": 64,
    "queued_duration": 1,
    "variables": [],
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/53480"
  },
  "merge_request": null,
  "user": {
    "id": 38,
    "name": "raquezha",
    "username": "raquezha",
    "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
    "email": "[REDACTED]"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "description": "This is awesome",
    "web_url": "https://gitlab.com/android-team/customer-app",
    "avatar_url": null,
    "git_ssh_url": "git@gitlab.com:android-team/customer-app.git",
    "git_http_url": "https://gitlab.com/android-team/customer-app.git",
    "namespace": "customer-app",
    "visibility_level": 0,
    "path_with_namespace": "android-team/customer-app",
    "default_branch": "main",
    "ci_config_path": null
  },
  "commit": {
    "id": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "message": "Add new feature",
    "title": "Add new feature",
    "timestamp": "2024-05-20T04:51:32+00:00",
    "url": "https://gitlab.com/android-team/customer-app/-/commit/e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "author": {
      "name": "raquezha",
      "email": "[REDACTED]"
    }
  },
  "builds": [
    {
      "id": 481131,
      "stage": "prepare",
      "name": "prepare",
      "status": "success",
      "created_at": "2024-06-19 03:05:35 UTC",
      "started_at": "2024-06-19 03:05:36 UTC",
      "finished_at": "2024-06-19 03:05:51 UTC",
      "duration": 14.930185,
      "queued_duration": 1.460193,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480895,
      "stage": "test",
      "name": "ktlint",
      "status": "failed",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": "2024-06-19 03:05:52 UTC",
      "finished_at": "2024-06-19 03:06:41 UTC",
      "duration": 49.304208,
      "queued_duration": 0.4089,
      "failure_reason": "script_failure",
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480896,
      "stage": "test",
      "name": "detekt",
      "status": "skipped",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": null,
      "finished_at": null,
      "duration": null,
      "queued_duration": null,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480897,
      "stage": "deploy",
      "name": "deploy",
      "status": "skipped",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": null,
      "finished_at": null,
      "duration": null,
      "queued_duration": null,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    }
  ]
}
            """.trimIndent()

        val SAMPLE_PAYLOAD_SUCCESS =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 53481,
    "iid": 2925,
    "name": null,
    "ref": "main",
    "tag": false,
    "sha": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "before_sha": "0000000000000000000000000000000000000000",
    "source": "push",
    "status": "success",
    "detailed_status": "passed",
    "stages": ["prepare", "test", "deploy"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": "2024-06-19 03:10:00 UTC",
    "duration": 178,
    "queued_duration": 1,
    "variables": [],
    "url": "https://gitlab.com/android-team/customer-app/-/pipelines/53481"
  },
  "merge_request": null,
  "user": {
    "id": 38,
    "name": "raquezha",
    "username": "raquezha",
    "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
    "email": "[REDACTED]"
  },
  "project": {
    "id": 105,
    "name": "customer-app",
    "description": "This is awesome",
    "web_url": "https://gitlab.com/android-team/customer-app",
    "avatar_url": null,
    "git_ssh_url": "git@gitlab.com:android-team/customer-app.git",
    "git_http_url": "https://gitlab.com/android-team/customer-app.git",
    "namespace": "customer-app",
    "visibility_level": 0,
    "path_with_namespace": "android-team/customer-app",
    "default_branch": "main",
    "ci_config_path": null
  },
  "commit": {
    "id": "e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "message": "Add new feature",
    "title": "Add new feature",
    "timestamp": "2024-05-20T04:51:32+00:00",
    "url": "https://gitlab.com/android-team/customer-app/-/commit/e2b9fff8bb1f4a7c7036b348963358bd74457cc2",
    "author": {
      "name": "raquezha",
      "email": "[REDACTED]"
    }
  },
  "builds": [
    {
      "id": 481131,
      "stage": "prepare",
      "name": "prepare",
      "status": "success",
      "created_at": "2024-06-19 03:05:35 UTC",
      "started_at": "2024-06-19 03:05:36 UTC",
      "finished_at": "2024-06-19 03:05:51 UTC",
      "duration": 14.930185,
      "queued_duration": 1.460193,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480895,
      "stage": "test",
      "name": "ktlint",
      "status": "success",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": "2024-06-19 03:05:52 UTC",
      "finished_at": "2024-06-19 03:06:41 UTC",
      "duration": 49.304208,
      "queued_duration": 0.4089,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480896,
      "stage": "test",
      "name": "detekt",
      "status": "success",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": "2024-06-19 03:06:42 UTC",
      "finished_at": "2024-06-19 03:07:30 UTC",
      "duration": 48.123456,
      "queued_duration": 0.5,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    },
    {
      "id": 480897,
      "stage": "deploy",
      "name": "deploy",
      "status": "success",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": "2024-06-19 03:07:31 UTC",
      "finished_at": "2024-06-19 03:10:00 UTC",
      "duration": 149.654321,
      "queued_duration": 0.3,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 38,
        "name": "raquezha",
        "username": "raquezha",
        "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
        "email": "[REDACTED]"
      },
      "runner": null,
      "artifacts_file": { "filename": null, "size": null },
      "environment": null
    }
  ]
}
            """.trimIndent()
    }
}
