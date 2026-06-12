// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/housekeeping.rs
//
// Housekeeping — periodic maintenance tasks driven by housekeeping thread.
//
// Responsibilities (mirrors Rust handle_housekeeping):
//   1. Keepalive scheduling (idle >1s → send; RTT measurement scheduling)
//   2. Timeout detection (CONN_TIMEOUT 5s) + reconnection loop
//   3. Time-based window recovery (enhanced/non-classic modes)
//   4. Bitrate calculation update
//   5. SharedStats.update() on each tick
//   6. Status log every STATUS_LOG_INTERVAL_MS (30s)
//   7. IP list reload on WatchService event or `reload` command
//
// JVM DEVIATION — IP reload trigger:
//   Rust uses SIGHUP (Unix signal) to trigger IP list reload.
//   JVM has no reliable cross-platform signal handling for SIGHUP.
//   We use two mechanisms instead:
//     a) WatchService polling on the IP file's parent directory (fires on modify).
//     b) `reload` command via stdin/TCP control channel.
//   Both call the same reloadIpList() callback passed from Main.
//   Documented here and in CLI help text.
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.stats.SharedStats
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.housekeeping")

/**
 * Housekeeping constants and utilities.
 * Mirrors Rust src/sender/housekeeping.rs + src/sender/mod.rs.
 */
object Housekeeping {
    // src/sender/mod.rs :: HOUSEKEEPING_INTERVAL_MS (already in SrtlaSender.kt too)
    const val STATUS_LOG_INTERVAL_MS: Long = 30_000L
    // src/sender/housekeeping.rs :: GLOBAL_TIMEOUT_MS (also in SrtlaSender.kt as const)
    // Kept here as an alias for documentation completeness.
    const val GLOBAL_TIMEOUT_MS: Long = 10_000L

    /**
     * Perform one housekeeping tick on the sender.
     *
     * Called from SrtlaSender's housekeeping thread every HOUSEKEEPING_INTERVAL_MS.
     * Extends doHousekeeping() with:
     *   - SharedStats update
     *   - Status log every 30s
     *
     * @param sender      The SrtlaSender (exposes connections/reg under its lock).
     * @param config      DynamicConfig for current scheduling settings.
     * @param stats       SharedStats updated each tick.
     * @param lastStatusLogMs  Mutable timestamp of last status log; update in place.
     * @return            Updated lastStatusLogMs (or original if not logged).
     */
    fun tick(
        sender: SrtlaSender,
        config: DynamicConfig,
        stats: SharedStats,
        lastStatusLogMs: Long,
    ): Long {
        val now = System.currentTimeMillis()
        val snap = config.snapshot()

        // ── SharedStats update (mirrors Rust SharedStats::update in housekeeping) ──
        val connections = sender.getConnections()
        stats.update(connections, snap)

        // ── Status log every 30s ───────────────────────────────────────────────
        return if (now - lastStatusLogMs >= STATUS_LOG_INTERVAL_MS) {
            Status.logConnectionStatus(connections, sender.getLastSelectedIdx(), config)
            now
        } else {
            lastStatusLogMs
        }
    }
}
