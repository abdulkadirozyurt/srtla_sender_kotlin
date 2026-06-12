// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/selection/exploration.rs
//
// Connection exploration for enhanced mode: discovers better alternatives when
// the current best link is degrading and a second-best has recovered.
// Periodic fallback exploration every 30 s for 300 ms acts as a safety net.
package dev.abdulkadirozyurt.srtla.sender.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection

// src/sender/selection/exploration.rs
private const val EXPLORE_PERIOD_MS: Long   = 30_000L  // exploration.rs: every 30 s
private const val EXPLORE_WINDOW_MS: Long   =    300L  // exploration.rs: 300 ms window

/**
 * Determine whether to explore the second-best connection now.
 *
 * Mirrors Rust `should_explore_now` in src/sender/selection/exploration.rs.
 *
 * Returns true when:
 *   - Best link has recent NAKs (< 3 s ago) AND second-best has recovered (> 5 s or none), OR
 *   - Periodic fallback: (currentTimeMs % 30_000) < 300
 *
 * @param conns        All connections (for NAK state inspection).
 * @param bestIdx      Index of current best connection (or null).
 * @param secondIdx    Index of second-best connection (or null).
 * @param currentTimeMs Wall-clock time in ms (passed in so callers can cache it).
 */
fun shouldExploreNow(
    conns: List<SrtlaConnection>,
    bestIdx: Int?,
    secondIdx: Int?,
    currentTimeMs: Long,
): Boolean {
    // Need both best and second-best to explore
    val b = bestIdx   ?: return false
    val s = secondIdx ?: return false
    if (b >= conns.size || s >= conns.size) return false

    val bestConn   = conns[b]
    val secondConn = conns[s]

    // Condition 1: current best has recent NAKs (degrading)
    val bestDegraded = bestConn.timeSinceLastNakMs()
        ?.let { it < 3_000L } ?: false

    // Condition 2: second-best has recovered (no NAK or last NAK > 5 s ago)
    val secondRecovered = secondConn.timeSinceLastNakMs()
        ?.let { it > 5_000L } ?: true   // no NAKs = recovered

    // Condition 3: periodic exploration safety net (every 30 s for 300 ms)
    val periodicExploration = (currentTimeMs % EXPLORE_PERIOD_MS) < EXPLORE_WINDOW_MS

    return (bestDegraded && secondRecovered) || periodicExploration
}
