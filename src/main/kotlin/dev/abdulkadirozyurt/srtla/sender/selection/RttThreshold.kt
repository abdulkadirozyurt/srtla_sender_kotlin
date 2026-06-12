// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/selection/rtt_threshold.rs
//
// RTT-threshold selection: groups links into "fast" (rtt ≤ min_rtt + delta)
// and "slow", strongly preferring fast links to reduce packet reordering.
// Quality scoring is applied within the fast group.
// Falls back to slow links only when no fast link has capacity.
package dev.abdulkadirozyurt.srtla.sender.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection

/** Default RTT delta (ms) above the minimum to be considered "fast". */
const val RTT_DELTA_DEFAULT_MS: Int = 30    // rtt_threshold.rs (config default)

/**
 * RTT-threshold connection selection.
 *
 * Mirrors Rust `rtt_threshold::select_connection` in
 * src/sender/selection/rtt_threshold.rs.
 *
 * @param conns         All uplink connections.
 * @param qualityCache  Map from connId → QualityCache.
 * @param lastIdx       Previously selected index.
 * @param lastSwitchMs  Timestamp of last switch.
 * @param currentTimeMs Current wall-clock time in ms.
 * @param rttDeltaMs    RTT threshold above minimum for "fast" classification.
 * @param enableQuality Whether to apply quality scoring within fast group.
 * @return Selected index, or null if no valid connection.
 */
fun rttThresholdSelect(
    conns: List<SrtlaConnection>,
    qualityCache: Map<Long, QualityCache>,
    lastIdx: Int?,
    lastSwitchMs: Long,
    currentTimeMs: Long,
    rttDeltaMs: Int,
    enableQuality: Boolean,
): Int? {
    // ── Phase 1: find minimum RTT among eligible links ──────────────────────
    var minRtt = Double.MAX_VALUE
    for (c in conns) {
        if (c.isTimedOut() || !c.connected) continue
        if (c.getScore() <= 0) continue
        val rtt = c.getSmoothRttMs()
        if (rtt > 0.0 && rtt < minRtt) minRtt = rtt
    }

    // If no valid RTT data, treat all links as fast
    val rttThreshold = if (minRtt == Double.MAX_VALUE) Double.MAX_VALUE
                       else minRtt + rttDeltaMs.toDouble()

    // ── Phase 2: best among fast links ─────────────────────────────────────
    var bestIdx: Int?     = null
    var bestScore: Double = -1.0

    for ((i, c) in conns.withIndex()) {
        if (c.isTimedOut() || !c.connected) continue
        val base = c.getScore()
        if (base <= 0) continue

        val rtt = c.getSmoothRttMs()
        // "fast" if: no RTT data (rtt <= 0) OR within threshold
        val isFast = rtt <= 0.0 || rtt <= rttThreshold

        if (isFast) {
            val score: Double = if (enableQuality) {
                val qm = qualityCache[c.connId]?.get(c, currentTimeMs)
                    ?: calculateQualityMultiplier(c, currentTimeMs)
                base.toDouble() * qm
            } else {
                base.toDouble()
            }
            if (score > bestScore) {
                bestScore = score
                bestIdx   = i
            }
        }
    }

    // ── Phase 3: fallback to any eligible link if no fast link has capacity ─
    if (bestIdx == null) {
        for ((i, c) in conns.withIndex()) {
            if (c.isTimedOut() || !c.connected) continue
            val base = c.getScore()
            if (base <= 0) continue
            val score: Double = if (enableQuality) {
                val qm = qualityCache[c.connId]?.get(c, currentTimeMs)
                    ?: calculateQualityMultiplier(c, currentTimeMs)
                base.toDouble() * qm
            } else {
                base.toDouble()
            }
            if (score > bestScore) {
                bestScore = score
                bestIdx   = i
            }
        }
    }

    // ── Phase 4: time-based dampening ──────────────────────────────────────
    val timeSinceLast = (currentTimeMs - lastSwitchMs).coerceAtLeast(0L)
    val inCooldown    = timeSinceLast < MIN_SWITCH_INTERVAL_MS

    if (lastIdx != null && bestIdx != lastIdx && inCooldown) {
        val lastValid = lastIdx < conns.size
            && !conns[lastIdx].isTimedOut()
            && conns[lastIdx].connected
        if (lastValid && conns[lastIdx].getScore() > 0) return lastIdx
    }

    return bestIdx
}
