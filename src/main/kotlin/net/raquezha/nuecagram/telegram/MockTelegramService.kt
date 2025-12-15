package net.raquezha.nuecagram.telegram

import java.util.concurrent.atomic.AtomicInteger

/**
 * Mock implementation of TelegramService for testing.
 * Returns incrementing message IDs for each sent message.
 * Thread-safe using AtomicInteger.
 */
class MockTelegramService : TelegramService {
    private val messageCounter = AtomicInteger(0)

    override suspend fun sendMessage(message: Message): String = messageCounter.incrementAndGet().toString()
}
