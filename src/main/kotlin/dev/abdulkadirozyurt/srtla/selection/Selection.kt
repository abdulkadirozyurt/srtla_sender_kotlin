// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/selection/mod.rs
//
// Two strategies remain in v4:
//   Classic  — C-exact capacity selection (window / (in_flight + 1)), plus
//              optional operator link weights.
//   Enhanced — quality-aware scoring with admission gates, sole-carrier
//              election, BDP in-flight cap, CC soft cap and 10% hysteresis.
package dev.abdulkadirozyurt.srtla.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.core.SchedulingMode

/**
 * Select the best connection index for one packet, or null when none is usable.
 * Called once per routed SRT packet.
 */
fun selectConnectionIdx(
    conns: List<SrtlaConnection>,
    lastIdx: Int?,
    currentTimeMs: Long,
    config: ConfigSnapshot,
): Int? {
    applyStallGate(conns, currentTimeMs, config)
    return when (config.mode) {
        SchedulingMode.CLASSIC -> {
            // The quality gates are Enhanced-only and the mode switches at
            // runtime, so their transient flags must not freeze here.
            for (c in conns) c.clearQualityGateState()
            selectClassic(conns, currentTimeMs)
        }
        SchedulingMode.ENHANCED ->
            selectEnhanced(conns, lastIdx, currentTimeMs, config.effectiveQualityEnabled())
    }
}

/**
 * Drive every link's silence pull and stall latch, and recompute stallGated.
 *
 * A link is gated only when at least one un-latched, un-pulled schedulable link
 * exists, so the last usable link is never gated and the selectors can skip a
 * gated link without a fallback pass. With the guard off, flag, latch and pull
 * are cleared, restoring baseline selection.
 *
 * Also refreshes each link's connTimeoutMs and delayBudgetMs from the snapshot,
 * so paths that carry no config see the current runtime values.
 */
internal fun applyStallGate(conns: List<SrtlaConnection>, currentTimeMs: Long, config: ConfigSnapshot) {
    for (c in conns) {
        c.connTimeoutMs = config.connTimeoutMs
        c.delayBudgetMs = config.negotiatedLatencyMs
    }
    if (!config.stallDeselect) {
        for (c in conns) {
            c.stallGated = false
            c.silencePulled = false
            c.clearStallLatch()
        }
        return
    }
    val minInFlight = config.stallMinInFlight
    val staleCeilingMs = config.stallAckStaleMs
    for (c in conns) {
        // Pull first: the latch's escalation path reads the fresh pull state.
        c.updateSilencePull(currentTimeMs, minInFlight, staleCeilingMs)
        c.updateStallLatch(currentTimeMs, minInFlight, staleCeilingMs)
    }
    val anyHealthy = conns.any {
        !it.isTimedOut(currentTimeMs) && it.isSchedulable() && !it.stallLatched() && !it.silencePulled
    }
    for (c in conns) c.stallGated = anyHealthy && (c.stallLatched() || c.silencePulled)
}
