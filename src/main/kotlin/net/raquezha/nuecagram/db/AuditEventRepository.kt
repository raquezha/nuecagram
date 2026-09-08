package net.raquezha.nuecagram.db

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.raquezha.nuecagram.db.models.AuditMetadataPatch
import net.raquezha.nuecagram.db.models.InstallationAdminContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class AuditEventRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
) {
    suspend fun writeAuditEvent(
        installationId: UUID?,
        actorType: String,
        actorId: String?,
        action: String,
        metadataJson: String = "{}",
        metadataPatch: AuditMetadataPatch = AuditMetadataPatch(),
    ): Boolean {
        if (actorType in ACTOR_ID_REQUIRED_TYPES && actorId.isNullOrBlank()) return false

        databaseFactory.dbTransaction {
            val installationSnapshot = installationId?.let { id ->
                Installations.join(
                    MuteStates,
                    JoinType.LEFT,
                    additionalConstraint = { Installations.id eq MuteStates.installationId },
                ).selectAll().where {
                    (Installations.id eq id) and Installations.deletedAt.isNull()
                }.firstOrNull()?.let { row ->
                    InstallationAdminContext(
                        id = row[Installations.id],
                        repoName = row[Installations.repoName],
                        chatName = row[Installations.chatName],
                        gitlabBaseUrl = row[Installations.gitlabBaseUrl],
                        gitlabProjectId = row[Installations.gitlabProjectId],
                        telegramChatId = row[Installations.telegramChatId],
                        telegramTopicId = row[Installations.telegramTopicId],
                        muted = row.getOrNull(MuteStates.muted) ?: false,
                    )
                }
            }
            AuditEvents.insert {
                it[id] = UUID.randomUUID()
                it[AuditEvents.installationId] = installationId
                it[AuditEvents.actorType] = actorType
                it[AuditEvents.actorId] = actorId
                it[AuditEvents.action] = action
                it[metadata] = buildAuditMetadataJson(
                    existingMetadataJson = metadataJson,
                    installationId = installationId,
                    installation = installationSnapshot,
                    actorId = actorId,
                    metadataPatch = metadataPatch,
                )
            }
        }
        return true
    }

    private fun buildAuditMetadataJson(
        existingMetadataJson: String,
        installationId: UUID?,
        installation: InstallationAdminContext?,
        actorId: String?,
        metadataPatch: AuditMetadataPatch,
    ): String {
        val existing =
            runCatching { auditJson.parseToJsonElement(existingMetadataJson).jsonObject }
                .getOrDefault(JsonObject(emptyMap()))
        val fields = existing.toMutableMap()
        fields.putString("installation_id", installationId?.toString())
        fields.putString("actor_id", actorId)
        fields.putString("username", metadataPatch.actorUsername)
        fields.putString("first_name", metadataPatch.actorFirstName)
        fields.putString("repo_name", metadataPatch.repoName ?: installation?.repoName)
        fields.putString("nickname", metadataPatch.nickname ?: installation?.chatName)
        fields.putLong("chat_id", metadataPatch.chatId ?: installation?.telegramChatId)
        fields.putLong("topic_id", metadataPatch.topicId ?: installation?.telegramTopicId)
        fields.putString("old_repo_name", metadataPatch.identityDelta?.oldRepoName)
        fields.putString("new_repo_name", metadataPatch.identityDelta?.newRepoName)
        fields.putString("old_nickname", metadataPatch.identityDelta?.oldNickname)
        fields.putString("new_nickname", metadataPatch.identityDelta?.newNickname)
        return auditJson.encodeToString(JsonObject.serializer(), JsonObject(fields))
    }

    private companion object {
        val ACTOR_ID_REQUIRED_TYPES = setOf("telegram", "webapp_session")
        val auditJson = Json
    }
}

private fun MutableMap<String, JsonElement>.putString(key: String, value: String?) {
    value?.takeIf(String::isNotBlank)?.let { put(key, JsonPrimitive(it)) }
}

private fun MutableMap<String, JsonElement>.putLong(key: String, value: Long?) {
    if (value != null) {
        put(key, JsonPrimitive(value))
    }
}
