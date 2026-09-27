package net.raquezha.nuecagram.telegram

val TELEGRAM_ADMIN_STATUSES = setOf("creator", "administrator")
val TELEGRAM_MEMBER_STATUSES = TELEGRAM_ADMIN_STATUSES + "member"

const val TELEGRAM_ADMIN_ONLY_MESSAGE = "Only Telegram group administrators can use this command."

fun isTelegramAdmin(status: String?): Boolean = status in TELEGRAM_ADMIN_STATUSES

fun isTelegramMember(status: String?): Boolean = status in TELEGRAM_MEMBER_STATUSES
