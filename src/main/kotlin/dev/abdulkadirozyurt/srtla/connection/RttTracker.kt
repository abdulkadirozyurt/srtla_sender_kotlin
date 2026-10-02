// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/connection/rtt.rs
package dev.abdulkadirozyurt.srtla.connection

import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.filter.Ewma
import dev.abdulkadirozyurt.srtla.filter.KalmanConfig
import dev.abdulkadirozyurt.srtla.filter.KalmanFilter
import dev.abdulkadirozyurt.srtla.protocol.extractKeepaliveTimestamp
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.rtt")

/** Fast sliding window (~3s at 300ms keepalive interval). */
internal const val FAST_WINDOW_SAMPLES: Int = 10
/** Slow sliding window (~30s at 300ms keepalive interval). */
internal const val SLOW_WINDOW_SAMPLES: Int = 100
/** Min-RTT sample filter length. */
internal const val RTT_SAMPLE_FILTER_SIZE: Int = 15

/**
 * Longest round trip we believe. Anything longer is a clock jump or a stale
 * packet-log entry, and would poison the estimator for minutes.
 */
private const val MAX_PLAUSIBLE_RTT_MS: Long = 10_000L

/** EWMA weight for the mean absolute successive difference (MASD) of RTT. */
private const val RTT_MASD_ALPHA: Double = 0.1

/** Queue-building trips when the floor gradient exceeds this many MASD units. */
private const val GRAD_TRIP_SIGMA: Double = 3.0

/** Floor on the queue-building trip threshold, as a fraction of min RTT. */
private const val GRAD_TRIP_FLOOR_FRACTION: Double = 0.05

/**
 * RTT measurement and tracking.
 *
 * A 2-state Kalman filter is the primary smooth estimator: `value` is the
 * smoothed RTT in ms and `velocity` is the trend in ms/sample (the filter has no
 * dt term, so the trend is per measurement, not per second).
 */
class RttTracker {
    var lastKeepaliveSentMs: Long = 0L
    var waitingForKeepaliveResponse: Boolean = false
    var lastRttMeasurementMs: Long = 0L
    val kalmanRtt: KalmanFilter = KalmanFilter(KalmanConfig.forRtt())
    var rttJitterMs: Double = 0.0
    var prevRttMs: Double = 0.0
    /** Smoothed RTT change rate in ms/sample (alpha 0.2). */
    val rttAvgDelta: Ewma = Ewma(0.2)
    /** Dual-window minimum RTT baseline: min(fast, slow). */
    var rttMinMs: Double = 200.0
    /** Minimum of the fast (~3s) window: the recent propagation floor. */
    var rttMinFastMs: Double = 200.0
    /** Minimum of the slow (~30s) window: the long-term floor. */
    var rttMinSlowMs: Double = 200.0
    /** Mean absolute successive difference of RTT (ms). */
    var rttMasdMs: Double = 0.0
    var estimatedRttMs: Double = 0.0

    private val rttMinFastWindow = ArrayDeque<Double>(FAST_WINDOW_SAMPLES + 1)
    private val rttMinSlowWindow = ArrayDeque<Double>(SLOW_WINDOW_SAMPLES + 1)
    private val rttSampleFilter = ArrayDeque<Double>(RTT_SAMPLE_FILTER_SIZE + 1)

    /** Reset all RTT state (used on reconnection). */
    fun reset() {
        lastRttMeasurementMs = 0L
        kalmanRtt.reset()
        rttJitterMs = 0.0
        prevRttMs = 0.0
        rttAvgDelta.reset()
        rttMinMs = 200.0
        rttMinFastMs = 200.0
        rttMinSlowMs = 200.0
        rttMasdMs = 0.0
        estimatedRttMs = 0.0
        lastKeepaliveSentMs = 0L
        waitingForKeepaliveResponse = false
        rttMinFastWindow.clear()
        rttMinSlowWindow.clear()
        rttSampleFilter.clear()
    }

    /**
     * Fold one round trip into the smoothed RTT, rejecting samples that cannot be
     * real. Returns the accepted sample in ms, or null.
     *
     * All three per-link RTT sources (SRT cumulative ACK, SRTLA per-packet ACK,
     * keepalive echo) funnel through here, so all apply the same guard and all
     * reach the estimator. `rtt == 0` is rejected as hard as an implausibly long
     * one: it would seed `rttMinMs` at zero.
     */
    fun recordRoundTrip(sentMs: Long, nowMs: Long): Long? {
        val rtt = nowMs.satSub(sentMs)
        if (rtt == 0L || rtt > MAX_PLAUSIBLE_RTT_MS) return null
        updateEstimate(rtt, nowMs)
        return rtt
    }

    fun updateEstimate(rttMs: Long, nowMs: Long) {
        val currentRtt = rttMs.toDouble()

        rttSampleFilter.addLast(currentRtt)
        while (rttSampleFilter.size > RTT_SAMPLE_FILTER_SIZE) rttSampleFilter.removeFirst()
        val filteredRtt = rttSampleFilter.fold(Double.MAX_VALUE) { a, b -> minOf(a, b) }

        if (!kalmanRtt.isInitialized) {
            kalmanRtt.update(currentRtt)
            prevRttMs = currentRtt
            estimatedRttMs = currentRtt
            rttMinMs = filteredRtt
            rttMinFastMs = filteredRtt
            rttMinSlowMs = filteredRtt
            rttMasdMs = 0.0
            rttMinFastWindow.addLast(filteredRtt)
            rttMinSlowWindow.addLast(filteredRtt)
            lastRttMeasurementMs = nowMs
            return
        }

        kalmanRtt.update(currentRtt)

        val deltaRtt = currentRtt - prevRttMs
        rttAvgDelta.update(deltaRtt)
        // A slow standing-queue ramp has small successive steps, so MASD stays
        // low even as the floor lifts: that asymmetry makes the queue-build
        // detector immune to jitter.
        rttMasdMs = rttMasdMs * (1.0 - RTT_MASD_ALPHA) + Math.abs(deltaRtt) * RTT_MASD_ALPHA
        prevRttMs = currentRtt

        rttMinFastWindow.addLast(filteredRtt)
        while (rttMinFastWindow.size > FAST_WINDOW_SAMPLES) rttMinFastWindow.removeFirst()
        rttMinSlowWindow.addLast(filteredRtt)
        while (rttMinSlowWindow.size > SLOW_WINDOW_SAMPLES) rttMinSlowWindow.removeFirst()
        val fastMin = rttMinFastWindow.fold(Double.MAX_VALUE) { a, b -> minOf(a, b) }
        val slowMin = rttMinSlowWindow.fold(Double.MAX_VALUE) { a, b -> minOf(a, b) }
        rttMinFastMs = fastMin
        rttMinSlowMs = slowMin
        rttMinMs = minOf(fastMin, slowMin)

        rttJitterMs *= 0.99
        if (Math.abs(deltaRtt) > rttJitterMs) rttJitterMs = Math.abs(deltaRtt)

        estimatedRttMs = kalmanRtt.value
        lastRttMeasurementMs = nowMs
    }

    fun isStable(): Boolean = Math.abs(rttAvgDelta.value) < 1.0

    /**
     * Jitter-immune delay gradient (ms): how far the recent floor has lifted
     * above the long-term floor. Clamped at zero.
     */
    fun rttGradientMs(): Double = maxOf(rttMinFastMs - rttMinSlowMs, 0.0)

    /**
     * True when the delay gradient indicates a standing queue forming rather
     * than jitter. False until the baseline is established.
     */
    fun queueBuildingSuspected(): Boolean {
        if (!kalmanRtt.isInitialized || !rttMinMs.isFinite()) return false
        val trip = maxOf(GRAD_TRIP_SIGMA * rttMasdMs, GRAD_TRIP_FLOOR_FRACTION * rttMinMs)
        return rttGradientMs() > trip
    }

    fun recordKeepaliveSent(nowMs: Long) {
        lastKeepaliveSentMs = nowMs
        waitingForKeepaliveResponse = true
    }

    /** Measure RTT from an echoed keepalive. Returns the accepted RTT or null. */
    fun handleKeepaliveResponse(data: ByteArray, len: Int, label: String, nowMs: Long): Long? {
        if (!waitingForKeepaliveResponse) return null
        val ts = extractKeepaliveTimestamp(data, len)
        if (ts != null) {
            val rtt = recordRoundTrip(ts, nowMs)
            if (rtt != null) {
                waitingForKeepaliveResponse = false
                log.fine {
                    "$label: RTT from keepalive: ${rtt}ms (kalman: ${"%.1f".format(kalmanRtt.value)}ms, " +
                        "velocity: ${"%.2f".format(kalmanRtt.velocity)}ms/sample, " +
                        "jitter: ${"%.1f".format(rttJitterMs)}ms)"
                }
                return rtt
            }
        }
        waitingForKeepaliveResponse = false
        return null
    }

    fun handleKeepaliveResponse(data: ByteArray, label: String, nowMs: Long): Long? =
        handleKeepaliveResponse(data, data.size, label, nowMs)

    fun needsMeasurement(connected: Boolean, connectionEstablishedMs: Long, nowMs: Long): Boolean {
        if (connectionEstablishedMs == 0L) return false
        return connected && !waitingForKeepaliveResponse &&
            (lastRttMeasurementMs == 0L || nowMs.satSub(lastRttMeasurementMs) > 3000L)
    }
}
