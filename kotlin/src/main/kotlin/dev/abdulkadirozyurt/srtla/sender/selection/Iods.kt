// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/selection/iods.rs
//
// IoDS (In-order Delivery Scheduling) reordering prevention.
// Ensures packets are scheduled so they arrive in order at the receiver,
// reducing SRT retransmissions caused by out-of-order delivery.
package dev.abdulkadirozyurt.srtla.sender.selection

/**
 * IoDS scheduling state.
 * Mirrors Rust `struct IodsFilter` in src/sender/selection/iods.rs.
 */
class IodsFilter {
    /** Last scheduled predicted arrival time. */
    private var lastArrival: Double = 0.0

    /**
     * Record that a packet was scheduled with the given predicted arrival time.
     * Mirrors Rust `IodsFilter::record_scheduled`.
     */
    fun recordScheduled(predictedArrival: Double) {
        if (predictedArrival > lastArrival) {
            lastArrival = predictedArrival
        }
    }

    /**
     * Filter candidate indices to only those maintaining monotonic ordering.
     * A candidate is valid if its predicted arrival time >= lastArrival.
     * Candidates for which [arrivalFn] returns null are excluded.
     * Mirrors Rust `IodsFilter::filter_valid`.
     */
    fun filterValid(
        indices: List<Int>,
        arrivalFn: (Int) -> Double?,
    ): List<Int> = indices.filter { idx ->
        val arrival = arrivalFn(idx) ?: return@filter false
        arrival >= lastArrival
    }

    /**
     * Reset the ordering state (e.g., after a long gap).
     * Mirrors Rust `IodsFilter::reset`.
     */
    fun reset() {
        lastArrival = 0.0
    }
}
