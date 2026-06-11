// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/housekeeping.rs
//
// TODO (Faz D): This file is an intentional skeleton.
// Full logic lives in SrtlaSender.doHousekeeping() for Faz B.
// Faz D will extract IP-reload, config-driven mode switching, and
// SharedStats updates into this standalone object.
//
// Remaining TODOs:
//   - WatchService-based IP file reload (SIGHUP equivalent)
//   - SharedStats.update() call on each housekeeping tick
//   - Status log every STATUS_LOG_INTERVAL_MS (30s)
//   - Extract doHousekeeping() from SrtlaSender into this object
package dev.abdulkadirozyurt.srtla.sender

/**
 * Housekeeping skeleton — Faz D will complete this.
 * Constants mirror Rust src/sender/housekeeping.rs and src/sender/mod.rs.
 */
object Housekeeping {
    // src/sender/mod.rs
    const val STATUS_LOG_INTERVAL_MS: Long = 30_000L
    // src/sender/housekeeping.rs
    // GLOBAL_TIMEOUT_MS is in SrtlaSender.kt (const val GLOBAL_TIMEOUT_MS)

    // TODO(Faz D): fun handleHousekeeping(sender: SrtlaSender, config: DynamicConfig, stats: SharedStats)
    // TODO(Faz D): fun logConnectionStatus(connections, lastSelectedIdx, config)
}
