package net.raquezha.nuecagram.webhook

object NuecagramHeaders {
    const val GITLAB_EVENT = "X-Gitlab-Event"
    const val SECRET_TOKEN = "X-Nuecagram-Token"
    const val CHAT_ID = "X-Nuecagram-Chat-Id"
    const val TOPIC_ID = "X-Nuecagram-Topic-Id"
}
