package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.server.testing.testApplication
import org.junit.Test

class NoteEventWebhookTest : BaseEventTestHelper() {
    @Test
    fun testWebhookMergeEvent() =
        testApplication {
            configureTestApplication()
            val response = postWebhook(EVENT_NOTE, SAMPLE_PAYLOAD)
            assertThat(response).isEqualTo("Webhook received successfully")
        }

    companion object {
        val SAMPLE_PAYLOAD =
            """
{
  "object_kind": "note",
  "event_type": "note",
  "user": {
    "id": 38,
    "name": "raquezha",
    "username": "raquezha",
    "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
    "email": "[REDACTED]"
  },
  "project_id": 327,
  "project": {
    "id": 327,
    "name": "Kotlin Practice",
    "description": null,
    "web_url": "https://gitlab.com/android-team/interns/2024-batch2/kotlin-practice",
    "avatar_url": null,
    "git_ssh_url": "git@gitlab.com:android-team/interns/2024-batch2/kotlin-practice.git",
    "git_http_url": "https://gitlab.com/android-team/interns/2024-batch2/kotlin-practice.git",
    "namespace": "2024-batch2",
    "visibility_level": 0,
    "path_with_namespace": "android-team/interns/2024-batch2/kotlin-practice",
    "default_branch": "master",
    "ci_config_path": null,
    "homepage": "https://gitlab.com/android-team/interns/2024-batch2/kotlin-practice",
    "url": "git@gitlab.com:android-team/interns/2024-batch2/kotlin-practice.git",
    "ssh_url": "git@gitlab.com:android-team/interns/2024-batch2/kotlin-practice.git",
    "http_url": "https://gitlab.com/android-team/interns/2024-batch2/kotlin-practice.git"
  },
  "object_attributes": {
    "attachment": null,
    "author_id": 38,
    "change_position": {
      "base_sha": null,
      "start_sha": null,
      "head_sha": null,
      "old_path": null,
      "new_path": null,
      "position_type": "text",
      "old_line": null,
      "new_line": null,
      "line_range": null
    },
    "commit_id": "c2b4f233acab45629c41f0a363b879a7cb59841c",
    "created_at": "2024-07-01 08:52:18 UTC",
    "discussion_id": "61443987ed14977eb95fd5a761e5a6fcc506dc8e",
    "id": 72057,
    "line_code": "08ae6361b0035d1fdb7df6669ef0ce6273ffed2f_0_25",
    "note": "sample comment",
    "noteable_id": null,
    "noteable_type": "Commit",
    "original_position": {
      "base_sha": "edff1b0eac8f3b85f8e8ea63738d90a728470012",
      "start_sha": "edff1b0eac8f3b85f8e8ea63738d90a728470012",
      "head_sha": "c2b4f233acab45629c41f0a363b879a7cb59841c",
      "old_path": "app/src/main/java/com/example/practice_exercises/Problem-Set-Character-Frequency.kt",
      "new_path": "app/src/main/java/com/example/practice_exercises/Problem-Set-Character-Frequency.kt",
      "position_type": "text",
      "old_line": null,
      "new_line": 25,
      "line_range": null
    },
    "position": {
      "base_sha": "1231231312312312312312312312",
      "start_sha": "1231231312312312312312312312",
      "head_sha": "1232132131231231231231231",
      "old_path": "app/src/main/java/com/example/practice_exercises/Problem-Set-Character-Frequency.kt",
      "new_path": "app/src/main/java/com/example/practice_exercises/Problem-Set-Character-Frequency.kt",
      "position_type": "text",
      "old_line": null,
      "new_line": 25,
      "line_range": null
    },
    "project_id": 327,
    "resolved_at": null,
    "resolved_by_id": null,
    "resolved_by_push": null,
    "st_diff": null,
    "system": false,
    "type": "DiffNote",
    "updated_at": "2024-07-01 08:52:18 UTC",
    "updated_by_id": null,
    "description": "sample comment",
    "url": "https://gitlab.com/android-team/interns/2024-batch2/kotlin-practice/-/commit/c2b4f233acab45629c41f0a363b879a7cb59841c#note_72057",
    "action": "create"
  },
  "repository": {
    "name": "Kotlin Practice",
    "url": "git@gitlab.com:android-team/interns/2024-batch2/kotlin-practice.git",
    "description": null,
    "homepage": "https://gitlab.com/android-team/interns/2024-batch2/kotlin-practice"
  },
  "commit": {
    "id": "1231313131313131231312",
    "message": "problem set easy exercises\n",
    "title": "problem set easy exercises",
    "timestamp": "2024-07-01T10:46:10+08:00",
    "url": "https://gitlab.com/android-team/interns/2024-batch2/kotlin-practice/-/commit/12312321321231231231231",
    "author": {
      "name": "sdafdasfasfas",
      "email": "[REDACTED]"
    }
  }
}
            """.trimIndent()
    }
}
