// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/status.rs
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.protocol.PKT_LOG_SIZE
import java.util.logging.Level
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.status")

/** Periodic status report (every 30 s). Skipped entirely when INFO is off. */
fun logConnectionStatus(connections: List<SrtlaConnection>, lastSelectedIdx: Int?, snap: ConfigSnapshot, now: Long) {
    if (!log.isLoggable(Level.INFO)) return
    val total = connections.size
    var active = 0
    var totalMbps = 0.0
    var totalInFlight = 0L
    for (c in connections) {
        if (!c.isTimedOut(now)) active++
        totalMbps += c.currentBitrateMbps()
        totalInFlight += c.inFlightPackets
    }
    val maxEntries = total.toLong() * PKT_LOG_SIZE
    val utilization = if (maxEntries > 0) totalInFlight.toDouble() / maxEntries * 100.0 else 0.0

    log.info("Connection Status Report:")
    log.info("  Total connections: $total")
    log.info("  Total bitrate: ${"%.2f".format(totalMbps)} Mbps")
    log.info("  Active connections: $active (${"%.1f".format(if (total > 0) active * 100.0 / total else 0.0)}%)")
    log.info("  Timed out connections: ${total - active}")
    log.info("  Mode: ${snap.mode}")
    when (snap.mode) {
        SchedulingMode.CLASSIC -> log.info("    (quality scoring not applicable)")
        SchedulingMode.ENHANCED -> log.info("    Quality: ${if (snap.qualityEnabled) "ON" else "OFF"}")
    }
    log.info("  Packet log: $totalInFlight entries used (${"%.1f".format(utilization)}% of capacity)")
    if (lastSelectedIdx != null) {
        if (lastSelectedIdx < connections.size) {
            log.info("  Last selected: ${connections[lastSelectedIdx].label}")
        } else {
            log.warning("  Last selected index $lastSelectedIdx is out of bounds!")
        }
    } else {
        log.info("  Last selected: none")
    }

    for ((i, c) in connections.withIndex()) {
        val status = if (c.isTimedOut(now)) "TIMED_OUT" else "ACTIVE"
        val scoreDesc = when (val s = c.getScore()) {
            -1 -> "DISCONNECTED"
            0 -> "AT_CAPACITY"
            else -> s.toString()
        }
        val lastRecv = c.lastReceived?.let { "%.1fs ago".format(now.satSub(it) / 1000.0) } ?: "never"
        val lastSend = c.lastSent?.let { "%.1fs ago".format(now.satSub(it) / 1000.0) } ?: "never"
        log.info(
            "    [$i] $status ${c.label} - Score: $scoreDesc - Last recv/send: $lastRecv/$lastSend - " +
                "Window: ${c.window} - In-flight: ${c.inFlightPackets} - Bitrate: ${"%.2f".format(c.currentBitrateMbps())} Mbps",
        )
        if (c.rtt.estimatedRttMs > 0.0) {
            log.info(
                "        RTT: kalman=${"%.1f".format(c.getSmoothRttMs())}ms, velocity=${"%.2f".format(c.getRttVelocity())}ms/sample, " +
                    "jitter=${"%.1f".format(c.getRttJitterMs())}ms, stable=${c.isRttStable()} " +
                    "(last: ${"%.1f".format(now.satSub(c.rtt.lastRttMeasurementMs) / 1000.0)}s ago)",
            )
        }
    }
    if (active == 0) {
        log.warning("No active connections available!")
    } else if (active < total / 2) {
        log.warning("Less than half of connections are active")
    }
}
