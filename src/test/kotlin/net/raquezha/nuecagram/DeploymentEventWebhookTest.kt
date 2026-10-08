package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.ktor.server.testing.testApplication
import org.junit.Test

class DeploymentEventWebhookTest : BaseEventTestHelper() {
    @Test
    fun testWebhookDeploymentEvent() =
        testApplication {
            configureTestApplication()
            val response = postWebhook(EVENT_DEPLOYMENT, SAMPLE_PAYLOAD)
            assertThat(response).isEqualTo("Webhook received successfully")
        }

    @Test
    fun testMainPushAfterMergeOpensDedicatedDeploymentCard() =
        testApplication {
            configureTestApplication()
            val mockTelegramService = telegramService as net.raquezha.nuecagram.telegram.MockTelegramService
            mockTelegramService.reset()

            val featurePush = PushEventWebhookTest.SAMPLE_PAYLOAD
                .replace("282", "101")
                .replace("refs/heads/nuecalytics", "refs/heads/feature")
            postWebhook(EVENT_PUSH, featurePush)

            val mergePayload = """
{
  "object_kind": "merge_request",
  "event_type": "merge_request",
  "user": { "id": 1, "name": "Alice Author", "username": "alice" },
  "project": { "id": 101, "name": "Gitlab Test", "web_url": "http://example.com/gitlabhq/gitlab-test" },
  "object_attributes": {
    "id": 99,
    "iid": 42,
    "action": "merge",
    "source_branch": "feature",
    "target_branch": "main",
    "source_project_id": 101,
    "target_project_id": 101,
    "title": "Feature branch",
    "url": "http://example.com/gitlabhq/gitlab-test/-/merge_requests/42",
    "merge_params": { "squash_commit_message": "Feature branch" }
  },
  "reviewers": [{ "id": 2, "name": "bob", "username": "bob" }]
}
            """.trimIndent()
            postWebhook(EVENT_MERGE, mergePayload)

            val mainPush = PushEventWebhookTest.SAMPLE_PAYLOAD
                .replace("282", "101")
                .replace("refs/heads/nuecalytics", "refs/heads/main")
            postWebhook(EVENT_PUSH, mainPush)

            val messages = kotlinx.coroutines.runBlocking {
                repeat(100) {
                    val sent = mockTelegramService.sentMessages()
                    if (sent.size >= 3) return@runBlocking sent
                    kotlinx.coroutines.delay(50)
                }
                mockTelegramService.sentMessages()
            }
            assertThat(messages).hasSize(3)
            assertThat(messages[1].text).contains("Merged (Squashed")
            assertThat(messages[2].messageId).isNull()
            assertThat(messages[2].text).contains("main")
        }

    companion object {
        val SAMPLE_PAYLOAD =
            """
{
  "object_kind": "deployment",
  "status": "success",
  "status_changed_at":"2021-04-28 21:50:00 +0200",
  "deployment_id": 15,
  "deployable_id": 796,
  "deployable_url": "http://10.126.0.2:3000/root/test-deployment-webhooks/-/jobs/796",
  "environment": "staging",
  "environment_tier": "staging",
  "environment_slug": "staging",
  "environment_external_url": "https://staging.example.com",
  "project": {
    "id": 30,
    "name": "test-deployment-webhooks",
    "description": "This is awesome",
    "web_url": "http://10.126.0.2:3000/root/test-deployment-webhooks",
    "avatar_url": null,
    "git_ssh_url": "ssh://vlad@10.126.0.2:2222/root/test-deployment-webhooks.git",
    "git_http_url": "http://10.126.0.2:3000/root/test-deployment-webhooks.git",
    "namespace": "Administrator",
    "visibility_level": 0,
    "path_with_namespace": "root/test-deployment-webhooks",
    "default_branch": "master",
    "ci_config_path": "",
    "homepage": "http://10.126.0.2:3000/root/test-deployment-webhooks",
    "url": "ssh://vlad@10.126.0.2:2222/root/test-deployment-webhooks.git",
    "ssh_url": "ssh://vlad@10.126.0.2:2222/root/test-deployment-webhooks.git",
    "http_url": "http://10.126.0.2:3000/root/test-deployment-webhooks.git"
  },
  "short_sha": "279484c0",
  "user": {
    "id": 1,
    "name": "Administrator",
    "username": "root",
    "avatar_url": "https://www.gravatar.com/avatar/e64c7d89f26bd1972efa854d13d7dd61?s=80&d=identicon",
    "email": "admin@example.com"
  },
  "user_url": "http://10.126.0.2:3000/root",
  "commit_url": "http://10.126.0.2:3000/root/test-deployment-webhooks/-/commit/279484c09fbe69ededfced8c1bb6e6d24616b468",
  "commit_title": "Add new file"
}
            """.trimIndent()
    }
}
