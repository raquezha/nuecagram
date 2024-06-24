package net.raquezha.nuecagram.telegram

class MockTelegramService : TelegramService {
    override suspend fun sendMessage(message: Message) {
        // Do nothing
    }
}
