// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/connection/bitrate.rs
//
// Bitrate measurement over a 2-second sliding window.
// Matches Android C implementation.
package dev.abdulkadirozyurt.srtla.connection

// src/connection/bitrate.rs
private const val BITRATE_UPDATE_INTERVAL_MS: Long = 2_000L

/**
 * Bitrate measurement and tracking.
 * Mirrors Rust `struct BitrateTracker` in src/connection/bitrate.rs.
 */
class BitrateTracker {
    var bytesSentTotal: Long = 0L
    var bytesSentWindow: Long = 0L
    var lastRateUpdateMs: Long = System.currentTimeMillis()
    var currentBitrateBps: Double = 0.0

    /** Reset all bitrate tracking state. */
    fun reset() {
        bytesSentTotal = 0L
        bytesSentWindow = 0L
        lastRateUpdateMs = System.currentTimeMillis()
        currentBitrateBps = 0.0
    }

    /** Update tracking when bytes are sent. */
    fun updateOnSend(bytesSent: Long) {
        bytesSentTotal = bytesSentTotal.saturatingAdd(bytesSent)
    }

    /**
     * Calculate current bitrate over a 2-second window.
     * Mirrors Rust `BitrateTracker::calculate`.
     */
    fun calculate() {
        val now = System.currentTimeMillis()
        val timeDiffMs = (now - lastRateUpdateMs).coerceAtLeast(0L)
        if (timeDiffMs >= BITRATE_UPDATE_INTERVAL_MS) {
            val bytesDiff = (bytesSentTotal - bytesSentWindow).coerceAtLeast(0L)
            if (timeDiffMs > 0L) {
                // Convert to bits per second: (bytes * 8 * 1000) / milliseconds
                val bits = bytesDiff.saturatingMul(8L)
                currentBitrateBps = (bits.toDouble() * 1000.0) / timeDiffMs.toDouble()
            }
            lastRateUpdateMs = now
            bytesSentWindow = bytesSentTotal
        }
    }

    /** Current bitrate in Mbps. */
    fun mbps(): Double = currentBitrateBps / 1_000_000.0
}

private fun Long.saturatingAdd(other: Long): Long {
    val result = this + other
    return if ((this xor other) < 0L || (this xor result) >= 0L) result else Long.MAX_VALUE
}

private fun Long.saturatingMul(other: Long): Long {
    if (other == 0L) return 0L
    val result = this * other
    return if (this == result / other) result else Long.MAX_VALUE
}
