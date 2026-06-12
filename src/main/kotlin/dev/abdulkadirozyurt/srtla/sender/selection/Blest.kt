// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/selection/blest.rs
//
// BLEST (Block Less, Enjoy Streaming) head-of-line blocking guard.
// Filters out links whose one-way-delay would cause excessive waiting
// at the receiver relative to the fastest link.
package dev.abdulkadirozyurt.srtla.sender.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection

// src/sender/selection/blest.rs
/** Maximum acceptable block time (ms) before a link is excluded. */
const val BLEST_DEFAULT_BLOCK_THRESHOLD_MS: Double = 50.0   // blest.rs:DEFAULT_BLOCK_THRESHOLD_MS

/**
 * BLEST head-of-line blocking filter.
 *
 * Mirrors Rust `struct BlestFilter` in src/sender/selection/blest.rs.
 *
 * State:
 *   - [thresholdMs] — base acceptable block time.
 *   - [penalty]     — dynamic factor that grows on blocking events and decays per tick.
 */
class BlestFilter(
    private val thresholdMs: Double = BLEST_DEFAULT_BLOCK_THRESHOLD_MS,
) {
    var penalty: Double = 0.0
        private set

    /** Decay penalty each scheduling tick. Call once per tick. */
    fun tick() {
        penalty *= 0.95
        if (penalty < 0.01) penalty = 0.0
    }

    /** Record a head-of-line blocking event (increases penalty). */
    fun recordBlocking() {
        penalty = (penalty + 1.0).coerceAtMost(10.0)
    }

    /** Effective threshold = base / (1 + penalty × 0.5). */
    fun effectiveThreshold(): Double = thresholdMs / (1.0 + penalty * 0.5)

    /**
     * Filter [conns] to indices of non-blocked links.
     *
     * OWD is estimated as rttMinMs / 2.0.
     * A link is blocked if (owd - min_owd) > effective_threshold.
     * Links with rttMinMs ≥ 200 ms are excluded from the min-OWD calculation
     * (mirrors Rust `c.rtt.rtt_min_ms < 200.0` check).
     *
     * If no valid RTT data exists, all connected links are returned.
     *
     * Mirrors Rust `BlestFilter::filter` in blest.rs.
     */
    fun filter(conns: List<SrtlaConnection>): List<Int> {
        if (conns.isEmpty()) return emptyList()

        // Find minimum OWD across connected links with valid RTT (< 200 ms)
        var minOwd = Double.MAX_VALUE
        for (c in conns) {
            if (!c.connected) continue
            val rttMin = c.getRttMinMs()
            if (rttMin < 200.0) {
                val owd = rttMin / 2.0
                if (owd < minOwd) minOwd = owd
            }
        }

        if (minOwd == Double.MAX_VALUE) {
            // No valid RTT data — return all connected indices
            return conns.indices.filter { conns[it].connected }
        }

        val threshold = effectiveThreshold()
        return conns.indices.filter { i ->
            val c = conns[i]
            if (!c.connected) return@filter false
            val owd       = c.getRttMinMs() / 2.0
            val blockTime = owd - minOwd
            blockTime <= threshold
        }
    }
}
