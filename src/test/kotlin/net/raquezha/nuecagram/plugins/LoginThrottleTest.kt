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
        assertThat(throttle.retryAfterSeconds("new-client", afterExpiry)).isNull()
        assertThat(throttle.pruneExpired(afterExpiry)).isEqualTo(0)
        assertThat(throttle.retryAfterSeconds("inactive-client", afterExpiry)).isNull()
    }

    @Test
    fun retryAfterReportsRemainingTimeFromOldestFailure() {
        val throttle = LoginThrottle()
        val start = Instant.parse("2025-01-01T00:00:00Z")
        repeat(5) { throttle.recordFailure("client", start.plusSeconds(it.toLong())) }

        assertThat(throttle.retryAfterSeconds("client", start.plusSeconds(10))).isEqualTo(890)
        assertThat(throttle.retryAfterSeconds("client", start.plus(14, ChronoUnit.MINUTES).plusSeconds(59)))
            .isEqualTo(1)
        assertThat(throttle.retryAfterSeconds("client", start.plus(16, ChronoUnit.MINUTES))).isNull()
    }

    @Test
    fun loginThrottleMessageRoundsUpAndUsesSafeGenericCopy() {
        assertThat(loginThrottleMessage(1)).isEqualTo(
            "Too many sign-in attempts. Please try again in 1 second.",
        )
        assertThat(loginThrottleMessage(60)).isEqualTo(
            "Too many sign-in attempts. Please try again in about 1 minute.",
        )
        assertThat(loginThrottleMessage(61)).isEqualTo(
            "Too many sign-in attempts. Please try again in about 2 minutes.",
        )
    }
}
