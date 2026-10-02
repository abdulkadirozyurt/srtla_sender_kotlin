// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/connection/bitrate.rs
//
// Sans-IO leaf: every method that needs time takes it as `nowMs`, so the
// caller owns the single monotonic clock.
package dev.abdulkadirozyurt.srtla.connection

import dev.abdulkadirozyurt.srtla.core.satAdd
import dev.abdulkadirozyurt.srtla.core.satMul
import dev.abdulkadirozyurt.srtla.core.satSub

private const val BITRATE_UPDATE_INTERVAL_MS: Long = 2_000L

/** Bitrate measurement over a 2-second window. */
class BitrateTracker(nowMs: Long) {
    var bytesSentTotal: Long = 0L
    var bytesSentWindow: Long = 0L
    var lastRateUpdateMs: Long = nowMs
    /** Measured send rate in bits per second. */
    var currentBitrateBps: Double = 0.0

    /** Start a fresh measurement window at [nowMs]. */
    fun reset(nowMs: Long) {
        bytesSentTotal = 0L
        bytesSentWindow = 0L
        lastRateUpdateMs = nowMs
        currentBitrateBps = 0.0
    }

    fun updateOnSend(bytesSent: Long) {
        bytesSentTotal = bytesSentTotal.satAdd(bytesSent)
    }

    /** Recompute the rate once the 2-second window has elapsed. */
    fun calculate(nowMs: Long) {
        val timeDiffMs = nowMs.satSub(lastRateUpdateMs)
        if (timeDiffMs >= BITRATE_UPDATE_INTERVAL_MS) {
            val bytesDiff = bytesSentTotal.satSub(bytesSentWindow)
            if (timeDiffMs > 0L) {
                val bits = bytesDiff.satMul(8L)
                currentBitrateBps = (bits.toDouble() * 1000.0) / timeDiffMs.toDouble()
            }
            lastRateUpdateMs = nowMs
            bytesSentWindow = bytesSentTotal
        }
    }

    fun mbps(): Double = currentBitrateBps / 1_000_000.0
}
