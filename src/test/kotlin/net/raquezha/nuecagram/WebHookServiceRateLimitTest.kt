package net.raquezha.nuecagram

import com.google.common.truth.Truth.assertThat
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.mockk
import net.raquezha.nuecagram.db.InstallationRepository
import net.raquezha.nuecagram.webhook.WebHookService
import org.junit.Test

class WebHookServiceRateLimitTest {
    @Test
    fun rateLimitsRequestsWithinTheWindow() {
        val service =
            WebHookService(
                logger = KotlinLogging.logger { },
                installationRepository = mockk<InstallationRepository>(),
                maxRequestsPerWindow = 2,
                rateLimitWindowMs = 1_000,
            )

        assertThat(service.isRateLimited("client", 0)).isFalse()
        assertThat(service.isRateLimited("client", 1)).isFalse()
        assertThat(service.isRateLimited("client", 2)).isTrue()
        assertThat(service.isRateLimited("client", 1_001)).isFalse()
    }
}
