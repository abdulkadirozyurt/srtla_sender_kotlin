// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/config_snapshot.rs
//
// Pure, hot-path configuration snapshot. The live DynamicConfig builds one of
// these once per select iteration; this type depends only on SchedulingMode.
package dev.abdulkadirozyurt.srtla.core

import dev.abdulkadirozyurt.srtla.protocol.CONN_TIMEOUT

/** In-flight backlog at or above which a link is a stall candidate. */
const val STALL_MIN_IN_FLIGHT_PACKETS: Int = 32

/**
 * Ceiling (ms) on the RTT-adaptive delivery-proof staleness window:
 * `clamp(STALL_STALE_RTT_MULT x srtt, STALL_STALE_FLOOR_MS, this)`. A link with
 * no RTT baseline falls back to the ceiling. Deselect is a selection penalty
 * only, never a liveness shortcut.
 */
const val STALL_ACK_STALE_MS: Long = 3000L

/** Multiplier on smoothed RTT for the adaptive staleness window. */
const val STALL_STALE_RTT_MULT: Long = 4L

/** Floor (ms) on the adaptive staleness window; above routine HARQ stalls. */
const val STALL_STALE_FLOOR_MS: Long = 1000L

/** Rejoin dwell as a multiple of the effective staleness window. */
const val STALL_REJOIN_DWELL_MULT: Long = 2L

/** Ceiling on the rejoin-dwell backoff multiplier. */
const val STALL_REJOIN_BACKOFF_MAX: Int = 16

/** How long a rejoin must last, in staleness windows, to count as having held. */
const val STALL_REJOIN_PROBATION_MULT: Long = STALL_REJOIN_DWELL_MULT + 1

/** Size at which the outstanding-probe log starts expiring entries. */
const val PROBE_LOG_SOFT_CAP: Int = 128

/** Age at which an unanswered probe is dropped from the probe log. */
const val PROBE_LOG_MAX_AGE_MS: Long = 10_000L

/** Share a rejoining link starts its ramp at, as a fraction of its natural score. */
const val STALL_REJOIN_RAMP_FLOOR: Double = 0.05

/** Duplicate-probe rate on a held-out link: one copy of every Nth routed packet. */
const val STALL_PROBE_ONE_IN_N: Int = 100

/** Floor (ms) for the fast silence-pull window. */
const val SILENCE_PULL_FLOOR_MS: Long = 250L

/** Multiplier on smoothed RTT for the silence-pull window. */
const val SILENCE_PULL_RTT_MULT: Long = 2L

/** Default per-link liveness timeout (ms). Matches the classic CONN_TIMEOUT. */
const val CONN_TIMEOUT_MS: Long = CONN_TIMEOUT * 1000L

/** Clamp bounds for the runtime-configurable liveness timeout. */
const val CONN_TIMEOUT_MS_MIN: Long = 1_000L
const val CONN_TIMEOUT_MS_MAX: Long = 60_000L

/**
 * Snapshot of configuration for efficient hot-path access.
 * Mirrors Rust `struct ConfigSnapshot`.
 */
data class ConfigSnapshot(
    val mode: SchedulingMode = SchedulingMode.ENHANCED,
    val qualityEnabled: Boolean = true,
    /**
     * Stalled-link deselect (default on). Off (`--no-stall-deselect`), selection
     * is byte-for-byte unchanged.
     */
    val stallDeselect: Boolean = true,
    val stallMinInFlight: Int = STALL_MIN_IN_FLIGHT_PACKETS,
    val stallAckStaleMs: Long = STALL_ACK_STALE_MS,
    /** Per-link liveness timeout in ms. Silence past this re-registers the link. */
    val connTimeoutMs: Long = CONN_TIMEOUT_MS,
    /**
     * One-way delivery budget in ms: the TSBPD receive delay the far-end SRT
     * listener declared in its handshake response. Zero until the handshake
     * crosses (or on a peer without TSBPD).
     */
    val negotiatedLatencyMs: Int = 0,
) {
    /** Quality scoring only applies to enhanced mode. */
    fun effectiveQualityEnabled(): Boolean = qualityEnabled && !mode.isClassic()
}
