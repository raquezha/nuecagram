package net.raquezha.nuecagram.webhook

internal object FormatterConstants {
    const val SECONDS_PER_HOUR = 3600L
    const val SECONDS_PER_MINUTE = 60L
    const val MAX_COMMIT_TITLE_LENGTH = 50
    const val MAX_CONTEXT_TITLE_LENGTH = 60
    const val MAX_NOTE_LENGTH = 300
    const val MAX_SHORT_DESC_LENGTH = 100
    const val MAX_DESC_LENGTH = 150
    const val MAX_RELEASE_DESC_LENGTH = 200
    const val MAX_DISPLAY_COMMITS = 5
    const val MAX_DISPLAY_BUILDS = 10
    const val SHORT_SHA_LENGTH = 7
    const val NULL_HASH_LENGTH = 40
}

internal fun String.escapeHtml(): String =
    this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

internal fun String.bold() = "<b>${this.escapeHtml()}</b>"

internal fun String.italic() = "<i>${this.escapeHtml()}</i>"

internal fun String.italicBold() = this.escapeHtml().let { "<b><i>$it</i></b>" }

internal fun String.link(label: String) = "<a href=\"${this.escapeHtml()}\">${label.escapeHtml()}</a>"

internal fun String.isNullHash(): Boolean = this == "0".repeat(FormatterConstants.NULL_HASH_LENGTH)

internal fun formatDuration(seconds: Long): String {
    val hours = seconds / FormatterConstants.SECONDS_PER_HOUR
    val minutes = (seconds % FormatterConstants.SECONDS_PER_HOUR) / FormatterConstants.SECONDS_PER_MINUTE
    val secs = seconds % FormatterConstants.SECONDS_PER_MINUTE

    return when {
        hours > 0 -> "${hours}h ${minutes}m ${secs}s"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

internal fun formatMrBadge(mrIid: Long?, projectWebUrl: String, mrUrl: String? = null): String {
    if (mrIid == null) return ""
    val label = "!$mrIid"
    if (!mrUrl.isNullOrBlank()) return " (${mrUrl.link(label)})"
    val cleanUrl = projectWebUrl.trimEnd('/')
    return if (cleanUrl.isNotBlank()) {
        " (${"$cleanUrl/-/merge_requests/$mrIid".link(label)})"
    } else {
        " ($label)"
    }
}

internal fun StringBuilder.appendTruncatedDescription(
    description: String?,
    maxLength: Int,
) {
    if (!description.isNullOrBlank()) {
        val truncatedDesc = description.take(maxLength).trim()
        append("${truncatedDesc.italic()}")
        if (description.length > maxLength) append("...")
        append("\n")
    }
}

internal fun StringBuilder.appendLabels(labels: List<String>) {
    if (labels.isNotEmpty()) {
        append("\n🏷️ ${labels.joinToString(" • ")}")
    }
}
