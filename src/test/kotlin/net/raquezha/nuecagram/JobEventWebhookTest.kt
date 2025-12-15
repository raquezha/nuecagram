package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.Test

class JobEventWebhookTest : BaseEventTestHelper() {
    /**
     * Tests job event handling in different modes.
     *
     * This test verifies the HTTP layer handles job and pipeline events correctly.
     * The internal business logic (job accumulation, skip behavior) is verified by
     * checking debug logs during test execution:
     *
     * Job-only mode (pipeline 53093):
     * - Log: "Job-only mode: Pipeline #53093 has no PipelineEvent..."
     * - Log: "Added job X to tracked pipeline 53093. Total jobs: N"
     *
     * Both-enabled mode (pipeline 99999):
     * - Log: "Marked pipeline 99999 as having PipelineEvent"
     * - Log: "Skipping BuildEvent #888888 - PipelineEvent is handling pipeline #99999"
     */
    @Test
    fun testWebhookJobEvents() =
        testApplication {
            configureTestApplication()

            // ============================================================
            // SCENARIO 1: Job-only mode (pipeline 53093)
            // ============================================================

            // First job event - triggers job accumulation
            val failedResponse = postWebhook(EVENT_JOB, SAMPLE_PAYLOAD_FAILED)
            assertThat(failedResponse).isEqualTo("Webhook received successfully")

            // Multiple jobs accumulate for the same pipeline
            val pendingResponse = postWebhook(EVENT_JOB, SAMPLE_PAYLOAD_PENDING)
            assertThat(pendingResponse).isEqualTo("Webhook received successfully")

            val runningResponse = postWebhook(EVENT_JOB, SAMPLE_PAYLOAD_RUNNING_SAME_JOB)
            assertThat(runningResponse).isEqualTo("Webhook received successfully")

            val successResponse = postWebhook(EVENT_JOB, SAMPLE_PAYLOAD_SUCCESS)
            assertThat(successResponse).isEqualTo("Webhook received successfully")

            val runningJobResponse = postWebhook(EVENT_JOB, SAMPLE_PAYLOAD_RUNNING)
            assertThat(runningJobResponse).isEqualTo("Webhook received successfully")

            delay(ASYNC_PROCESSING_DELAY_MS)

            // ============================================================
            // SCENARIO 2: Both-enabled mode (pipeline 99999)
            // ============================================================

            // Pipeline event marks this pipeline for PipelineEvent handling
            val pipelineResponse = postWebhook(EVENT_PIPELINE, SAMPLE_PIPELINE_PAYLOAD)
            assertThat(pipelineResponse).isEqualTo("Webhook received successfully")

            delay(ASYNC_PROCESSING_DELAY_MS)

            // Job event for same pipeline is skipped (see debug logs)
            val jobResponse = postWebhook(EVENT_JOB, SAMPLE_JOB_FOR_SAME_PIPELINE)
            assertThat(jobResponse).isEqualTo("Webhook received successfully")

            delay(ASYNC_PROCESSING_DELAY_MS)
        }

    companion object {
        // Delay to allow async queue processing to complete
        private const val ASYNC_PROCESSING_DELAY_MS = 100L

        val SAMPLE_PAYLOAD_FAILED =
            """
{
  "object_kind": "build",
  "ref": "t2_dispatcher_review_vR.13",
  "tag": true,
  "before_sha": "0000000000000000000000000000000000000000",
  "sha": "a811f8ab22f7c28183a28a595b034ec8bc85b935",
  "retries_count": 0,
  "build_id": 473579,
  "build_name": "publish-badge",
  "build_stage": "badge",
  "build_status": "failed",
  "build_created_at": "2024-06-11 03:02:25 UTC",
  "build_started_at": "2024-06-11 03:13:06 UTC",
  "build_finished_at": "2024-06-11 03:13:44 UTC",
  "build_duration": 38.371629,
  "build_queued_duration": 0.537157,
  "build_allow_failure": true,
  "build_failure_reason": "script_failure",
  "pipeline_id": 53093,
  "runner": {
    "id": 205,
    "description": "Runner 4",
    "runner_type": "group_type",
    "active": true,
    "is_shared": false,
    "tags": [
      "shared",
      "deploy",
      "tests",
      "notify",
      "badge"
    ]
  },
  "project_id": 282,
  "project_name": "Android Team / tindahannimama / dispatcher-app",
  "user": {
    "id": 124,
    "name": "Razyl Vidal",
    "username": "razylvidal",
    "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/124/avatar.png",
    "email": "[REDACTED]"
  },
  "commit": {
    "id": 53093,
    "name": null,
    "sha": "a811f8ab22f7c28183a28a595b034ec8bc85b935",
    "message": "Bump version\n",
    "author_name": "Razyl Abbygail Vidal",
    "author_email": "[REDACTED]",
    "author_url": "https://gitlab.com/razylvidal",
    "status": "running",
    "duration": null,
    "started_at": "2024-06-11 03:02:27 UTC",
    "finished_at": null
  },
  "repository": {
    "name": "dispatcher-app",
    "url": "git@gitlab.com:android-team/dispatcher-app.git",
    "description": null,
    "homepage": "https://gitlab.com/android-team/dispatcher-app",
    "git_http_url": "https://gitlab.com/android-team/dispatcher-app.git",
    "git_ssh_url": "git@gitlab.com:android-team/dispatcher-app.git",
    "visibility_level": 0
  },
  "project": {
    "id": 282,
    "name": "dispatcher-app",
    "description": null,
    "web_url": "https://gitlab.com/android-team/dispatcher-app",
    "avatar_url": null,
    "git_ssh_url": "git@gitlab.com:android-team/dispatcher-app.git",
    "git_http_url": "https://gitlab.com/android-team/dispatcher-app.git",
    "namespace": "tindahannimama",
    "visibility_level": 0,
    "path_with_namespace": "android-team/tindahannimama/dispatcher-app",
    "default_branch": "main",
    "ci_config_path": null
  },
  "environment": null
}
            """.trimIndent()

        val SAMPLE_PAYLOAD_RUNNING =
            """
{
  "object_kind": "build",
  "ref": "main",
  "tag": false,
  "sha": "a811f8ab22f7c28183a28a595b034ec8bc85b935",
  "build_id": 473580,
  "build_name": "unit-test",
  "build_stage": "test",
  "build_status": "running",
  "build_duration": 10.5,
  "pipeline_id": 53093,
  "project_id": 282,
  "project_name": "dispatcher-app",
  "user": {
    "id": 124,
    "name": "Razyl Vidal",
    "username": "razylvidal"
  },
  "commit": {
    "sha": "a811f8ab22f7c28183a28a595b034ec8bc85b935",
    "message": "Add new feature"
  },
  "project": {
    "id": 282,
    "name": "dispatcher-app",
    "web_url": "https://gitlab.com/android-team/dispatcher-app"
  }
}
            """.trimIndent()

        val SAMPLE_PAYLOAD_SUCCESS =
            """
{
  "object_kind": "build",
  "ref": "main",
  "tag": false,
  "sha": "a811f8ab22f7c28183a28a595b034ec8bc85b935",
  "build_id": 473581,
  "build_name": "build-apk",
  "build_stage": "build",
  "build_status": "success",
  "build_duration": 120.0,
  "pipeline_id": 53093,
  "project_id": 282,
  "project_name": "dispatcher-app",
  "user": {
    "id": 124,
    "name": "Razyl Vidal",
    "username": "razylvidal"
  },
  "commit": {
    "sha": "a811f8ab22f7c28183a28a595b034ec8bc85b935",
    "message": "Add new feature"
  },
  "project": {
    "id": 282,
    "name": "dispatcher-app",
    "web_url": "https://gitlab.com/android-team/dispatcher-app"
  }
}
            """.trimIndent()

        val SAMPLE_PAYLOAD_PENDING =
            """
{
  "object_kind": "build",
  "ref": "main",
  "tag": false,
  "sha": "a811f8ab22f7c28183a28a595b034ec8bc85b935",
  "build_id": 473580,
  "build_name": "unit-test",
  "build_stage": "test",
  "build_status": "pending",
  "pipeline_id": 53093,
  "project_id": 282,
  "project_name": "dispatcher-app",
  "user": {
    "id": 124,
    "name": "Razyl Vidal",
    "username": "razylvidal"
  },
  "commit": {
    "sha": "a811f8ab22f7c28183a28a595b034ec8bc85b935",
    "message": "Add new feature"
  },
  "project": {
    "id": 282,
    "name": "dispatcher-app",
    "web_url": "https://gitlab.com/android-team/dispatcher-app"
  }
}
            """.trimIndent()

        val SAMPLE_PAYLOAD_RUNNING_SAME_JOB =
            """
{
  "object_kind": "build",
  "ref": "main",
  "tag": false,
  "sha": "a811f8ab22f7c28183a28a595b034ec8bc85b935",
  "build_id": 473580,
  "build_name": "unit-test",
  "build_stage": "test",
  "build_status": "running",
  "build_duration": 5.0,
  "pipeline_id": 53093,
  "project_id": 282,
  "project_name": "dispatcher-app",
  "user": {
    "id": 124,
    "name": "Razyl Vidal",
    "username": "razylvidal"
  },
  "commit": {
    "sha": "a811f8ab22f7c28183a28a595b034ec8bc85b935",
    "message": "Add new feature"
  },
  "project": {
    "id": 282,
    "name": "dispatcher-app",
    "web_url": "https://gitlab.com/android-team/dispatcher-app"
  }
}
            """.trimIndent()

        // Pipeline event for pipeline 99999 - used to test both-enabled mode
        // Includes builds to simulate typical GitLab payload when Pipeline Events are enabled
        val SAMPLE_PIPELINE_PAYLOAD =
            """
{
  "object_kind": "pipeline",
  "object_attributes": {
    "id": 99999,
    "iid": 100,
    "ref": "main",
    "tag": false,
    "sha": "abc123",
    "status": "running",
    "detailed_status": "running",
    "stages": ["test", "build"],
    "created_at": "2024-06-19 02:20:18 UTC",
    "finished_at": null,
    "duration": null,
    "url": "https://gitlab.com/test/project/-/pipelines/99999"
  },
  "user": {
    "id": 1,
    "name": "Test User",
    "username": "testuser"
  },
  "project": {
    "id": 1,
    "name": "test-project",
    "web_url": "https://gitlab.com/test/project"
  },
  "commit": {
    "id": "abc123",
    "message": "Test commit"
  },
  "builds": [
    {
      "id": 888888,
      "stage": "test",
      "name": "test-job",
      "status": "running",
      "created_at": "2024-06-19 02:20:18 UTC",
      "started_at": "2024-06-19 02:20:20 UTC",
      "finished_at": null,
      "duration": 10.0,
      "queued_duration": 2.0,
      "failure_reason": null,
      "when": "on_success",
      "manual": false,
      "allow_failure": false,
      "user": {
        "id": 1,
        "name": "Test User",
        "username": "testuser"
      }
    },
    {
      "id": 888889,
      "stage": "build",
      "name": "build-app",
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
        "id": 1,
        "name": "Test User",
        "username": "testuser"
      }
    }
  ]
}
            """.trimIndent()

        // Job event for the SAME pipeline 99999 - should be skipped in both-enabled mode
        val SAMPLE_JOB_FOR_SAME_PIPELINE =
            """
{
  "object_kind": "build",
  "ref": "main",
  "tag": false,
  "sha": "abc123",
  "build_id": 888888,
  "build_name": "test-job",
  "build_stage": "test",
  "build_status": "running",
  "build_duration": 10.0,
  "pipeline_id": 99999,
  "project_id": 1,
  "project_name": "test-project",
  "user": {
    "id": 1,
    "name": "Test User",
    "username": "testuser"
  },
  "commit": {
    "sha": "abc123",
    "message": "Test commit"
  },
  "project": {
    "id": 1,
    "name": "test-project",
    "web_url": "https://gitlab.com/test/project"
  }
}
            """.trimIndent()
    }
}
