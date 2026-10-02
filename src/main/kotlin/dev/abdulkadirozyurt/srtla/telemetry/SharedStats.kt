// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/stats.rs

package dev.abdulkadirozyurt.srtla.telemetry

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.json.Json
import dev.abdulkadirozyurt.srtla.selection.ClassificationResult
import dev.abdulkadirozyurt.srtla.selection.LinkCcSnapshot
import dev.abdulkadirozyurt.srtla.selection.calculateQualityMultiplier
import dev.abdulkadirozyurt.srtla.selection.inFlightCapPackets
import dev.abdulkadirozyurt.srtla.core.ConfigSnapshot

/**
 * Per-link statistics.
 *
 * Fields match `ConnectionInfo` (extended keepalive format) where applicable,
 * plus additional context useful for external monitoring.
 */
data class LinkStats(
    /** Local IP address used for this link */
    val ip: String,
    /** Human-readable label (e.g., "host:port via ip") */
    val label: String,
    /** True if SRTLA registration completed (REG3 received) */
    val connected: Boolean,
    /** True if no packets received within timeout period */
    val timedOut: Boolean,

    // --- Core metrics (same as ConnectionInfo in extended keepalives) ---
    /** Congestion window size (packet count). Higher = more capacity. */
    val window: Int,
    /** Packets sent but not yet ACKed. Higher = more load on this link. */
    val inFlight: Int,
    /** Smoothed RTT in milliseconds. From Kalman filter. */
    val rttMs: Long,
    /** Total NAK count since connection established. Indicates packet loss. */
    val nakCount: Int,
    /** Measured send rate in bytes per second (not estimated). */
    val bitrateBytesPerSec: Long,

    // --- RTT baseline tracking ---
    /** Dual-window minimum RTT baseline in milliseconds. */
    val rttMinMs: Double,
    /** Kalman RTT velocity in ms/sample (positive = rising, negative = falling). */
    val rttVelocity: Double,

    // --- Selection algorithm context ---
    /** Base score: window / (in_flight + 1). Used by classic mode. */
    val baseScore: Int,
    /** Quality multiplier (0.35 to 1.1) used by enhanced mode. */
    val qualityMultiplier: Double,

    // --- Weak-link classifier ---
    /** Whether the classifier flagged this link as weak this tick. */
    val weak: Boolean,
    /** Why the link was (or was not) flagged. */
    val weakReason: String,
    /** This link's share of total throughput in permille (0..=1000). */
    val weakSharePermille: Int,
    /** Threshold the share was checked against (permille). */
    val weakThresholdPermille: Int,

    // --- Per-link CC soft cap ---
    /** Current state: bootstrap / climbing / holding / backing_off / drain. */
    val ccState: String,
    /** Active climb sub-mode when cc_state == climbing. */
    val ccClimbMode: String,
    /** Target sendable rate this link's CC believes is sustainable (bps). */
    val ccTargetBps: Long,
    /** Age-bucketed RTT EWMA (ms) — input to the CC state machine. */
    val ccRttEwmaMs: Double,
    /** 1:3 weighted moving deviation around cc_rtt_ewma_ms (ms). */
    val ccRttVarMs: Double,
    /** Lowest RTT ever observed on this link (ms). */
    val ccRttMinMs: Double,
    /** Loss permille over the 1s rolling window. */
    val ccLossPermille: Int,
    /** Time-decayed loss fraction (0..1) driving continuous phase demotion. */
    val ccLossEwma: Double,
    /** Whether the sustained-loss verdict has latched. */
    val ccLossDegraded: Boolean,

    // --- Adaptive batch-send regime ---
    /** Current per-connection batch-send regime. */
    val batchRegime: String,

    // --- Stalled-link deselect ---
    /** Whether this link's stall LATCH is engaged. */
    val stallGated: Boolean,
    /** Cumulative stall-latch engagements since the link was created. */
    val stallGateEvents: Long,
    /** Normalised operator link weight from the IPs file (1 = unweighted). */
    val weight: Int,
    /** Cumulative fast silence-pull engagements. */
    val silencePulls: Long,
    /** Whether this link is the elected sole carrier. */
    val soleCarrier: Boolean,
    /** Whether a sibling holds that role and this link is being held out. */
    val soleCarrierExcluded: Boolean,
    /** Cumulative sole-carrier handovers from another link to this one. */
    val soleCarrierElections: Long,

    // --- In-flight cap soft admission gate ---
    /** In-flight cap in packets. 0 means "no signal". */
    val inFlightCapPackets: Int,
    /** Whether the cap was active this tick. */
    val inFlightCapActive: Boolean,
) {
    fun toJsonValue(): Map<String, Any?> = linkedMapOf(
        "ip" to ip,
        "label" to label,
        "connected" to connected,
        "timed_out" to timedOut,
        "window" to window,
        "in_flight" to inFlight,
        "rtt_ms" to rttMs,
        "nak_count" to nakCount,
        "bitrate_bytes_per_sec" to bitrateBytesPerSec,
        "rtt_min_ms" to rttMinMs,
        "rtt_velocity" to rttVelocity,
        "base_score" to baseScore,
        "quality_multiplier" to qualityMultiplier,
        "weak" to weak,
        "weak_reason" to weakReason,
        "weak_share_permille" to weakSharePermille,
        "weak_threshold_permille" to weakThresholdPermille,
        "cc_state" to ccState,
        "cc_climb_mode" to ccClimbMode,
        "cc_target_bps" to ccTargetBps,
        "cc_rtt_ewma_ms" to ccRttEwmaMs,
        "cc_rtt_var_ms" to ccRttVarMs,
        "cc_rtt_min_ms" to ccRttMinMs,
        "cc_loss_permille" to ccLossPermille,
        "cc_loss_ewma" to ccLossEwma,
        "cc_loss_degraded" to ccLossDegraded,
        "batch_regime" to batchRegime,
        "stall_gated" to stallGated,
        "stall_gate_events" to stallGateEvents,
        "weight" to weight,
        "silence_pulls" to silencePulls,
        "sole_carrier" to soleCarrier,
        "sole_carrier_excluded" to soleCarrierExcluded,
        "sole_carrier_elections" to soleCarrierElections,
        "in_flight_cap_packets" to inFlightCapPackets,
        "in_flight_cap_active" to inFlightCapActive,
    )
}

/**
 * Aggregate statistics snapshot.
 */
data class StatsSnapshot(
    /** Current scheduling mode: "classic" or "enhanced" */
    val mode: String = "enhanced",
    /** Whether quality scoring is enabled (always false for classic mode) */
    val qualityEnabled: Boolean = true,

    /** Number of links that are connected AND not timed out */
    val activeLinks: Int = 0,
    /** Total configured links */
    val totalLinks: Int = 0,

    /** Sum of window across active links */
    val totalWindow: Int = 0,
    /** Sum of in_flight across active links */
    val totalInFlight: Int = 0,

    // --- Weak-link classifier output ---
    /** Estimated max delay budget the classifier derived this tick (ms). */
    val weakLinkEstimatedMaxDelayMs: Int = 0,
    /** Delay tier the cascade chose this tick (ms). */
    val weakLinkSelectedDelayMs: Int = 0,

    /** One-way delivery budget in ms read off the SRT peer's handshake. */
    val negotiatedLatencyMs: Int = 0,

    /** Per-link details */
    val links: List<LinkStats> = emptyList(),
) {
    fun toJsonValue(): Map<String, Any?> = linkedMapOf(
        "mode" to mode,
        "quality_enabled" to qualityEnabled,
        "active_links" to activeLinks,
        "total_links" to totalLinks,
        "total_window" to totalWindow,
        "total_in_flight" to totalInFlight,
        "weak_link_estimated_max_delay_ms" to weakLinkEstimatedMaxDelayMs,
        "weak_link_selected_delay_ms" to weakLinkSelectedDelayMs,
        "negotiated_latency_ms" to negotiatedLatencyMs,
        "links" to links.map { it.toJsonValue() },
    )
}

/**
 * Thread-safe container for stats export.
 *
 * Updated by sender during housekeeping (~1s interval).
 * Read by config handler when `stats` command is received.
 */
class SharedStats {
    @Volatile
    private var snapshot: StatsSnapshot = StatsSnapshot()

    /**
     * Update stats from current connection state.
     *
     * `classification` carries the weak-link classifier's per-tick output.
     * Pass `null` when the classifier is disabled or unavailable; the weak
     * fields are populated with neutral defaults in that case.
     */
    fun update(
        connections: List<SrtlaConnection>,
        config: ConfigSnapshot,
        classification: ClassificationResult?,
        linkCc: Map<Long, LinkCcSnapshot>?,
        nowMs: Long = dev.abdulkadirozyurt.srtla.core.nowMs(),
    ) {
        val currentTimeMs = nowMs
        val qualityEnabled = config.qualityEnabled && config.mode.toString() != "classic"

        val newSnapshot = StatsSnapshot(
            mode = config.mode.toString(),
            qualityEnabled = qualityEnabled,
            totalLinks = connections.size,
            weakLinkEstimatedMaxDelayMs = classification?.estimatedMaxDelayMs ?: 0,
            weakLinkSelectedDelayMs = classification?.selectedDelayMs ?: 0,
            negotiatedLatencyMs = config.negotiatedLatencyMs,
        )

        var activeLinks = 0
        var totalWindow = 0
        var totalInFlight = 0
        val linkStatsList = mutableListOf<LinkStats>()

        for (conn in connections) {
            val timedOut = conn.isTimedOut(currentTimeMs)
            val isActive = conn.connected && !timedOut

            // Quality multiplier: use actual selection algorithm calculation,
            // or 1.0 if quality scoring is disabled (classic mode)
            val qualityMultiplier = if (qualityEnabled) {
                calculateQualityMultiplier(conn, currentTimeMs)
            } else {
                1.0
            }

            // Weak-link classifier entry
            val weakEntry = classification?.perLink?.find { it.connId == conn.connId }
            val (weak, weakReason, weakShare, weakThreshold) = if (weakEntry != null) {
                Quadruple(
                    weakEntry.weak,
                    weakEntry.reason.wireName,
                    weakEntry.sharePermille,
                    weakEntry.thresholdPermille,
                )
            } else {
                Quadruple(false, "unknown", 0, 0)
            }

            // CC entry
            val ccEntry = linkCc?.get(conn.connId)
            val (ccState, ccClimbMode, ccTargetBps, ccRttEwma, ccRttVar, ccRttMin,
                ccLossPm, ccLossEwma, ccLossDegraded) = if (ccEntry != null) {
                Tuple9(
                    ccEntry.state.asStr(),
                    ccEntry.climbMode.asStr(),
                    ccEntry.targetBps,
                    ccEntry.rttEwmaMs,
                    ccEntry.rttVarMs,
                    ccEntry.rttMinMs,
                    ccEntry.lossPermille,
                    ccEntry.lossEwma,
                    ccEntry.lossDegraded,
                )
            } else {
                Tuple9("unknown", "normal", 0L, 0.0, 0.0, 0.0, 0, 0.0, false)
            }

            val cap = inFlightCapPackets(ccTargetBps, conn.getRttMinMs())
            val inFlightCapPkts = cap?.coerceAtLeast(0) ?: 0
            val inFlightCapActive = cap != null && conn.inFlightPackets > cap

            val link = LinkStats(
                ip = conn.localIp.hostAddress,
                label = conn.label,
                connected = conn.connected,
                timedOut = timedOut,
                window = conn.window,
                inFlight = conn.inFlightPackets,
                rttMs = conn.getSmoothRttMs().toLong(),
                nakCount = conn.totalNakCount(),
                bitrateBytesPerSec = (conn.currentBitrateMbps() * 1_000_000.0 / 8.0).toLong(),
                rttMinMs = conn.getRttMinMs(),
                rttVelocity = conn.getRttVelocity(),
                baseScore = conn.getScore(),
                qualityMultiplier = qualityMultiplier,
                weak = weak,
                weakReason = weakReason,
                weakSharePermille = weakShare,
                weakThresholdPermille = weakThreshold,
                ccState = ccState,
                ccClimbMode = ccClimbMode,
                ccTargetBps = ccTargetBps,
                ccRttEwmaMs = ccRttEwma,
                ccRttVarMs = ccRttVar,
                ccRttMinMs = ccRttMin,
                ccLossPermille = ccLossPm,
                ccLossEwma = ccLossEwma,
                ccLossDegraded = ccLossDegraded,
                batchRegime = conn.batchSender.regime.asStr(),
                stallGated = conn.stallLatched(),
                stallGateEvents = conn.stallGateEvents(),
                weight = conn.linkWeight,
                silencePulls = conn.silencePulls(),
                soleCarrier = conn.isSoleCarrier(),
                soleCarrierExcluded = conn.isSoleCarrierExcluded(),
                soleCarrierElections = conn.soleCarrierElections(),
                inFlightCapPackets = inFlightCapPkts,
                inFlightCapActive = inFlightCapActive,
            )

            if (isActive) {
                activeLinks += 1
                totalWindow += conn.window
                totalInFlight += conn.inFlightPackets
            }

            linkStatsList.add(link)
        }

        snapshot = newSnapshot.copy(
            activeLinks = activeLinks,
            totalWindow = totalWindow,
            totalInFlight = totalInFlight,
            links = linkStatsList,
        )
    }

    /** Get current stats snapshot. */
    fun get(): StatsSnapshot = snapshot

    /** Serialize to JSON. */
    fun toJson(): String = Json.write(get().toJsonValue())
}

// Helper data classes for tuple unpacking
private data class Quadruple(
    val item1: Boolean,
    val item2: String,
    val item3: Int,
    val item4: Int,
)

private data class Tuple9(
    val item1: String,
    val item2: String,
    val item3: Long,
    val item4: Double,
    val item5: Double,
    val item6: Double,
    val item7: Int,
    val item8: Double,
    val item9: Boolean,
)
