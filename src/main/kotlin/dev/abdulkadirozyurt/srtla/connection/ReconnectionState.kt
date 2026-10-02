// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/connection/reconnection.rs
package dev.abdulkadirozyurt.srtla.connection

import dev.abdulkadirozyurt.srtla.core.satSub
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.reconnection")

/**
 * Attempts an established link makes at the housekeeping cadence before it slows
 * down. SRT drops the session after 5 s of silence, so a sub-second blip must be
 * retried on the next tick.
 */
internal const val FAST_RETRY_ATTEMPTS: Int = 4
private const val FAST_RETRY_DELAY_MS: Long = 1000L

/**
 * Cadence once the fast attempts are spent. Each retry rebuilds the socket,
 * which drops any REG2 answer still in flight, so a slow modem gets 5 s per try.
 * No exponential backoff: a link back after minutes must not wait minutes more.
 */
internal const val SLOW_RETRY_DELAY_MS: Long = 5000L

/**
 * Attempts are stamped with the time their tick was serviced; half a tick of
 * slack keeps a retry on the tick it is due despite service jitter.
 */
private const val TICK_SLACK_MS: Long = 500L

private fun elapsedReaches(now: Long, since: Long, delayMs: Long): Boolean =
    now.satSub(since) + TICK_SLACK_MS >= delayMs

/** Reconnection state and retry pacing. */
class ReconnectionState(
    var lastReconnectAttemptMs: Long = 0L,
    /**
     * Attempts since the link last completed registration (REG3). A successful
     * socket rebuild does not reset it: only the receiver's answer proves the
     * link is back.
     */
    var reconnectFailureCount: Int = 0,
    var connectionEstablishedMs: Long = 0L,
    var startupGraceDeadlineMs: Long = 0L,
) {
    private fun retryDelay(): Long =
        if (reconnectFailureCount < FAST_RETRY_ATTEMPTS) FAST_RETRY_DELAY_MS else SLOW_RETRY_DELAY_MS

    fun shouldAttemptReconnect(now: Long): Boolean {
        if (connectionEstablishedMs == 0L) {
            if (now <= startupGraceDeadlineMs) return false
            // Initial registration retries once per housekeeping pass, like C.
            if (lastReconnectAttemptMs == 0L) return true
            return elapsedReaches(now, lastReconnectAttemptMs, FAST_RETRY_DELAY_MS)
        }
        if (lastReconnectAttemptMs == 0L) return true
        return elapsedReaches(now, lastReconnectAttemptMs, retryDelay())
    }

    fun recordAttempt(label: String, now: Long) {
        lastReconnectAttemptMs = now
        if (connectionEstablishedMs == 0L) {
            log.fine { "$label: Initial registration retry scheduled (next attempt in ~1s)" }
            return
        }
        if (reconnectFailureCount < Int.MAX_VALUE) reconnectFailureCount++
        log.info("$label: Reconnect attempt #$reconnectFailureCount, next attempt in ${retryDelay() / 1000}s")
    }

    fun markSuccess(label: String) {
        if (reconnectFailureCount > 0) {
            log.info("$label: Reconnection successful, resetting retry pacing")
            reconnectFailureCount = 0
        }
    }

    fun resetStartupGrace(now: Long) {
        startupGraceDeadlineMs = now + STARTUP_GRACE_MS
    }
}
