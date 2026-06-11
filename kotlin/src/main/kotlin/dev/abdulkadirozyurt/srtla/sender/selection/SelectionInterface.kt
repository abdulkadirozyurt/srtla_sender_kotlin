// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/selection/mod.rs
//
// Minimal Selection interface — Faz C will add Enhanced, RttThreshold, Edpf.
// Classic is fully implemented here; the interface is designed so Faz C can
// add concrete implementations without changing callers.
//
// MIN_SWITCH_INTERVAL_MS = 15ms (aligned with batch flush interval).
package dev.abdulkadirozyurt.srtla.sender.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection

/** Minimum time between connection switches (ms). src/sender/selection/mod.rs */
const val MIN_SWITCH_INTERVAL_MS: Long = 15L

/**
 * Connection selection strategy interface.
 * Faz C will add: EnhancedSelection, RttThresholdSelection, EdpfSelection.
 */
interface SelectionStrategy {
    /**
     * Select the best connection index.
     *
     * @param conns      All uplink connections (may update internal quality caches).
     * @param lastIdx    Previously selected index (for hysteresis / dampening).
     * @param lastSwitchMs  Timestamp of last switch (for time-based dampening).
     * @param currentTimeMs Current wall time in ms.
     * @return Selected index, or null if no valid connection.
     */
    fun select(
        conns: List<SrtlaConnection>,
        lastIdx: Int?,
        lastSwitchMs: Long,
        currentTimeMs: Long,
    ): Int?
}
