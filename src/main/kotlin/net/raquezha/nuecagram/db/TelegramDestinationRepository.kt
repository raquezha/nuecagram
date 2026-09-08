package net.raquezha.nuecagram.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import net.raquezha.nuecagram.db.models.KnownTelegramDestination
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

class TelegramDestinationRepository(
    private val databaseFactory: DatabaseFactory = DatabaseFactory,
) {
    private companion object {
        const val MAX_COLUMN_LENGTH = 255
    }

    private fun destinationId(chatId: Long, topicId: Long?): String = "$chatId:${topicId ?: 0}"

    suspend fun recordTelegramUpdate(updateId: Long): Boolean = databaseFactory.dbTransaction {
        TelegramUpdates.insertIgnore { it[TelegramUpdates.updateId] = updateId }.insertedCount == 1
    }

    suspend fun upsertTelegramPrivateChat(userId: Long, chatId: Long) {
        databaseFactory.dbTransaction {
            TelegramPrivateChats.upsert(TelegramPrivateChats.telegramUserId) {
                it[telegramUserId] = userId
                it[telegramChatId] = chatId
                it[startedAt] = Instant.now().databaseTime()
            }
        }
    }

    suspend fun telegramPrivateChatId(userId: Long): Long? = databaseFactory.dbTransaction {
        TelegramPrivateChats.selectAll()
            .where { TelegramPrivateChats.telegramUserId eq userId }
            .firstOrNull()?.get(TelegramPrivateChats.telegramChatId)
    }

    suspend fun upsertKnownTelegramDestination(
        chatId: Long,
        topicId: Long?,
        chatTitle: String?,
    ) {
        if (chatId >= 0) return
        val destinationId = destinationId(chatId, topicId)
        databaseFactory.dbTransaction {
            KnownTelegramDestinations.upsert(KnownTelegramDestinations.id) {
                it[KnownTelegramDestinations.id] = destinationId
                it[KnownTelegramDestinations.telegramChatId] = chatId
                it[KnownTelegramDestinations.telegramTopicId] = topicId
                it[KnownTelegramDestinations.chatTitle] =
                    chatTitle?.trim()?.takeIf(String::isNotBlank)?.take(MAX_COLUMN_LENGTH)
                it[KnownTelegramDestinations.lastSeenAt] = Instant.now().databaseTime()
            }
        }
    }

    suspend fun knownTelegramDestinations(): List<KnownTelegramDestination> = databaseFactory.dbTransaction {
        KnownTelegramDestinations.selectAll()
            .orderBy(KnownTelegramDestinations.lastSeenAt to SortOrder.DESC)
            .map {
                KnownTelegramDestination(
                    id = it[KnownTelegramDestinations.id],
                    telegramChatId = it[KnownTelegramDestinations.telegramChatId],
                    telegramTopicId = it[KnownTelegramDestinations.telegramTopicId],
                    chatTitle = it[KnownTelegramDestinations.chatTitle],
                )
            }
    }
}

private fun Instant.databaseTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
