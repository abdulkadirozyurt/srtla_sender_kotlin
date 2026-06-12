// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/selection/enhanced.rs
//
// Enhanced connection selection: quality-aware scoring, 10 % hysteresis,
// time-based switch dampening, optional smart exploration.
package dev.abdulkadirozyurt.srtla.sender.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection

// src/sender/selection/enhanced.rs
/** New connection must be 10 % better than the current one to switch. */
const val SWITCH_THRESHOLD: Double = 1.10   // enhanced.rs:SWITCH_THRESHOLD

/**
 * Enhanced connection selection algorithm.
 *
 * Mirrors Rust `enhanced::select_connection` in src/sender/selection/enhanced.rs.
 *
 * Algorithm:
 *   1. Score each non-timed-out connection (base × quality if enabled).
 *   2. Track best, second-best, and the current connection's score.
 *   3. Time-based dampening: if within MIN_SWITCH_INTERVAL_MS of last switch
 *      AND the current connection is still valid, stay on it.
 *   4. Score hysteresis: only switch if new best is ≥ current × 1.10.
 *   5. Exploration (if enabled): try second-best when best is degrading.
 *
 * @param conns         All uplink connections.
 * @param qualityCache  Map from connId → QualityCache (updated in place).
 * @param lastIdx       Previously selected index (for hysteresis/dampening).
 * @param lastSwitchMs  Timestamp of last switch.
 * @param currentTimeMs Current wall-clock time in ms.
 * @param enableQuality Whether to apply quality scoring.
 * @param enableExplore Whether to enable smart exploration.
 * @return Selected index, or null if no valid connection.
 */
fun enhancedSelect(
    conns: List<SrtlaConnection>,
    qualityCache: Map<Long, QualityCache>,
    lastIdx: Int?,
    lastSwitchMs: Long,
    currentTimeMs: Long,
    enableQuality: Boolean,
    enableExplore: Boolean,
): Int? {
    var bestIdx: Int?    = null
    var secondIdx: Int?  = null
    var bestScore: Double   = -1.0
    var secondScore: Double = -1.0
    var currentScore: Double? = null

    for ((i, c) in conns.withIndex()) {
        if (c.isTimedOut()) continue
        val base = c.getScore().toDouble()
        val score: Double = if (!enableQuality) {
            base
        } else {
            val qm = qualityCache[c.connId]?.get(c, currentTimeMs)
                ?: calculateQualityMultiplier(c, currentTimeMs)
            base * qm
        }

        if (i == lastIdx) currentScore = score

        if (score > bestScore) {
            secondScore = bestScore;  secondIdx = bestIdx
            bestScore   = score;      bestIdx   = i
        } else if (score > secondScore) {
            secondScore = score;      secondIdx = i
        }
    }

    // ── Time-based switch dampening ─────────────────────────────────────────
    val timeSinceLastSwitch = (currentTimeMs - lastSwitchMs).coerceAtLeast(0L)
    val inCooldown = timeSinceLastSwitch < MIN_SWITCH_INTERVAL_MS

    if (lastIdx != null && bestIdx != lastIdx) {
        val lastValid = lastIdx < conns.size
            && !conns[lastIdx].isTimedOut()
            && conns[lastIdx].connected

        // Stay with current if in cooldown and still valid
        if (inCooldown && lastValid) return lastIdx

        // Score-based hysteresis (only if not in cooldown)
        if (currentScore != null && bestScore < currentScore * SWITCH_THRESHOLD) {
            return lastIdx
        }
    }

    // ── Exploration ─────────────────────────────────────────────────────────
    if (enableExplore && !inCooldown) {
        if (shouldExploreNow(conns, bestIdx, secondIdx, currentTimeMs)) {
            // Try second-best only if different from current
            if (secondIdx != null && secondIdx != lastIdx) {
                return secondIdx
            }
        }
    }

    return bestIdx
}
