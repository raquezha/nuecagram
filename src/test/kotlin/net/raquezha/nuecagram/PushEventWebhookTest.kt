package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.server.testing.testApplication
import org.junit.Test

class PushEventWebhookTest : BaseEventTestHelper() {
    @Test
    fun testWebhookPushEventIsReceivedButSkippedDuringProcessing() =
        testApplication {
            configureTestApplication()
            val response = postWebhook(EVENT_PUSH, SAMPLE_PAYLOAD)
            // Push events are received successfully but skipped during processing
            // (handled via PipelineEvent consolidation instead)
            assertThat(response).isEqualTo("Webhook received successfully")
        }

    companion object {
        val SAMPLE_PAYLOAD =
            """
{
  "object_kind": "push",
  "event_name": "push",
  "before": "97033a09d35c1552959c4a0c7b9d0a49b6e44a12",
  "after": "7f57a11e11f0947ef762385bc99ab8636a9567af",
  "ref": "refs/heads/nuecalytics",
  "ref_protected": false,
  "checkout_sha": "7f57a11e11f0947ef762385bc99ab8636a9567af",
  "message": null,
  "user_id": 124,
  "user_name": "Razyl Vidal",
  "user_username": "razylvidal",
  "user_email": "",
  "user_avatar": "https://gitlab.com/uploads/-/system/user/avatar/124/avatar.png",
  "project_id": 282,
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
    "ci_config_path": null,
    "homepage": "https://gitlab.com/android-team/dispatcher-app",
    "url": "git@gitlab.com:android-team/dispatcher-app.git",
    "ssh_url": "git@gitlab.com:android-team/dispatcher-app.git",
    "http_url": "https://gitlab.com/android-team/dispatcher-app.git"
  },
  "commits": [
    {
      "id": "7f57a11e11f0947ef762385bc99ab8636a9567af",
      "message": "Enable crashlytics collection\n",
      "title": "Enable crashlytics collection",
      "timestamp": "2024-05-21T16:24:28+08:00",
      "url": "https://gitlab.com/android-team/dispatcher-app/-/commit/7f57a11e11f0947ef762385bc99ab8636a9567af",
      "author": {
        "name": "Razyl Abbygail Vidal",
        "email": "[REDACTED]"
      },
      "added": [

      ],
      "modified": [
        "app-dispatcher/src/main/kotlin/com/app/tindahannimama/dispatcher/DispatcherApp.kt"
      ],
      "removed": [

      ]
    }
  ],
  "total_commits_count": 1,
  "push_options": {
  },
  "repository": {
    "name": "dispatcher-app",
    "url": "git@gitlab.com:android-team/dispatcher-app.git",
    "description": null,
    "homepage": "https://gitlab.com/android-team/dispatcher-app",
    "git_http_url": "https://gitlab.com/android-team/dispatcher-app.git",
    "git_ssh_url": "git@gitlab.com:android-team/dispatcher-app.git",
    "visibility_level": 0
  }
}
            """.trimIndent()
    }
}
