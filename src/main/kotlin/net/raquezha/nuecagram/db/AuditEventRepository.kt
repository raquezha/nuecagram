package net.raquezha.nuecagram.db

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.raquezha.nuecagram.db.models.ActorType
import net.raquezha.nuecagram.db.models.AuditMetadataKeys
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
        if (actorType in ActorType.REQUIRED_ACTOR_ID && actorId.isNullOrBlank()) return false

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
        fields.putString(AuditMetadataKeys.INSTALLATION_ID, installationId?.toString())
        fields.putString(AuditMetadataKeys.ACTOR_ID, actorId)
        fields.putString(AuditMetadataKeys.USERNAME, metadataPatch.actorUsername)
        fields.putString(AuditMetadataKeys.FIRST_NAME, metadataPatch.actorFirstName)
        fields.putString(AuditMetadataKeys.REPO_NAME, metadataPatch.repoName ?: installation?.repoName)
        fields.putString(AuditMetadataKeys.NICKNAME, metadataPatch.nickname ?: installation?.chatName)
        fields.putLong(AuditMetadataKeys.CHAT_ID, metadataPatch.chatId ?: installation?.telegramChatId)
        fields.putLong(AuditMetadataKeys.TOPIC_ID, metadataPatch.topicId ?: installation?.telegramTopicId)
        fields.putString(AuditMetadataKeys.OLD_REPO_NAME, metadataPatch.identityDelta?.oldRepoName)
        fields.putString(AuditMetadataKeys.NEW_REPO_NAME, metadataPatch.identityDelta?.newRepoName)
        fields.putString(AuditMetadataKeys.OLD_NICKNAME, metadataPatch.identityDelta?.oldNickname)
        fields.putString(AuditMetadataKeys.NEW_NICKNAME, metadataPatch.identityDelta?.newNickname)
        return auditJson.encodeToString(JsonObject.serializer(), JsonObject(fields))
    }

    private companion object {
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
