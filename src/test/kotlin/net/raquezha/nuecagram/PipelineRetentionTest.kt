package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.mockk
import java.util.UUID
import net.raquezha.nuecagram.webhook.WebHookService
import org.junit.Test

class PipelineRetentionTest {
    @Test
    fun activityRetainsCardStatusJobOwnershipAndManualDeduplicationTogether() {
        val service = WebHookService(KotlinLogging.logger {}, mockk())
        val installation = UUID.randomUUID()
        val ttl = WebHookService.DEFAULT_STALE_ENTRY_TTL_MS
        service.markPipelineEventReceived(installation, 1)
        service.setPipelineMessageId(installation, 1, "42")
        service.setPipelineLastTerminalStatus(installation, 1, "success")
        service.tryMarkPipelineNotification(installation, 1, "manual_waiting")

        val later = System.currentTimeMillis() + ttl + 1
        service.setPipelineMessageId(installation, 1, "43", nowMs = later)
        service.cleanupStaleEntries(nowMs = later)
        assertThat(service.getPipelineMessageId(installation, 1)).isEqualTo("43")
        assertThat(service.getPipelineLastTerminalStatus(installation, 1)).isEqualTo("success")
        assertThat(service.hasPipelineEvent(installation, 1)).isTrue()
        assertThat(service.hasPipelineNotification(installation, 1, "manual_waiting")).isTrue()

        service.cleanupStaleEntries(nowMs = later + ttl + 1)
        assertThat(service.getPipelineMessageId(installation, 1)).isNull()
        assertThat(service.getPipelineLastTerminalStatus(installation, 1)).isNull()
        assertThat(service.hasPipelineEvent(installation, 1)).isFalse()
        assertThat(service.hasPipelineNotification(installation, 1, "manual_waiting")).isFalse()
    }

    @Test
    fun statusCannotCreateAnEntryWithAnInvalidMessageId() {
        val service = WebHookService(KotlinLogging.logger {}, mockk())
        val installation = UUID.randomUUID()
        service.setPipelineLastTerminalStatus(installation, 1, "success")
        assertThat(service.getPipelineMessageId(installation, 1)).isNull()
        assertThat(service.getPipelineLastTerminalStatus(installation, 1)).isNull()
    }

    @Test
    fun samePipelineIdInDifferentInstallationsIsIndependent() {
        val service = WebHookService(KotlinLogging.logger {}, mockk())
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        service.setPipelineMessageId(first, 1, "42")
        service.setPipelineLastTerminalStatus(first, 1, "success")
        service.setPipelineMessageId(second, 1, "43")
        service.setPipelineLastTerminalStatus(second, 1, "failed")
        service.clearTrackedPipeline(first, 1)
        assertThat(service.getPipelineMessageId(second, 1)).isEqualTo("43")
        assertThat(service.getPipelineLastTerminalStatus(second, 1)).isEqualTo("failed")
    }
}
