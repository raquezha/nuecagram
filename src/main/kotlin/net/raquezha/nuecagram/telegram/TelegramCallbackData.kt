package net.raquezha.nuecagram.telegram

data class TelegramCallbackPayload(
    val action: String,
    val targetId: String,
)

object TelegramCallbackData {
    private const val PREFIX_CB = "cb"
    private const val PREFIX_INST = "inst"
    private const val CALLBACK_PART_COUNT = 3

    fun format(action: String, targetId: String): String = "$PREFIX_CB:$action:$targetId"

    fun parse(data: String?): TelegramCallbackPayload? {
        if (data.isNullOrBlank()) return null
        val parts = data.split(':')
        if (parts.size != CALLBACK_PART_COUNT) return null
        val prefix = parts[0]
        if ((prefix != PREFIX_CB && prefix != PREFIX_INST) || parts[1].isBlank() || parts[2].isBlank()) {
            return null
        }
        return TelegramCallbackPayload(action = parts[1], targetId = parts[2])
    }
}
