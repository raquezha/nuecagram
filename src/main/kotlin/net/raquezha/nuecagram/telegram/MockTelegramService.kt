package net.raquezha.nuecagram.telegram

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MockTelegramService : TelegramService {
    private val messageCounter = AtomicInteger(0)
    private val sentMessages = CopyOnWriteArrayList<Message>()

    override suspend fun sendMessage(message: Message): String {
        sentMessages += message
        return messageCounter.incrementAndGet().toString()
    }

    fun sentMessages(): List<Message> = sentMessages.toList()

    fun reset() {
        sentMessages.clear()
        messageCounter.set(0)
    }
}
