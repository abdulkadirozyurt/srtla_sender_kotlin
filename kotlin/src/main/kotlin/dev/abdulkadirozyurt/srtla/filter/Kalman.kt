// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/kalman.rs
//
// 2-state Kalman filter for RTT smoothing with trend detection.
// Tracks [value, velocity] to provide smooth RTT estimates that naturally
// capture trends without the lag of pure EWMA.
//
// All constants and formulas are birebir from the Rust source.
package dev.abdulkadirozyurt.srtla.filter

/**
 * Configuration for the Kalman filter.
 * Mirrors Rust `struct KalmanConfig` in src/kalman.rs.
 */
data class KalmanConfig(
    /** Process noise for the value state. */
    val qValue: Double,
    /** Process noise for the velocity state. */
    val qVelocity: Double,
    /** Measurement noise. */
    val r: Double,
) {
    companion object {
        /**
         * Default configuration tuned for RTT smoothing.
         * Mirrors Rust `KalmanConfig::for_rtt`.
         */
        fun forRtt(): KalmanConfig = KalmanConfig(qValue = 0.5, qVelocity = 0.1, r = 2.0)
    }
}

/**
 * 2-state Kalman filter tracking [value, velocity].
 * Mirrors Rust `struct KalmanFilter` in src/kalman.rs.
 *
 * State vector: x = value (e.g., smoothed RTT ms), v = velocity (rate of change per update).
 * Error covariance matrix P stored flat as [p00, p01, p10, p11].
 * State transition F = [[1,1],[0,1]].
 * Observation H = [1, 0].
 */
class KalmanFilter(private val config: KalmanConfig) {
    private var x: Double = 0.0          // estimated value
    private var v: Double = 0.0          // estimated velocity
    // P stored as [p00, p01, p10, p11]
    private var p00: Double = 0.0
    private var p01: Double = 0.0
    private var p10: Double = 0.0
    private var p11: Double = 0.0
    private var _initialized: Boolean = false

    val value: Double get() = x
    val velocity: Double get() = v
    val isInitialized: Boolean get() = _initialized

    /**
     * Feed a new measurement, running predict + update.
     * Mirrors Rust `KalmanFilter::update` in src/kalman.rs.
     * NaN / infinite measurements are silently ignored.
     */
    fun update(measurement: Double) {
        if (measurement.isNaN() || measurement.isInfinite()) return

        if (!_initialized) {
            x = measurement
            v = 0.0
            // Initial covariance: diagonal with R
            p00 = config.r; p01 = 0.0; p10 = 0.0; p11 = config.r
            _initialized = true
            return
        }

        // --- Predict ---
        // State transition: x_pred = x + v, v_pred = v
        val xPred = x + v
        val vPred = v

        // P_pred = F * P * F' + Q
        // F = [[1,1],[0,1]]
        // p00' = p00 + p10 + p01 + p11 + q_value
        // p01' = p01 + p11
        // p10' = p10 + p11
        // p11' = p11 + q_velocity
        val pp00 = p00 + p10 + p01 + p11 + config.qValue
        val pp01 = p01 + p11
        val pp10 = p10 + p11
        val pp11 = p11 + config.qVelocity

        // --- Update ---
        // H = [1, 0]
        val y = measurement - xPred   // innovation
        val s = pp00 + config.r       // innovation covariance

        if (Math.abs(s) < 1e-12) return

        val k0 = pp00 / s             // Kalman gain (value component)
        val k1 = pp10 / s             // Kalman gain (velocity component)

        x = xPred + k0 * y
        v = vPred + k1 * y

        // P = (I - K*H) * P_pred
        p00 = (1.0 - k0) * pp00
        p01 = (1.0 - k0) * pp01
        p10 = pp10 - k1 * pp00
        p11 = pp11 - k1 * pp01
    }

    /**
     * Reset the filter to uninitialized state.
     * Mirrors Rust `KalmanFilter::reset`.
     */
    fun reset() {
        x = 0.0; v = 0.0
        p00 = 0.0; p01 = 0.0; p10 = 0.0; p11 = 0.0
        _initialized = false
    }
}
