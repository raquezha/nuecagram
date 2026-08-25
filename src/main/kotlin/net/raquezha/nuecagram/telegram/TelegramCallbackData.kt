package net.raquezha.nuecagram.telegram

data class TelegramCallbackPayload(
    val action: String,
    val targetId: String,
)

object TelegramCallbackData {
    private const val PREFIX = "cb"
    private val CALLBACK_PATTERN = Regex("^(?:cb|inst):([a-z0-9_-]+(?::[a-z0-9_-]+)?):([a-zA-Z0-9_=-]+)$")

    fun format(action: String, targetId: String): String = "$PREFIX:$action:$targetId"

    fun parse(data: String?): TelegramCallbackPayload? {
        if (data == null) return null
        val match = CALLBACK_PATTERN.matchEntire(data.trim()) ?: return null
        val (action, targetId) = match.destructured
        if (data.trim().startsWith("cb:") && action.contains(':')) return null
        return TelegramCallbackPayload(action = action, targetId = targetId)
    }
}
