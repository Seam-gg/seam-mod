package gg.seam.mod.auth

/**
 * The RFC 8628 poll state machine, kept free of Minecraft and HTTP so it can be reasoned about (and
 * tested) on its own. [DeviceCodeAuth] owns the timer and the network; this owns "what next".
 *
 * The server answers a not-yet-approved poll with HTTP 400 and an `error` code — those are normal
 * flow control, not failures.
 */
object PollPolicy {

    /** RFC 8628 §3.5: on `slow_down` the client raises its interval by at least 5 seconds. */
    const val SLOW_DOWN_INCREMENT_SECONDS = 5

    /** Ceiling on the interval so a misbehaving server can't stretch polls out indefinitely. */
    const val MAX_INTERVAL_SECONDS = 60

    /** Floor, in case the server advertises 0 and we'd otherwise busy-poll. */
    const val MIN_INTERVAL_SECONDS = 1

    /** Consecutive network errors tolerated before giving up — a flaky connection shouldn't kill a link. */
    const val MAX_CONSECUTIVE_NETWORK_ERRORS = 3

    sealed interface Decision {
        /** Keep polling, after waiting [intervalSeconds]. */
        data class Retry(val intervalSeconds: Int) : Decision

        /** Give up and show [reason] to the player. */
        data class Stop(val reason: String) : Decision
    }

    /** Clamp a server-advertised interval into something sane. */
    fun sanitizeInterval(seconds: Int): Int =
        seconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)

    /**
     * What to do about an error [code] returned by the poll endpoint, given the interval currently
     * in use. Unknown codes stop rather than spin — an unrecognised error is not a pending state.
     */
    fun decide(code: String, currentIntervalSeconds: Int): Decision = when (code) {
        "authorization_pending" -> Decision.Retry(sanitizeInterval(currentIntervalSeconds))
        "slow_down" -> Decision.Retry(sanitizeInterval(currentIntervalSeconds + SLOW_DOWN_INCREMENT_SECONDS))
        "access_denied" -> Decision.Stop("Link request was denied.")
        "expired_token" -> Decision.Stop("The code expired. Start linking again.")
        "invalid_request" -> Decision.Stop("Seam rejected the link request.")
        else -> Decision.Stop("Linking failed ($code).")
    }

    /**
     * What to do about a transport failure (server unreachable, timeout). Retries at the current
     * interval until [MAX_CONSECUTIVE_NETWORK_ERRORS] in a row, then stops.
     */
    fun decideNetworkError(consecutiveErrors: Int, currentIntervalSeconds: Int): Decision =
        if (consecutiveErrors >= MAX_CONSECUTIVE_NETWORK_ERRORS) {
            Decision.Stop("Could not reach Seam. Check your connection and try again.")
        } else {
            Decision.Retry(sanitizeInterval(currentIntervalSeconds))
        }
}
