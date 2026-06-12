// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/ewma.rs
//
// Exponentially Weighted Moving Average filter.
// Smooths a noisy measurement series by weighting recent samples more heavily.
// Used for RTT change rate tracking and other time-series smoothing.
//
// The smoothing factor `alpha` controls responsiveness:
//   alpha near 1.0 → tracks input closely (low smoothing)
//   alpha near 0.0 → retains history (high smoothing)
package dev.abdulkadirozyurt.srtla.filter

/**
 * Exponentially Weighted Moving Average filter.
 * Mirrors Rust `struct Ewma` in src/ewma.rs.
 *
 * @param alpha Smoothing factor (0.0 < alpha ≤ 1.0).
 */
class Ewma(private val alpha: Double) {
    private var _value: Double = 0.0
    private var initialized: Boolean = false

    /** Current smoothed value. Returns 0.0 until first valid update. */
    val value: Double get() = _value

    /**
     * Feed a new measurement into the filter, updating the smoothed value.
     * NaN or infinite measurements are silently ignored to prevent poisoning
     * the smoothed value. Mirrors Rust `Ewma::update`.
     */
    fun update(measurement: Double) {
        if (measurement.isNaN() || measurement.isInfinite()) return
        if (!initialized) {
            _value = measurement
            initialized = true
        } else {
            _value = _value * (1.0 - alpha) + measurement * alpha
        }
    }

    /**
     * Reset the filter to its uninitialized state.
     * Mirrors Rust `Ewma::reset`.
     */
    fun reset() {
        _value = 0.0
        initialized = false
    }
}
