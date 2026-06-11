// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/connection/rtt.rs (inline tests)
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.connection.RttTracker
import dev.abdulkadirozyurt.srtla.testkit.*

fun registerRttTrackerTests() {

suite("RttTracker") {
    test("dual window adapts to handover") {
        val tracker = RttTracker()
        // Establish baseline at 50ms
        repeat(10) { tracker.updateEstimate(50L) }
        assertTrue(
            Math.abs(tracker.rttMinMs - 50.0) < 1.0,
            "baseline should be ~50ms, got ${tracker.rttMinMs}"
        )
        // Simulate cellular handover: RTT jumps to 120ms
        // Need to flush both windows + sample filter (15 + 100 = 115 samples)
        repeat(115) { tracker.updateEstimate(120L) }
        assertTrue(
            Math.abs(tracker.rttMinMs - 120.0) < 1.0,
            "baseline should adapt to ~120ms after handover, got ${tracker.rttMinMs}"
        )
    }

    test("dual window tracks minimum") {
        val tracker = RttTracker()
        tracker.updateEstimate(100L)
        tracker.updateEstimate(80L)
        tracker.updateEstimate(60L)
        tracker.updateEstimate(90L)
        tracker.updateEstimate(70L)
        assertTrue(
            Math.abs(tracker.rttMinMs - 60.0) < 1.0,
            "baseline should track minimum of 60ms, got ${tracker.rttMinMs}"
        )
    }

    test("reset clears windows") {
        val tracker = RttTracker()
        repeat(20) { tracker.updateEstimate(50L) }
        assertTrue(Math.abs(tracker.rttMinMs - 50.0) < 1.0)
        tracker.reset()
        assertEquals(200.0, tracker.rttMinMs)
        tracker.updateEstimate(80L)
        assertTrue(
            Math.abs(tracker.rttMinMs - 80.0) < 1.0,
            "after reset + new measurement, baseline should be 80ms, got ${tracker.rttMinMs}"
        )
    }

    test("fast window forgets old minimum after flushing") {
        val tracker = RttTracker()
        tracker.updateEstimate(20L)
        // Fill fast window (10 samples) with 100ms
        repeat(10) { tracker.updateEstimate(100L) }
        // Slow window still holds 20ms
        assertTrue(
            Math.abs(tracker.rttMinMs - 20.0) < 1.0,
            "slow window should still hold 20ms, got ${tracker.rttMinMs}"
        )
        // Flush both slow + sample filter
        repeat(115) { tracker.updateEstimate(100L) }
        assertTrue(
            Math.abs(tracker.rttMinMs - 100.0) < 1.0,
            "after both windows filled, baseline should be 100ms, got ${tracker.rttMinMs}"
        )
    }

    test("Kalman smooths RTT") {
        val tracker = RttTracker()
        repeat(50) { tracker.updateEstimate(50L) }
        assertTrue(
            Math.abs(tracker.estimatedRttMs - 50.0) < 1.0,
            "Kalman should converge to 50ms: ${tracker.estimatedRttMs}"
        )
        repeat(20) { tracker.updateEstimate(80L) }
        assertTrue(
            tracker.kalmanRtt.velocity > 0.0 || tracker.estimatedRttMs > 60.0,
            "should track rising RTT: est=${tracker.estimatedRttMs}, vel=${tracker.kalmanRtt.velocity}"
        )
    }

    test("isStable returns true for constant RTT") {
        val tracker = RttTracker()
        repeat(50) { tracker.updateEstimate(50L) }
        assertTrue(tracker.isStable())
    }
}

} // registerRttTrackerTests
