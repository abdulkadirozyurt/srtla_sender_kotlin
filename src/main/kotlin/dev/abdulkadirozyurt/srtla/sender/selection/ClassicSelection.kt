// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/selection/classic.rs + src/sender/selection/mod.rs
//
// Classic connection selection algorithm — matches original C implementation exactly.
//
// Selection criteria:
//   - Pure capacity-based: score = window / (in_flight + 1)
//   - No quality awareness (no NAK penalties)
//   - No RTT consideration
//   - No exploration
//   - No time-based dampening or hysteresis (matches C implementation)
//   - Simple "pick highest score" algorithm
package dev.abdulkadirozyurt.srtla.sender.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection

/**
 * Classic SRTLA connection selection.
 * Mirrors Rust `classic::select_connection` in src/sender/selection/classic.rs.
 *
 * Always picks the connection with the highest score = window / (in_flight + 1).
 * No dampening, no hysteresis — matches the original C implementation exactly.
 */
object ClassicSelection : SelectionStrategy {
    /**
     * Select the connection with the highest capacity score.
     * Returns null if all connections are timed out or have score -1 (disconnected).
     * lastIdx / lastSwitchMs / currentTimeMs are ignored — classic has no dampening.
     */
    override fun select(
        conns: List<SrtlaConnection>,
        lastIdx: Int?,
        lastSwitchMs: Long,
        currentTimeMs: Long,
    ): Int? {
        var bestIdx: Int? = null
        var bestScore: Int = -1
        for ((i, c) in conns.withIndex()) {
            if (c.isTimedOut()) continue
            val score = c.getScore()
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return bestIdx
    }
}

/**
 * Top-level stateless dispatch helper — used only by tests that don't yet hold
 * a SelectionOrchestrator.  SrtlaSender uses SelectionOrchestrator.select()
 * directly.
 *
 * Mirrors Rust `select_connection_idx` in src/sender/selection/mod.rs.
 *
 * @param classicMode  If true → ClassicSelection; otherwise Enhanced (default config).
 */
fun selectConnectionIdx(
    conns: List<SrtlaConnection>,
    lastIdx: Int?,
    lastSwitchMs: Long,
    currentTimeMs: Long,
    classicMode: Boolean,
): Int? {
    val config = if (classicMode) {
        ConfigSnapshot(mode = SchedulingMode.CLASSIC)
    } else {
        ConfigSnapshot(mode = SchedulingMode.ENHANCED)
    }
    // Create a short-lived orchestrator for test dispatch.
    // Production code uses the stateful orchestrator in SrtlaSender.
    return SelectionOrchestrator().select(conns, lastIdx, lastSwitchMs, currentTimeMs, config)
}
