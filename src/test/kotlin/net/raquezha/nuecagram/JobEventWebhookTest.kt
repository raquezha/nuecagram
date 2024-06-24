package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.server.testing.testApplication
import org.junit.Test

class JobEventWebhookTest : BaseEventTestHelper() {
    @Test
    fun testWebhookJobEvent() =
        testApplication {
            configureTestApplication()
            val response = postWebhook(EVENT_JOB, SAMPLE_PAYLOAD)
            assertThat(response).isEqualTo("Webhook received successfully")
        }

    companion object {
        val SAMPLE_PAYLOAD =
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
    }
}
