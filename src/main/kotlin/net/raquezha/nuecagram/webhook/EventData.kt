package net.raquezha.nuecagram.webhook

import java.util.UUID
import org.gitlab4j.api.webhook.Event

data class EventData(
    val installationId: UUID,
    val event: Event,
    val headerEvent: String,
    val chatDetails: ChatDetails,
) {
    fun log(): String =
        "Webhook event=$headerEvent objectKind=${event.objectKind} installationId=$installationId"
}
