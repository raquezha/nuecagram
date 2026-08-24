package net.raquezha.nuecagram.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

object Installations : Table("installations") {
    val id = javaUUID("id")
    val gitlabBaseUrl = text("gitlab_base_url")
    val gitlabProjectId = long("gitlab_project_id").nullable()
    val telegramChatId = long("telegram_chat_id")
    val telegramTopicId = long("telegram_topic_id").nullable()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()
    val updatedAt = timestampWithTimeZone("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object WebhookSecrets : Table("webhook_secrets") {
    val id = javaUUID("id")
    val installationId = javaUUID("installation_id")
    val secretDigest = binary("secret_digest")
    val secretHash = text("secret_hash").nullable()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val confirmedAt = timestampWithTimeZone("confirmed_at").nullable()
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object ManagementLinks : Table("management_links") {
    val id = javaUUID("id")
    val installationId = javaUUID("installation_id")
    val tokenDigest = binary("token_digest")
    val tokenHash = text("token_hash").nullable()
    val expiresAt = timestampWithTimeZone("expires_at")
    val consumedAt = timestampWithTimeZone("consumed_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object AuditEvents : Table("audit_events") {
    val id = javaUUID("id")
    val installationId = javaUUID("installation_id").nullable()
    val actorType = text("actor_type")
    val actorId = text("actor_id").nullable()
    val action = text("action")
    val metadata = jsonb<String>("metadata", { it }, { it })
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object EventSummaries : Table("event_summaries") {
    val id = javaUUID("id")
    val installationId = javaUUID("installation_id")
    val externalEventId = text("external_event_id")
    val eventType = text("event_type")
    val status = text("status").nullable()
    val pipelineId = long("pipeline_id").nullable()
    val jobId = long("job_id").nullable()
    val eventUrl = text("event_url").nullable()
    val receivedAt = timestampWithTimeZone("received_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object MuteStates : Table("mute_states") {
    val installationId = javaUUID("installation_id")
    val muted = bool("muted")
    val updatedAt = timestampWithTimeZone("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(installationId)
}

object TelegramUpdates : Table("telegram_updates") {
    val updateId = long("update_id")
    val receivedAt = timestampWithTimeZone("received_at").databaseGenerated()

    override val primaryKey = PrimaryKey(updateId)
}

object TelegramPrivateChats : Table("telegram_private_chats") {
    val telegramUserId = long("telegram_user_id")
    val telegramChatId = long("telegram_chat_id")
    val startedAt = timestampWithTimeZone("started_at").databaseGenerated()

    override val primaryKey = PrimaryKey(telegramUserId)
}

object InstallationAdmins : Table("installation_admins") {
    val installationId = javaUUID("installation_id")
    val telegramUserId = long("telegram_user_id")
    val confirmedAt = timestampWithTimeZone("confirmed_at")

    override val primaryKey = PrimaryKey(installationId, telegramUserId)
}

object ManagementSessions : Table("management_sessions") {
    val id = javaUUID("id")
    val installationId = javaUUID("installation_id")
    val tokenDigest = binary("token_digest")
    val tokenHash = text("token_hash")
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()
    val csrfDigest = binary("csrf_digest").nullable()
    val csrfHash = text("csrf_hash").nullable()

    override val primaryKey = PrimaryKey(id)
}

object PlatformAdminSessions : Table("platform_admin_sessions") {
    val id = javaUUID("id")
    val tokenDigest = binary("token_digest")
    val tokenHash = text("token_hash")
    val csrfDigest = binary("csrf_digest")
    val csrfHash = text("csrf_hash")
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object MrParticipantCaches : Table("mr_participant_caches") {
    val installationId = javaUUID("installation_id")
    val projectId = long("project_id")
    val mrIid = long("mr_iid")
    val authorUsername = varchar("author_username", 255).nullable()
    val reviewerUsernames = text("reviewer_usernames")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(installationId, projectId, mrIid)
}

object TelegramLaunchNonces : Table("telegram_launch_nonces") {
    val id = javaUUID("id")
    val nonceDigest = binary("nonce_digest")
    val telegramChatId = long("telegram_chat_id")
    val telegramTopicId = long("telegram_topic_id").nullable()
    val telegramUserId = long("telegram_user_id")
    val expiresAt = timestampWithTimeZone("expires_at")
    val consumedAt = timestampWithTimeZone("consumed_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

object WebAppSessions : Table("webapp_sessions") {
    val id = javaUUID("id")
    val telegramUserId = long("telegram_user_id")
    val telegramChatId = long("telegram_chat_id").nullable()
    val telegramTopicId = long("telegram_topic_id").nullable()
    val tokenDigest = binary("token_digest")
    val tokenHash = text("token_hash")
    val csrfDigest = binary("csrf_digest")
    val csrfHash = text("csrf_hash")
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}

