// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/connection/reconnection.rs
//
// Reconnection state and exponential backoff tracking.
package dev.abdulkadirozyurt.srtla.connection

// src/connection/reconnection.rs
private const val BASE_RECONNECT_DELAY_MS: Long = 5_000L
private const val MAX_BACKOFF_DELAY_MS: Long = 120_000L
private const val MAX_BACKOFF_COUNT: Int = 5

/**
 * Reconnection state and backoff tracking.
 * Mirrors Rust `struct ReconnectionState` in src/connection/reconnection.rs.
 */
class ReconnectionState {
    var lastReconnectAttemptMs: Long = 0L
    var reconnectFailureCount: Int = 0
    var connectionEstablishedMs: Long = 0L
    var startupGraceDeadlineMs: Long = 0L

    /** Calculate backoff delay based on failure count. */
    private fun backoffDelay(): Long {
        val capped = minOf(reconnectFailureCount, MAX_BACKOFF_COUNT)
        val delay = BASE_RECONNECT_DELAY_MS * (1L shl capped)
        return minOf(delay, MAX_BACKOFF_DELAY_MS)
    }

    /**
     * Whether we should attempt reconnection now.
     * Mirrors Rust `ReconnectionState::should_attempt_reconnect`.
     */
    fun shouldAttemptReconnect(): Boolean {
        val now = System.currentTimeMillis()
        if (connectionEstablishedMs == 0L) {
            if (now <= startupGraceDeadlineMs) return false
            if (lastReconnectAttemptMs == 0L) return true
            return (now - lastReconnectAttemptMs) >= 1000L
        }
        if (lastReconnectAttemptMs == 0L) return true
        val timeSinceLast = now - lastReconnectAttemptMs
        return timeSinceLast >= backoffDelay()
    }

    /** Record a reconnection attempt. */
    fun recordAttempt(label: String) {
        lastReconnectAttemptMs = System.currentTimeMillis()
        if (connectionEstablishedMs == 0L) return
        reconnectFailureCount++
    }

    /** Mark reconnection as successful — resets backoff. */
    fun markSuccess(label: String) {
        reconnectFailureCount = 0
    }

    /** Reset the startup grace period (now + STARTUP_GRACE_MS). */
    fun resetStartupGrace() {
        startupGraceDeadlineMs = System.currentTimeMillis() + STARTUP_GRACE_MS
    }
}
