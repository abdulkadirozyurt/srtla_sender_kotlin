// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/status.rs
//
// Connection status reporting — logs per-link RTT, window, bitrate, score.
// Triggered every STATUS_LOG_INTERVAL_MS (30s) from the housekeeping loop.
//
// Mirrors Rust `log_connection_status` in src/sender/status.rs.
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.sender.selection.SchedulingMode
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.status")

/**
 * Connection status reporting.
 * Mirrors Rust `log_connection_status` in src/sender/status.rs.
 */
object Status {

    /**
     * Log a comprehensive connection status report.
     * Called every [Housekeeping.STATUS_LOG_INTERVAL_MS] (30s) from housekeeping.
     *
     * Matches Rust output format from src/sender/status.rs::log_connection_status.
     *
     * @param connections      All uplink connections (read under stateLock).
     * @param lastSelectedIdx  Last selected connection index for context.
     * @param config           DynamicConfig for mode/quality/exploration settings.
     */
    fun logConnectionStatus(
        connections: List<SrtlaConnection>,
        lastSelectedIdx: Int?,
        config: DynamicConfig,
    ) {
        val totalConnections = connections.size
        val snap = config.snapshot()

        // Single pass over connections — mirrors Rust single-pass optimization
        var activeConnections = 0
        var totalBitrateMbps = 0.0
        var totalInFlight = 0

        for (conn in connections) {
            if (!conn.isTimedOut()) activeConnections++
            totalBitrateMbps += conn.currentBitrateMbps()
            totalInFlight += conn.inFlightPackets
        }

        val timedOutConnections = totalConnections - activeConnections

        // src/protocol/constants.rs :: PKT_LOG_SIZE used for log utilization
        val maxPossibleEntries = totalConnections * 256  // PKT_LOG_SIZE = 256
        val logUtilization = if (maxPossibleEntries > 0)
            (totalInFlight.toDouble() / maxPossibleEntries.toDouble()) * 100.0
        else 0.0

        // ── Aggregate header (mirrors Rust) ──────────────────────────────────
        log.info("Connection Status Report:")
        log.info("  Total connections: $totalConnections")
        log.info("  Total bitrate: ${"%.2f".format(totalBitrateMbps)} Mbps")
        log.info("  Active connections: $activeConnections " +
            "(${"%.1f".format(
                if (totalConnections > 0) activeConnections.toDouble() / totalConnections * 100.0 else 0.0
            )}%)")
        log.info("  Timed out connections: $timedOutConnections")

        // ── Mode and settings (mirrors Rust match snap.mode) ─────────────────
        val modeName = snap.mode.name.lowercase().replace('_', '-')
        log.info("  Mode: $modeName")
        when (snap.mode) {
            SchedulingMode.CLASSIC ->
                log.info("    (quality/exploration/rtt-delta not applicable)")
            SchedulingMode.ENHANCED ->
                log.info("    Quality: ${if (snap.qualityEnabled) "ON" else "OFF"}, " +
                    "Exploration: ${if (snap.explorationEnabled) "ON" else "OFF"}")
            SchedulingMode.RTT_THRESHOLD ->
                log.info("    Quality: ${if (snap.qualityEnabled) "ON" else "OFF"}, " +
                    "RTT delta: ${snap.rttDeltaMs}ms")
            SchedulingMode.EDPF ->
                log.info("    EDPF pipeline: BLEST + IoDS + EDPF")
        }

        // ── Packet log utilization ────────────────────────────────────────────
        log.info("  Packet log: $totalInFlight entries used " +
            "(${"%.1f".format(logUtilization)}% of capacity)")

        // ── Last selected connection ──────────────────────────────────────────
        if (lastSelectedIdx != null && lastSelectedIdx < connections.size) {
            log.info("  Last selected: ${connections[lastSelectedIdx].label}")
        } else {
            log.info("  Last selected: none")
        }

        // ── Per-connection details ────────────────────────────────────────────
        val now = System.currentTimeMillis()
        for ((i, conn) in connections.withIndex()) {
            val status = if (conn.isTimedOut()) "TIMED_OUT" else "ACTIVE"
            val score = conn.getScore()
            val scoreDesc = when (score) {
                -1   -> "DISCONNECTED"
                0    -> "AT_CAPACITY"
                else -> score.toString()
            }

            val lastRecv = if (conn.lastReceivedMs > 0L)
                "${"%.1f".format((now - conn.lastReceivedMs) / 1000.0)}s ago" else "never"
            val lastSend = if (conn.lastSentMs > 0L)
                "${"%.1f".format((now - conn.lastSentMs) / 1000.0)}s ago" else "never"

            log.info("    [$i] $status ${conn.label} - Score: $scoreDesc - " +
                "Last recv/send: $lastRecv/$lastSend - Window: ${conn.window} - " +
                "In-flight: ${conn.inFlightPackets} - " +
                "Bitrate: ${"%.2f".format(conn.currentBitrateMbps())} Mbps")

            // RTT details if available (mirrors Rust: only if estimated_rtt_ms > 0)
            val rttMs = conn.getSmoothRttMs()
            if (rttMs > 0.0) {
                val lastRttAgoSec = conn.rtt.lastRttMeasurementMs.let { t ->
                    if (t > 0L) (now - t) / 1000.0 else 0.0
                }
                log.info("        RTT: kalman=${"%.1f".format(rttMs)}ms, " +
                    "velocity=${"%.2f".format(conn.getRttVelocity())}ms/s, " +
                    "min=${"%.1f".format(conn.getRttMinMs())}ms " +
                    "(last: ${"%.1f".format(lastRttAgoSec)}s ago)")
            }
        }

        // ── Warnings (mirrors Rust) ───────────────────────────────────────────
        if (activeConnections == 0) {
            log.warning("No active connections available!")
        } else if (activeConnections < totalConnections / 2 && totalConnections >= 2) {
            log.warning("Less than half of connections are active")
        }
    }
}
