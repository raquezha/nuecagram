package net.raquezha.nuecagram.telegram

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MockTelegramService : TelegramService {
    private val messageCounter = AtomicInteger(0)
    private val sentMessages = CopyOnWriteArrayList<Message>()
    private val memberStatuses = ConcurrentHashMap<Pair<Long, Long>, String>()

    override suspend fun sendMessage(message: Message): String {
        sentMessages += message
        return messageCounter.incrementAndGet().toString()
    }

    override suspend fun chatMemberStatus(
        chatId: Long,
        userId: Long,
    ): String? = memberStatuses[chatId to userId]

    fun setChatMemberStatus(
        chatId: Long,
        userId: Long,
        status: String,
    ) {
        memberStatuses[chatId to userId] = status
    }

    fun sentMessages(): List<Message> = sentMessages.toList()

    fun reset() {
        sentMessages.clear()
        memberStatuses.clear()
        messageCounter.set(0)
    }
}
