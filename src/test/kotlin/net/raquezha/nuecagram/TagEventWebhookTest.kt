package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.server.testing.testApplication
import org.junit.Test

class TagEventWebhookTest : BaseEventTestHelper() {
    @Test
    fun testWebhookTagEvent() =
        testApplication {
            configureTestApplication()
            val response = postWebhook(EVENT_TAG, SAMPLE_PAYLOAD)
            assertThat(response).isEqualTo("Webhook received successfully")
        }

    companion object {
        val SAMPLE_PAYLOAD =
            """
{
  "object_kind": "tag_push",
  "event_name": "tag_push",
  "before": "b1bee95f3e7f1287988b4af86a46cc2d34175af4",
  "after": "39bd32ef881c77c4f1b4c23bbc6e70d77ff2c158",
  "ref": "refs/tags/tindahannimama_staging_v3.9.1",
  "ref_protected": false,
  "checkout_sha": "2c33b92cb23c6f824f147e67179be0eab7c6b3ed",
  "message": null,
  "user_id": 38,
  "user_name": "raquezha",
  "user_username": "raquezha",
  "user_email": "[REDACTED]",
  "user_avatar": "https://gitlab.com/uploads/-/system/user/avatar/38/avatar.png",
  "project_id": 107,
  "project": {
    "id": 107,
    "name": "tindahannimama-customer-app",
    "description": "",
    "web_url": "https://gitlab.com/android-team/tindahannimama/tindahannimama-customer-app",
    "avatar_url": "https://gitlab.com/512x512_.png",
    "git_ssh_url": "git@gitlab.com:android-team/tindahannimama/tindahannimama-customer-app.git",
    "git_http_url": "https://gitlab.com/android-team/tindahannimama/tindahannimama-customer-app.git",
    "namespace": "tindahannimama",
    "visibility_level": 0,
    "path_with_namespace": "android-team/tindahannimama/tindahannimama-customer-app",
    "default_branch": "main",
    "ci_config_path": null,
    "homepage": "https://gitlab.com/android-team/tindahannimama/tindahannimama-customer-app",
    "url": "git@gitlab.com:android-team/tindahannimama/tindahannimama-customer-app.git",
    "ssh_url": "git@gitlab.com:android-team/tindahannimama/tindahannimama-customer-app.git",
    "http_url": "https://gitlab.com/android-team/tindahannimama/tindahannimama-customer-app.git"
  },
  "commits": [
    {
      "id": "39bd32ef881c77c4f1b4c23bbc6e70d77ff2c158",
      "message": "Merge branch 'adjust-staging-url' into 'main'\n\nUse new staging server url\n\nSee merge request android-team/tindahannimama/tindahannimama-customer-app!480",
      "title": "Merge branch 'adjust-staging-url' into 'main'",
      "timestamp": "2024-06-11T03:04:03+00:00",
      "url": "https://gitlab.com/android-team/tindahannimama/tindahannimama-customer-app/-/commit/39bd32ef881c77c4f1b4c23bbc6e70d77ff2c158",
      "author": {
        "name": "Ralph Eufracio",
        "email": "[REDACTED]"
      },
      "added": [

      ],
      "modified": [
        "app-tindahannimama/build.gradle.kts"
      ],
      "removed": [

      ]
    },
    {
      "id": "efb8d8087e50253ed72e98cc684adea3d9d0ec3f",
      "message": "Use new staging server url\n",
      "title": "Use new staging server url",
      "timestamp": "2024-06-11T10:59:21+08:00",
      "url": "https://gitlab.com/android-team/tindahannimama/tindahannimama-customer-app/-/commit/efb8d8087e50253ed72e98cc684adea3d9d0ec3f",
      "author": {
        "name": "Ralph Eufracio",
        "email": "[REDACTED]"
      },
      "added": [

      ],
      "modified": [
        "app-tindahannimama/build.gradle.kts"
      ],
      "removed": [

      ]
    },
    {
      "id": "b1bee95f3e7f1287988b4af86a46cc2d34175af4",
      "message": "Merge branch 'limit-character-input' into 'main'\n\nLimit character input\n\nCloses #395\n\nSee merge request android-team/tindahannimama/tindahannimama-customer-app!479",
      "title": "Merge branch 'limit-character-input' into 'main'",
      "timestamp": "2024-06-04T08:12:05+00:00",
      "url": "https://gitlab.com/android-team/tindahannimama/tindahannimama-customer-app/-/commit/b1bee95f3e7f1287988b4af86a46cc2d34175af4",
      "author": {
        "name": "Ralph Eufracio",
        "email": "[REDACTED]"
      },
      "added": [

      ],
      "modified": [
        "app-tindahannimama/proguard-rules.pro",
        "feature-needhelp/src/main/java/com/app/tindahannimama/feature/needhelp/NeedHelpFragment.kt",
        "feature-needhelp/src/main/java/com/app/tindahannimama/feature/needhelp/NeedHelpPresenter.kt",
        "feature-needhelp/src/main/java/com/app/tindahannimama/feature/needhelp/NeedHelpViewState.kt",
        "feature-needhelp/src/main/res/values/strings.xml",
        "feature-needhelp/src/test/java/com/app/tindahannimama/feature/needhelp/NeedHelpPresenterTest.kt",
        "feature-products/shared/src/main/java/com/app/tindahannimama/feature/products/shared/suggestproduct/SuggestProductDialog.kt",
        "feature-products/shared/src/main/res/values/strings.xml",
        "tindahannimama-feature-shared/src/main/java/com/app/tindahannimama/feature/shared/Constant.kt",
        "tindahannimama-ui-components/draftaddress/src/main/java/com/app/tindahannimama/uicomponents/draftaddress/completeaddress/CompleteAddressFormFragment.kt",
        "tindahannimama-ui-components/draftaddress/src/main/java/com/app/tindahannimama/uicomponents/draftaddress/completeaddress/CompleteAddressFormPresenter.kt",
        "tindahannimama-ui-components/draftaddress/src/main/java/com/app/tindahannimama/uicomponents/draftaddress/completeaddress/CompleteAddressFormViewState.kt",
        "tindahannimama-ui-components/draftaddress/src/main/java/com/app/tindahannimama/uicomponents/draftaddress/manualaddress/ManualAddressFragment.kt",
        "tindahannimama-ui-components/draftaddress/src/main/java/com/app/tindahannimama/uicomponents/draftaddress/manualaddress/ManualAddressPresenter.kt",
        "tindahannimama-ui-components/draftaddress/src/main/java/com/app/tindahannimama/uicomponents/draftaddress/manualaddress/ManualAddressViewState.kt",
        "tindahannimama-ui-components/draftaddress/src/main/res/values/strings.xml",
        "tindahannimama-ui-components/draftaddress/src/test/java/com/app/tindahannimama/uicomponents/draftaddress/CompleteAddressFormPresenterTest.kt",
        "tindahannimama-ui-components/draftaddress/src/test/java/com/app/tindahannimama/uicomponents/draftaddress/ManualAddressPresenterTest.kt"
      ],
      "removed": [

      ]
    }
  ],
  "total_commits_count": 3,
  "push_options": {
  },
  "repository": {
    "name": "tindahannimama-customer-app",
    "url": "git@gitlab.com:android-team/tindahannimama/tindahannimama-customer-app.git",
    "description": "",
    "homepage": "https://gitlab.com/android-team/tindahannimama/tindahannimama-customer-app",
    "git_http_url": "https://gitlab.com/android-team/tindahannimama/tindahannimama-customer-app.git",
    "git_ssh_url": "git@gitlab.com:android-team/tindahannimama/tindahannimama-customer-app.git",
    "visibility_level": 0
  }
}

            """.trimIndent()
    }
}
