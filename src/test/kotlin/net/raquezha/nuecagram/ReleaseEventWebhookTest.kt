package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.server.testing.testApplication
import org.junit.Test

class ReleaseEventWebhookTest : BaseEventTestHelper() {
    @Test
    fun testWebhookReleaseEvent() =
        testApplication {
            configureTestApplication()
            val response = postWebhook(EVENT_RELEASE, SAMPLE_PAYLOAD)
            assertThat(response).isEqualTo("Webhook received successfully")
        }

    companion object {
        val SAMPLE_PAYLOAD =
            """
{
  "id": 1744,
  "created_at": "2023-05-17 06:07:42 UTC",
  "description": "customer-app_staging_v3.0.12",
  "name": "customer-app_staging_v3.0.12",
  "released_at": "2023-05-17 06:07:42 UTC",
  "tag": "customer-app_staging_v3.0.12",
  "object_kind": "release",
  "project": {
    "id": 105,
    "name": "🤖customer-app-customer-app",
    "description": "This is awesome",
    "web_url": "https://gitlab.com/android-team/customer-app",
    "avatar_url": "https://gitlab.com/uploads/-/system/project/avatar/105/photo_2023-09-26_14-46-04_Background_Removed.png",
    "git_ssh_url": "git@gitlab.com:android-team/customer-app.git",
    "git_http_url": "https://gitlab.com/android-team/customer-app.git",
    "namespace": "customer-app",
    "visibility_level": 0,
    "path_with_namespace": "android-team/customer-app",
    "default_branch": "main",
    "ci_config_path": null,
    "homepage": "https://gitlab.com/android-team/customer-app",
    "url": "git@gitlab.com:android-team/customer-app.git",
    "ssh_url": "git@gitlab.com:android-team/customer-app.git",
    "http_url": "https://gitlab.com/android-team/customer-app.git"
  },
  "url": "https://gitlab.com/android-team/customer-app/-/releases/customer-app_staging_v3.0.12",
  "action": "create",
  "assets": {
    "count": 4,
    "links": [

    ],
    "sources": [
      {
        "format": "zip",
        "url": "https://gitlab.com/android-team/customer-app/-/archive/customer-app_staging_v3.0.12/customer-app-android-customer-app_staging_v3.0.12.zip"
      },
      {
        "format": "tar.gz",
        "url": "https://gitlab.com/android-team/customer-app/-/archive/customer-app_staging_v3.0.12/customer-app-android-customer-app_staging_v3.0.12.tar.gz"
      },
      {
        "format": "tar.bz2",
        "url": "https://gitlab.com/android-team/customer-app/-/archive/customer-app_staging_v3.0.12/customer-app-android-customer-app_staging_v3.0.12.tar.bz2"
      },
      {
        "format": "tar",
        "url": "https://gitlab.com/android-team/customer-app/-/archive/customer-app_staging_v3.0.12/customer-app-android-customer-app_staging_v3.0.12.tar"
      }
    ]
  },
  "commit": {
    "id": "c4f565ead4815e51b4dd63c7672efec866d91148",
    "message": "Fix deleted dependency\n",
    "title": "Fix deleted dependency",
    "timestamp": "2023-05-17T13:59:36+08:00",
    "url": "https://gitlab.com/android-team/customer-app/-/commit/c4f565ead4815e51b4dd63c7672efec866d91148",
    "author": {
      "name": "raquezha",
      "email": "[REDACTED]"
    }
  }
}
            """.trimIndent()
    }
}
