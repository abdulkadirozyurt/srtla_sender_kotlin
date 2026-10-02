// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/selection/quality.rs
//
// Quality multiplier from NAK history, RTT and connection age. Cached per
// connection and recalculated every 50 ms (see SrtlaConnection.getCachedQualityMultiplier).
package dev.abdulkadirozyurt.srtla.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.satSub

/** Grace period after establishment: early NAKs must not degrade a link permanently. */
internal const val STARTUP_GRACE_PERIOD_MS: Long = 30_000L
internal const val PERFECT_CONNECTION_BONUS: Double = 1.1
internal const val STARTUP_NAK_PENALTY: Double = 0.98
internal const val HALF_LIFE_MS: Double = 2000.0
internal const val MAX_PENALTY: Double = 0.5
internal const val NAK_BURST_THRESHOLD: Int = 5
internal const val NAK_BURST_MAX_AGE_MS: Long = 3000L
internal const val NAK_BURST_PENALTY: Double = 0.7
internal const val RTT_BONUS_THRESHOLD_MS: Double = 200.0
internal const val MIN_RTT_MS: Double = 50.0
internal const val MAX_RTT_BONUS: Double = 1.03

/**
 * Multiplier on the base score:
 * - 1.1 for links that never had a NAK,
 * - 0.5..1.0 after a NAK, recovering with exponential decay (half-life 2 s),
 * - x0.7 extra for a recent burst of 5+ NAKs,
 * - x1.0..1.03 RTT bonus for low-latency links.
 */
fun calculateQualityMultiplier(conn: SrtlaConnection, currentTimeMs: Long): Double {
    val connectionAgeMs = currentTimeMs.satSub(conn.connectionEstablishedMs())
    if (connectionAgeMs < STARTUP_GRACE_PERIOD_MS) {
        return if (conn.totalNakCount() == 0) PERFECT_CONNECTION_BONUS else STARTUP_NAK_PENALTY
    }

    val nakAgeMs = conn.timeSinceLastNakMs(currentTimeMs)
    val qualityMult = when {
        nakAgeMs != null -> {
            val decayFactor = Math.exp(-(nakAgeMs.toDouble()) / HALF_LIFE_MS)
            var mult = 1.0 - MAX_PENALTY * decayFactor
            if (conn.nakBurstCount() >= NAK_BURST_THRESHOLD && nakAgeMs < NAK_BURST_MAX_AGE_MS) {
                mult *= NAK_BURST_PENALTY
            }
            mult
        }
        conn.totalNakCount() == 0 -> PERFECT_CONNECTION_BONUS
        else -> 1.0
    }
    return qualityMult * calculateRttBonus(conn)
}

/** 1.0 (slow or unmeasured) up to MAX_RTT_BONUS (fast). Never a penalty. */
internal fun calculateRttBonus(conn: SrtlaConnection): Double {
    val smoothRtt = conn.getSmoothRttMs()
    if (smoothRtt <= 0.0) return 1.0
    val rttFactor = minOf(RTT_BONUS_THRESHOLD_MS / maxOf(smoothRtt, MIN_RTT_MS), MAX_RTT_BONUS)
    return maxOf(rttFactor, 1.0)
}
