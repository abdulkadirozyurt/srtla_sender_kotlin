// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/connection/rtt.rs
//
// RTT measurement and tracking using a 2-state Kalman filter + EWMA for delta.
// Dual-window minimum RTT baseline: fast (~3s) and slow (~30s) sliding windows.
// 15-sample min-filter applied before feeding the baseline tracker.
package dev.abdulkadirozyurt.srtla.connection

import dev.abdulkadirozyurt.srtla.filter.Ewma
import dev.abdulkadirozyurt.srtla.filter.KalmanConfig
import dev.abdulkadirozyurt.srtla.filter.KalmanFilter

// src/connection/rtt.rs
private const val FAST_WINDOW_SAMPLES: Int = 10    // ~3s at 300ms keepalive interval
private const val SLOW_WINDOW_SAMPLES: Int = 100   // ~30s at 300ms keepalive interval
private const val RTT_SAMPLE_FILTER_SIZE: Int = 15

/**
 * RTT measurement and tracking.
 * Mirrors Rust `struct RttTracker` in src/connection/rtt.rs.
 *
 * Primary smooth estimator: 2-state Kalman filter [value, velocity].
 * Velocity = rate of change per update (positive = rising RTT / congestion building).
 */
class RttTracker {
    var lastKeepaliveSentMs: Long = 0L
    var waitingForKeepaliveResponse: Boolean = false
    var lastRttMeasurementMs: Long = 0L

    /** Kalman filter: primary smooth RTT with trend detection. */
    val kalmanRtt: KalmanFilter = KalmanFilter(KalmanConfig.forRtt())

    var rttJitterMs: Double = 0.0
    var prevRttMs: Double = 0.0
    /** Smoothed RTT change rate in ms/sample (alpha=0.2). */
    val rttAvgDelta: Ewma = Ewma(0.2)
    /** Dual-window minimum RTT baseline (ms). */
    var rttMinMs: Double = 200.0
    var estimatedRttMs: Double = 0.0

    // Sliding windows for min-RTT baseline
    private val rttMinFastWindow = ArrayDeque<Double>(FAST_WINDOW_SAMPLES + 1)
    private val rttMinSlowWindow = ArrayDeque<Double>(SLOW_WINDOW_SAMPLES + 1)
    // 15-sample min-filter applied before feeding dual-window baseline
    private val rttSampleFilter = ArrayDeque<Double>(RTT_SAMPLE_FILTER_SIZE + 1)

    /** Reset all RTT tracking state to initial values. */
    fun reset() {
        lastRttMeasurementMs = 0L
        kalmanRtt.reset()
        rttJitterMs = 0.0
        prevRttMs = 0.0
        rttAvgDelta.reset()
        rttMinMs = 200.0
        estimatedRttMs = 0.0
        lastKeepaliveSentMs = 0L
        waitingForKeepaliveResponse = false
        rttMinFastWindow.clear()
        rttMinSlowWindow.clear()
        rttSampleFilter.clear()
    }

    /**
     * Feed a new RTT measurement.
     * Mirrors Rust `RttTracker::update_estimate`.
     */
    fun updateEstimate(rttMs: Long) {
        val currentRtt = rttMs.toDouble()
        val nowMs = System.currentTimeMillis()

        // Min-RTT sample filter: smooth jitter before feeding baseline tracker
        rttSampleFilter.addLast(currentRtt)
        while (rttSampleFilter.size > RTT_SAMPLE_FILTER_SIZE) rttSampleFilter.removeFirst()
        val filteredRtt = rttSampleFilter.minOrNull() ?: currentRtt

        if (!kalmanRtt.isInitialized) {
            kalmanRtt.update(currentRtt)
            prevRttMs = currentRtt
            estimatedRttMs = currentRtt
            rttMinMs = filteredRtt
            rttMinFastWindow.addLast(filteredRtt)
            rttMinSlowWindow.addLast(filteredRtt)
            lastRttMeasurementMs = nowMs
            return
        }

        // Kalman filter: primary smooth RTT with trend detection
        kalmanRtt.update(currentRtt)

        // Track RTT change rate
        val deltaRtt = currentRtt - prevRttMs
        rttAvgDelta.update(deltaRtt)
        prevRttMs = currentRtt

        // Dual-window minimum RTT baseline
        rttMinFastWindow.addLast(filteredRtt)
        while (rttMinFastWindow.size > FAST_WINDOW_SAMPLES) rttMinFastWindow.removeFirst()
        rttMinSlowWindow.addLast(filteredRtt)
        while (rttMinSlowWindow.size > SLOW_WINDOW_SAMPLES) rttMinSlowWindow.removeFirst()

        val fastMin = rttMinFastWindow.minOrNull() ?: filteredRtt
        val slowMin = rttMinSlowWindow.minOrNull() ?: filteredRtt
        rttMinMs = minOf(fastMin, slowMin)

        // Track peak deviation with exponential decay
        rttJitterMs *= 0.99
        if (Math.abs(deltaRtt) > rttJitterMs) {
            rttJitterMs = Math.abs(deltaRtt)
        }

        estimatedRttMs = kalmanRtt.value
        lastRttMeasurementMs = nowMs
    }

    /** Returns true if RTT is stable (avg delta < 1.0 ms/sample). */
    fun isStable(): Boolean = Math.abs(rttAvgDelta.value) < 1.0

    /** Record that a keepalive was sent (to measure RTT on response). */
    fun recordKeepaliveSent() {
        lastKeepaliveSentMs = System.currentTimeMillis()
        waitingForKeepaliveResponse = true
    }

    /**
     * Process a keepalive response packet and update RTT estimate.
     * Returns the measured RTT in ms, or null if not applicable.
     * Mirrors Rust `RttTracker::handle_keepalive_response`.
     */
    fun handleKeepaliveResponse(data: ByteArray, label: String): Long? {
        if (!waitingForKeepaliveResponse) return null
        val ts = dev.abdulkadirozyurt.srtla.protocol.extractKeepaliveTimestamp(data) ?: run {
            waitingForKeepaliveResponse = false
            return null
        }
        val now = System.currentTimeMillis()
        val rtt = (now - ts).coerceAtLeast(0L)
        if (rtt <= 10_000L) {
            updateEstimate(rtt)
            waitingForKeepaliveResponse = false
            return rtt
        }
        waitingForKeepaliveResponse = false
        return null
    }

    /**
     * Whether we need to send a keepalive to measure RTT.
     * Mirrors Rust `RttTracker::needs_measurement`.
     */
    fun needsMeasurement(connected: Boolean, connectionEstablishedMs: Long): Boolean {
        if (connectionEstablishedMs == 0L) return false
        val now = System.currentTimeMillis()
        return connected
            && !waitingForKeepaliveResponse
            && (lastRttMeasurementMs == 0L || (now - lastRttMeasurementMs) > 3000L)
    }
}
