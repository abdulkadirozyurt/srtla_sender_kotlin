// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/selection/mod.rs, src/config.rs, src/mode.rs
//
// SelectionOrchestrator — top-level dispatcher for all scheduling modes.
// Also contains SchedulingMode enum and ConfigSnapshot data class (port of
// Rust src/mode.rs + src/config.rs::ConfigSnapshot).
//
// Quality scoring only applies to Enhanced and RttThreshold modes.
// Exploration only applies to Enhanced mode.
// EDPF pipeline: BLEST → IoDS → EDPF argmin.
package dev.abdulkadirozyurt.srtla.sender.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection

// ── SchedulingMode ────────────────────────────────────────────────────────────

/**
 * Scheduling mode for connection selection.
 * Mirrors Rust `enum SchedulingMode` in src/mode.rs.
 */
enum class SchedulingMode {
    /** Pure capacity-based — matches original C implementation, no dampening. */
    CLASSIC,       // mode.rs: Classic   → 0
    /** Quality-aware with hysteresis + optional exploration (default). */
    ENHANCED,      // mode.rs: Enhanced  → 1
    /** Groups links by RTT; prefers fast links, quality within group. */
    RTT_THRESHOLD, // mode.rs: RttThreshold → 2
    /** BLEST → IoDS → EDPF argmin pipeline. */
    EDPF;          // mode.rs: Edpf → 3

    fun isClassic(): Boolean = this == CLASSIC
    fun isEnhanced(): Boolean = this == ENHANCED
    fun isRttThreshold(): Boolean = this == RTT_THRESHOLD
    fun isEdpf(): Boolean = this == EDPF
}

// ── ConfigSnapshot ────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of scheduling configuration.
 * Mirrors Rust `struct ConfigSnapshot` in src/config.rs.
 *
 * Created once per scheduling call to avoid repeated lock/atomic reads
 * in the hot path.
 *
 * NOTE (Faz D hook): In Faz D a DynamicConfig class with AtomicReference
 * will own mutable config and produce ConfigSnapshot via snapshot().
 * For now callers construct ConfigSnapshot directly.
 */
data class ConfigSnapshot(
    /** Which scheduling algorithm to use. */
    val mode: SchedulingMode = SchedulingMode.ENHANCED,
    /** Whether quality scoring is requested (may be suppressed by mode). */
    val qualityEnabled: Boolean = true,
    /** Whether smart exploration is requested (only effective in Enhanced mode). */
    val explorationEnabled: Boolean = false,
    /** RTT delta threshold in ms for RTT-threshold mode. src/config.rs: DEFAULT_RTT_DELTA_MS */
    val rttDeltaMs: Int = RTT_DELTA_DEFAULT_MS,
) {
    /**
     * Quality scoring is only effective in Enhanced and RttThreshold modes.
     * Mirrors Rust `ConfigSnapshot::effective_quality_enabled`.
     */
    fun effectiveQualityEnabled(): Boolean = qualityEnabled && !mode.isClassic()

    /**
     * Exploration is only effective in Enhanced mode.
     * Mirrors Rust `ConfigSnapshot::effective_exploration_enabled`.
     */
    fun effectiveExplorationEnabled(): Boolean = explorationEnabled && mode.isEnhanced()
}

// ── SelectionOrchestrator ─────────────────────────────────────────────────────

/**
 * Stateful orchestrator that holds per-mode state (quality caches, BLEST, IoDS)
 * and dispatches to the appropriate algorithm.
 *
 * Mirrors Rust `select_connection_idx` in src/sender/selection/mod.rs plus
 * the thread-local BLEST/IoDS state in `edpf_pipeline_select`.
 *
 * Thread-safety: NOT thread-safe. The caller (SrtlaSender) holds a lock.
 */
class SelectionOrchestrator {

    // Quality caches indexed by connId (Long → QualityCache)
    private val qualityCaches: MutableMap<Long, QualityCache> = HashMap()

    // EDPF pipeline state
    private val blest = BlestFilter()   // blest.rs: BlestFilter
    private val iods  = IodsFilter()    // iods.rs:  IodsFilter

    /**
     * Select the best connection index.
     *
     * Mirrors Rust `select_connection_idx` in src/sender/selection/mod.rs.
     *
     * @param conns         All uplink connections.
     * @param lastIdx       Previously selected index (hysteresis / dampening).
     * @param lastSwitchMs  Timestamp of last switch.
     * @param currentTimeMs Current wall-clock time in ms.
     * @param config        Immutable config snapshot.
     * @return Index of selected connection, or null if none available.
     */
    fun select(
        conns: List<SrtlaConnection>,
        lastIdx: Int?,
        lastSwitchMs: Long,
        currentTimeMs: Long,
        config: ConfigSnapshot,
    ): Int? {
        // Prune stale quality caches for connections no longer present
        val activeIds = conns.map { it.connId }.toHashSet()
        qualityCaches.keys.retainAll(activeIds)

        return when (config.mode) {
            SchedulingMode.CLASSIC -> {
                // Classic: no dampening, no quality — matches original C
                ClassicSelection.select(conns, lastIdx, lastSwitchMs, currentTimeMs)
            }
            SchedulingMode.ENHANCED -> {
                // Ensure quality caches exist for all connections
                for (c in conns) qualityCaches.getOrPut(c.connId) { QualityCache() }
                enhancedSelect(
                    conns            = conns,
                    qualityCache     = qualityCaches,
                    lastIdx          = lastIdx,
                    lastSwitchMs     = lastSwitchMs,
                    currentTimeMs    = currentTimeMs,
                    enableQuality    = config.effectiveQualityEnabled(),
                    enableExplore    = config.effectiveExplorationEnabled(),
                )
            }
            SchedulingMode.RTT_THRESHOLD -> {
                // Ensure quality caches exist for all connections
                for (c in conns) qualityCaches.getOrPut(c.connId) { QualityCache() }
                rttThresholdSelect(
                    conns         = conns,
                    qualityCache  = qualityCaches,
                    lastIdx       = lastIdx,
                    lastSwitchMs  = lastSwitchMs,
                    currentTimeMs = currentTimeMs,
                    rttDeltaMs    = config.rttDeltaMs,
                    enableQuality = config.effectiveQualityEnabled(),
                )
            }
            SchedulingMode.EDPF -> {
                edpfPipelineSelect(conns)
            }
        }
    }

    /**
     * EDPF pipeline: BLEST filters → IoDS ordering → EDPF argmin.
     * Mirrors Rust `edpf_pipeline_select` in src/sender/selection/mod.rs.
     *
     * 1. BLEST filters out HoL-blocking links.
     * 2. IoDS filters for monotonic ordering.
     * 3. EDPF selects argmin(predicted_arrival) from remaining.
     * Fallbacks: BLEST-only, then all connections.
     */
    private fun edpfPipelineSelect(conns: List<SrtlaConnection>): Int? {
        blest.tick()

        // 1. BLEST filter
        val candidates = blest.filter(conns)

        // 2. IoDS filter for monotonic ordering
        val ordered = iods.filterValid(candidates) { idx ->
            edpfPredictedArrival(conns[idx], SRT_PKT_SIZE)
        }

        // 3. EDPF argmin with fallbacks
        val selected = edpfSelectFromIndices(conns, ordered, SRT_PKT_SIZE)
            ?: edpfSelectFromIndices(conns, candidates, SRT_PKT_SIZE)
            ?: edpfSelectFrom(conns, SRT_PKT_SIZE)

        // Record scheduled arrival for IoDS state update
        if (selected != null) {
            val arrival = edpfPredictedArrival(conns[selected], SRT_PKT_SIZE)
            if (arrival != null) iods.recordScheduled(arrival)
        }

        return selected
    }

    /** Reset all stateful caches (e.g., after full reconnect). */
    fun reset() {
        qualityCaches.clear()
        iods.reset()
    }
}
