// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/stats.rs
//
// SharedStats — per-connection telemetry exported via the control channel `stats` command.
//
// Design mirrors Rust:
//   1. Raw metrics only — no transformation; consumers interpret.
//   2. Matches ConnectionInfo (extended keepalive format).
//   3. Includes quality_multiplier — exact value used by enhanced/rtt-threshold.
//   4. Simple aggregates (sum/count), no derived calculations.
//
// Thread-safety: inner snapshot protected by ReentrantReadWriteLock.
// update() is called from the housekeeping thread (~1s); get() from control handler.
// No serde dependency: JSON is hand-written to avoid external libs (JDK 11 only).
package dev.abdulkadirozyurt.srtla.stats

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.sender.selection.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.sender.selection.SchedulingMode
import java.net.InetAddress
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

// ── Per-link stats ────────────────────────────────────────────────────────────

/**
 * Per-link statistics snapshot.
 * Mirrors Rust `struct LinkStats` in src/stats.rs.
 * Fields align with ConnectionInfo (extended keepalive format).
 */
data class LinkStats(
    /** Local IP used for this uplink. */
    val ip: String,
    /** Human-readable label (host:port via ip). */
    val label: String,
    /** True if SRTLA REG3 received. */
    val connected: Boolean,
    /** True if no packets received within CONN_TIMEOUT. */
    val timedOut: Boolean,

    // src/stats.rs: Core metrics (same as ConnectionInfo)
    /** Congestion window (packet count). Primary capacity signal. */
    val window: Int,
    /** In-flight (sent, not yet ACKed). */
    val inFlight: Int,
    /** Smoothed RTT ms (Kalman filter). */
    val rttMs: Int,
    /** Total NAK count since connection start. */
    val nakCount: Int,
    /** Measured send bitrate bytes/sec. */
    val bitrateBps: Long,

    // src/stats.rs: RTT baseline
    /** Dual-window min RTT baseline ms. */
    val rttMinMs: Double,
    /** Kalman RTT velocity ms/sample (positive=rising). */
    val rttVelocity: Double,

    // src/stats.rs: Selection algorithm context
    /** Base score = window / (inFlight + 1). Classic mode signal. */
    val baseScore: Int,
    /**
     * Quality multiplier (0.35–1.1) used by enhanced/rtt-threshold.
     * 1.1 = perfect, 1.0 = normal, <1.0 = degraded, ~0.35 = penalized.
     * Always 1.0 in classic mode.
     */
    val qualityMultiplier: Double,
)

// ── Aggregate snapshot ────────────────────────────────────────────────────────

/**
 * Aggregate stats snapshot.
 * Mirrors Rust `struct StatsSnapshot` in src/stats.rs.
 */
data class StatsSnapshot(
    /** Current mode string: "classic", "enhanced", "rtt-threshold", "edpf". */
    val mode: String = "enhanced",
    /** Quality scoring enabled (always false for classic). */
    val qualityEnabled: Boolean = true,
    /** RTT delta threshold ms (rtt-threshold mode). */
    val rttDeltaMs: Int = 30,
    /** Connected and non-timed-out link count. */
    val activeLinks: Int = 0,
    /** Total configured link count. */
    val totalLinks: Int = 0,
    /** Sum of window across active links. */
    val totalWindow: Int = 0,
    /** Sum of in_flight across active links. */
    val totalInFlight: Int = 0,
    /** Per-link details. */
    val links: List<LinkStats> = emptyList(),
) {
    /** Serialize to hand-written JSON (no external library). */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("""{"mode":${jsonStr(mode)},"quality_enabled":$qualityEnabled,"rtt_delta_ms":$rttDeltaMs,""")
        sb.append(""""active_links":$activeLinks,"total_links":$totalLinks,""")
        sb.append(""""total_window":$totalWindow,"total_in_flight":$totalInFlight,"links":[""")
        links.forEachIndexed { i, l ->
            if (i > 0) sb.append(",")
            sb.append("""{""")
            sb.append(""""ip":${jsonStr(l.ip)},"label":${jsonStr(l.label)},""")
            sb.append(""""connected":${l.connected},"timed_out":${l.timedOut},""")
            sb.append(""""window":${l.window},"in_flight":${l.inFlight},""")
            sb.append(""""rtt_ms":${l.rttMs},"nak_count":${l.nakCount},"bitrate_bps":${l.bitrateBps},""")
            sb.append(""""rtt_min_ms":${l.rttMinMs},"rtt_velocity":${l.rttVelocity},""")
            sb.append(""""base_score":${l.baseScore},"quality_multiplier":${l.qualityMultiplier}""")
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun jsonStr(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

// ── SharedStats ───────────────────────────────────────────────────────────────

/**
 * Thread-safe container for stats export.
 * Updated by housekeeping (~1s). Read by control handler on `stats` command.
 * Mirrors Rust `struct SharedStats` in src/stats.rs.
 */
class SharedStats {
    private val lock = ReentrantReadWriteLock()
    private var snapshot: StatsSnapshot = StatsSnapshot()

    /**
     * Update stats from current connection state.
     * Called from housekeeping thread. Mirrors Rust `SharedStats::update`.
     */
    fun update(connections: List<SrtlaConnection>, config: ConfigSnapshot) {
        val qualityEnabled = config.qualityEnabled && !config.mode.isClassic()
        val modeName = config.mode.name.lowercase().replace('_', '-')

        var activeLinks = 0
        var totalWindow = 0
        var totalInFlight = 0
        val linksList = mutableListOf<LinkStats>()

        val now = System.currentTimeMillis()

        for (conn in connections) {
            val timedOut = conn.isTimedOut()
            val isActive = conn.connected && !timedOut

            // src/stats.rs: quality_multiplier = exact value from selection algorithm
            // In classic mode always 1.0 (quality scoring disabled).
            val qualityMult = if (qualityEnabled) {
                calculateQualityMultiplier(conn, now)
            } else {
                1.0
            }

            val link = LinkStats(
                ip               = conn.localIp.hostAddress ?: conn.localIp.toString(),
                label            = conn.label,
                connected        = conn.connected,
                timedOut         = timedOut,
                window           = conn.window,
                inFlight         = conn.inFlightPackets,
                rttMs            = conn.getSmoothRttMs().toInt().coerceAtLeast(0),
                nakCount         = conn.totalNakCount(),
                bitrateBps       = (conn.currentBitrateMbps() * 1_000_000.0 / 8.0).toLong().coerceAtLeast(0L),
                rttMinMs         = conn.getRttMinMs(),
                rttVelocity      = conn.getRttVelocity(),
                baseScore        = conn.getScore(),
                qualityMultiplier = qualityMult,
            )

            if (isActive) {
                activeLinks++
                totalWindow  += conn.window
                totalInFlight += conn.inFlightPackets
            }
            linksList += link
        }

        val newSnap = StatsSnapshot(
            mode        = modeName,
            qualityEnabled = qualityEnabled,
            rttDeltaMs  = config.rttDeltaMs,
            activeLinks = activeLinks,
            totalLinks  = connections.size,
            totalWindow = totalWindow,
            totalInFlight = totalInFlight,
            links       = linksList,
        )

        lock.write { snapshot = newSnap }
    }

    /** Get current snapshot (read-locked). */
    fun get(): StatsSnapshot = lock.read { snapshot }

    /** Serialize to JSON. Mirrors Rust `SharedStats::to_json`. */
    fun toJson(): String = get().toJson()

    // ── Quality multiplier (mirrors Rust calculate_quality_multiplier) ─────────
    // Ported from src/sender/selection/mod.rs::calculate_quality_multiplier
    // Kept here so SharedStats can compute it without depending on selection package.
    private fun calculateQualityMultiplier(conn: SrtlaConnection, nowMs: Long): Double {
        val nakCount = conn.totalNakCount()
        if (nakCount == 0) return 1.1 // perfect: no NAKs ever

        val nakBurst = conn.congestion.nakBurstCount
        if (nakBurst > 0) {
            // Heavy penalty for NAK burst (mirrors Rust: 0.35)
            return 0.35
        }

        // Time-based decay: recent NAKs penalize more
        val lastNakMs = conn.congestion.lastNakTimeMs
        val agoMs = if (lastNakMs > 0L) (nowMs - lastNakMs).coerceAtLeast(0L) else 0L
        val decaySec = agoMs / 1000.0

        // Mirrors Rust quality multiplier decay curve
        // Base: 1.0, penalty per NAK decays exponentially with time
        val penalty = 0.1 * Math.exp(-decaySec / 10.0)
        return (1.0 - penalty).coerceIn(0.35, 1.1)
    }
}
