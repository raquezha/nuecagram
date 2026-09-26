package net.raquezha.nuecagram.plugins

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Test

class LoginThrottleTest {
    @Test
    fun expiredBucketsAreRemovedWhenDifferentClientRequestsLogin() {
        val throttle = LoginThrottle()
        val start = Instant.parse("2025-01-01T00:00:00Z")
        throttle.recordFailure("inactive-client", start)

        val afterExpiry = start.plus(16, ChronoUnit.MINUTES)
        assertThat(throttle.isBlocked("new-client", afterExpiry)).isFalse()
        assertThat(throttle.pruneExpired(afterExpiry)).isEqualTo(0)
        assertThat(throttle.isBlocked("inactive-client", afterExpiry)).isFalse()
    }

    @Test
    fun failedLoginThresholdResetsAfterWindowExpires() {
        val throttle = LoginThrottle()
        val start = Instant.parse("2025-01-01T00:00:00Z")
        repeat(5) { throttle.recordFailure("client", start.plusSeconds(it.toLong())) }

        assertThat(throttle.isBlocked("client", start.plusSeconds(10))).isTrue()
        assertThat(throttle.isBlocked("client", start.plus(16, ChronoUnit.MINUTES))).isFalse()
    }
}
