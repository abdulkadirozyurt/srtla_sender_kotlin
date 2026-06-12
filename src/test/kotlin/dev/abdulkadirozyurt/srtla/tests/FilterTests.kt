// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/ewma.rs (inline tests), src/kalman.rs (inline tests)
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.filter.*
import dev.abdulkadirozyurt.srtla.testkit.*

fun registerFilterTests() {

// ── Ewma tests (src/ewma.rs::tests) ──────────────────────────────────────────

suite("Ewma") {
    test("logic: first update initializes, subsequent blend") {
        val ewma = Ewma(0.5)
        ewma.update(10.0)
        assertTrue(Math.abs(ewma.value - 10.0) < 1e-9)
        // (10 * 0.5) + (20 * 0.5) = 15
        ewma.update(20.0)
        assertTrue(Math.abs(ewma.value - 15.0) < 1e-9)
        // (15 * 0.5) + (30 * 0.5) = 22.5
        ewma.update(30.0)
        assertTrue(Math.abs(ewma.value - 22.5) < 1e-9)
    }

    test("smoothing: alpha=0.1 retains history") {
        val ewma = Ewma(0.1)
        ewma.update(100.0)
        assertTrue(Math.abs(ewma.value - 100.0) < 1e-9)
        // value = 100 * 0.9 + 0 * 0.1 = 90
        ewma.update(0.0)
        assertTrue(Math.abs(ewma.value - 90.0) < 1e-9)
    }

    test("uninitialized value is 0.0") {
        val ewma = Ewma(0.5)
        assertTrue(Math.abs(ewma.value - 0.0) < 1e-9)
    }

    test("alpha=1.0 follows input exactly") {
        val ewma = Ewma(1.0)
        ewma.update(10.0)
        assertTrue(Math.abs(ewma.value - 10.0) < 1e-9)
        ewma.update(50.0)
        assertTrue(Math.abs(ewma.value - 50.0) < 1e-9)
    }

    test("alpha near zero retains history") {
        val ewma = Ewma(0.001)
        ewma.update(100.0)
        assertTrue(Math.abs(ewma.value - 100.0) < 1e-9)
        ewma.update(0.0)
        assertTrue(Math.abs(ewma.value - 99.9) < 0.01)
    }

    test("negative values") {
        val ewma = Ewma(0.5)
        ewma.update(-10.0)
        assertTrue(Math.abs(ewma.value - (-10.0)) < 1e-9)
        ewma.update(10.0)
        assertTrue(Math.abs(ewma.value - 0.0) < 1e-9)
    }

    test("converges to constant") {
        val ewma = Ewma(0.5)
        repeat(100) { ewma.update(42.0) }
        assertTrue(Math.abs(ewma.value - 42.0) < 0.001)
    }

    test("NaN guard: NaN ignored, value preserved") {
        val ewma = Ewma(0.5)
        ewma.update(10.0)
        ewma.update(Double.NaN)
        assertTrue(Math.abs(ewma.value - 10.0) < 1e-9)
        ewma.update(Double.POSITIVE_INFINITY)
        assertTrue(Math.abs(ewma.value - 10.0) < 1e-9)
        ewma.update(Double.NEGATIVE_INFINITY)
        assertTrue(Math.abs(ewma.value - 10.0) < 1e-9)
        ewma.update(20.0)
        assertTrue(Math.abs(ewma.value - 15.0) < 1e-9)
    }

    test("NaN on first sample: remains at zero, then initializes on valid") {
        val ewma = Ewma(0.5)
        ewma.update(Double.NaN)
        assertTrue(Math.abs(ewma.value - 0.0) < 1e-9)
        ewma.update(42.0)
        assertTrue(Math.abs(ewma.value - 42.0) < 1e-9)
    }

    test("reset clears state") {
        val ewma = Ewma(0.5)
        ewma.update(100.0)
        ewma.reset()
        assertTrue(Math.abs(ewma.value - 0.0) < 1e-9)
        ewma.update(50.0)
        assertTrue(Math.abs(ewma.value - 50.0) < 1e-9)
    }
}

// ── KalmanFilter tests (src/kalman.rs::tests) ────────────────────────────────

suite("KalmanFilter") {
    test("initializes on first measurement") {
        val kf = KalmanFilter(KalmanConfig.forRtt())
        assertFalse(kf.isInitialized)
        kf.update(50.0)
        assertTrue(kf.isInitialized)
        assertTrue(Math.abs(kf.value - 50.0) < 1e-9)
        assertTrue(Math.abs(kf.velocity - 0.0) < 1e-9)
    }

    test("tracks constant signal") {
        val kf = KalmanFilter(KalmanConfig.forRtt())
        repeat(100) { kf.update(42.0) }
        assertTrue(
            Math.abs(kf.value - 42.0) < 0.1,
            "should converge to 42.0, got ${kf.value}"
        )
        assertTrue(Math.abs(kf.velocity) < 0.1)
    }

    test("tracks rising trend") {
        val kf = KalmanFilter(KalmanConfig.forRtt())
        for (i in 0 until 50) kf.update(50.0 + i.toDouble())
        assertTrue(kf.velocity > 0.5, "velocity should be positive: ${kf.velocity}")
        assertTrue(kf.value > 90.0, "value should track rising input: ${kf.value}")
    }

    test("smooths noisy signal") {
        val kf = KalmanFilter(KalmanConfig.forRtt())
        for (i in 0 until 100) {
            val noise = if (i % 2 == 0) 10.0 else -10.0
            kf.update(50.0 + noise)
        }
        assertTrue(
            Math.abs(kf.value - 50.0) < 5.0,
            "should smooth noise: ${kf.value}"
        )
    }

    test("NaN guard") {
        val kf = KalmanFilter(KalmanConfig.forRtt())
        kf.update(50.0)
        kf.update(Double.NaN)
        assertTrue(Math.abs(kf.value - 50.0) < 1.0)
    }

    test("reset") {
        val kf = KalmanFilter(KalmanConfig.forRtt())
        kf.update(50.0)
        assertTrue(kf.isInitialized)
        kf.reset()
        assertFalse(kf.isInitialized)
        assertTrue(Math.abs(kf.value - 0.0) < 1e-9)
    }
}

} // registerFilterTests
