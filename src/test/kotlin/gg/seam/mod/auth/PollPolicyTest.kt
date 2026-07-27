package gg.seam.mod.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PollPolicyTest {

    @Test
    fun `authorization_pending keeps polling at the same interval`() {
        val decision = PollPolicy.decide("authorization_pending", currentIntervalSeconds = 5)
        assertEquals(PollPolicy.Decision.Retry(5), decision)
    }

    @Test
    fun `slow_down raises the interval by the RFC increment`() {
        val decision = PollPolicy.decide("slow_down", currentIntervalSeconds = 5)
        assertEquals(PollPolicy.Decision.Retry(5 + PollPolicy.SLOW_DOWN_INCREMENT_SECONDS), decision)
    }

    @Test
    fun `repeated slow_down cannot push the interval past the ceiling`() {
        var interval = 5
        repeat(50) {
            interval = (PollPolicy.decide("slow_down", interval) as PollPolicy.Decision.Retry).intervalSeconds
        }
        assertEquals(PollPolicy.MAX_INTERVAL_SECONDS, interval)
    }

    @Test
    fun `terminal error codes stop the flow`() {
        for (code in listOf("access_denied", "expired_token", "invalid_request")) {
            assertIs<PollPolicy.Decision.Stop>(PollPolicy.decide(code, 5), "expected $code to stop polling")
        }
    }

    @Test
    fun `an unrecognised error code stops rather than spinning forever`() {
        val decision = PollPolicy.decide("teapot", 5)
        assertIs<PollPolicy.Decision.Stop>(decision)
        assertEquals("Linking failed (teapot).", decision.reason)
    }

    @Test
    fun `a zero interval is floored so the client cannot busy-poll`() {
        assertEquals(PollPolicy.MIN_INTERVAL_SECONDS, PollPolicy.sanitizeInterval(0))
        assertEquals(PollPolicy.MIN_INTERVAL_SECONDS, PollPolicy.sanitizeInterval(-30))
    }

    @Test
    fun `transient network errors retry until the limit, then stop`() {
        assertIs<PollPolicy.Decision.Retry>(PollPolicy.decideNetworkError(consecutiveErrors = 1, currentIntervalSeconds = 5))
        assertIs<PollPolicy.Decision.Retry>(
            PollPolicy.decideNetworkError(PollPolicy.MAX_CONSECUTIVE_NETWORK_ERRORS - 1, 5),
        )
        assertIs<PollPolicy.Decision.Stop>(
            PollPolicy.decideNetworkError(PollPolicy.MAX_CONSECUTIVE_NETWORK_ERRORS, 5),
        )
    }
}
