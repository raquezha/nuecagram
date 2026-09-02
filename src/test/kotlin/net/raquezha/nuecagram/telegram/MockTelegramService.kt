package net.raquezha.nuecagram.telegram

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

data class AnsweredCallback(
    val callbackQueryId: String,
    val text: String?,
    val showAlert: Boolean,
)

@Suppress("TooManyFunctions")
class MockTelegramService : TelegramService {
    private val messageCounter = AtomicInteger(0)
    private val sentMessages = CopyOnWriteArrayList<Message>()
    private val memberStatuses = ConcurrentHashMap<Pair<Long, Long>, String>()
    private val answeredCallbacks = CopyOnWriteArrayList<AnsweredCallback>()
    private val botCommands = CopyOnWriteArrayList<BotCommand>()
    @Volatile
    private var failChatMemberLookup = false
    @Volatile
    private var failGetMe = false

    @Volatile
    private var webhookUrl: String? = null
    @Volatile
    private var webhookHeader: String? = null
    @Volatile
    private var menuButton: MenuButton? = null

    override suspend fun getMe(): TelegramUser? {
        check(!failGetMe) { "getMe failed" }
        return TelegramUser(
            id = 10001L,
            isBot = true,
            firstName = "NuecagramBot",
            username = "NuecagramBot",
        )
    }

    override suspend fun setWebhook(
        url: String,
        headerToken: String?,
    ): Boolean {
        webhookUrl = url
        webhookHeader = headerToken
        return true
    }

    override suspend fun setChatMenuButton(menuButton: MenuButton?): Boolean {
        this.menuButton = menuButton
        return true
    }

    override suspend fun setMyCommands(commands: List<BotCommand>, scope: BotCommandScope?): Boolean {
        if (scope == null) {
            botCommands.clear()
            botCommands.addAll(commands)
        }
        return true
    }

    override suspend fun deleteMyCommands(scope: BotCommandScope): Boolean {
        return true
    }

    override suspend fun sendMessage(message: Message): String {
        sentMessages += message
        return messageCounter.incrementAndGet().toString()
    }

    override suspend fun chatMemberStatus(
        chatId: Long,
        userId: Long,
    ): String? {
        check(!failChatMemberLookup) { "chat member lookup failed" }
        return memberStatuses[chatId to userId]
    }

    override suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String?,
        showAlert: Boolean,
    ): Boolean {
        answeredCallbacks += AnsweredCallback(callbackQueryId, text, showAlert)
        return true
    }

    fun failChatMemberLookups() {
        failChatMemberLookup = true
    }

    fun failGetMe() {
        failGetMe = true
    }

    fun configuredWebhookUrl(): String? = webhookUrl

    fun configuredWebhookHeader(): String? = webhookHeader

    fun configuredMenuButton(): MenuButton? = menuButton

    fun setChatMemberStatus(
        chatId: Long,
        userId: Long,
        status: String,
    ) {
        memberStatuses[chatId to userId] = status
    }

    fun sentMessages(): List<Message> = sentMessages.toList()

    fun answeredCallbacks(): List<AnsweredCallback> = answeredCallbacks.toList()

    fun botCommands(): List<BotCommand> = botCommands.toList()

    fun reset() {
        sentMessages.clear()
        memberStatuses.clear()
        answeredCallbacks.clear()
        botCommands.clear()
        failChatMemberLookup = false
        failGetMe = false
        webhookUrl = null
        webhookHeader = null
        menuButton = null
        messageCounter.set(0)
    }
}
