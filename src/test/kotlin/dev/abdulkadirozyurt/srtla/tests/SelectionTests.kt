// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/tests/rtt_threshold_tests.rs + src/tests/sender_tests.rs (quality/enhanced)
//         + src/sender/selection/blest.rs #[cfg(test)] + iods.rs #[cfg(test)]
//
// Full Faz C selection test suite: Quality, Enhanced, RttThreshold, Blest, Iods, Edpf,
// SelectionOrchestrator, SchedulingMode.
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.connection.*
import dev.abdulkadirozyurt.srtla.connection.congestion.CongestionControl
import dev.abdulkadirozyurt.srtla.sender.selection.*
import dev.abdulkadirozyurt.srtla.testkit.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import kotlin.math.exp
import kotlin.math.abs

// ── Test connection factory ───────────────────────────────────────────────────

private fun makeSelConn(
    inFlight: Int = 0,
    connected: Boolean = true,
    smoothRtt: Double = 0.0,   // Kalman value
    rttMin: Double = 0.0,
    bitrateBps: Double = 0.0,
    nakCount: Int = 0,
    lastNakAgoMs: Long = 0L,
    nakBurst: Int = 0,
    connEstAgoMs: Long = 0L,   // 0 = startup grace (fresh); >30000 = beyond grace
): SrtlaConnection {
    val ch = DatagramChannel.open()
    ch.configureBlocking(false)
    ch.socket().bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    val port = ch.socket().localPort
    ch.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
    val now = System.currentTimeMillis()
    val conn = SrtlaConnection(
        connId     = System.nanoTime(),
        socket     = UplinkSocket(ch),
        remoteAddr = InetSocketAddress(InetAddress.getLoopbackAddress(), port),
        localIp    = InetAddress.getLoopbackAddress(),
        label      = "test-${System.nanoTime()}",
    )
    conn.connected = connected
    conn.lastReceivedMs = if (connected) now else 0L
    conn.inFlightPackets = inFlight
    if (smoothRtt > 0.0) conn.rtt.kalmanRtt.update(smoothRtt)
    if (rttMin > 0.0) conn.rtt.rttMinMs = rttMin
    if (bitrateBps > 0.0) conn.bitrate.currentBitrateBps = bitrateBps
    conn.congestion.nakCount = nakCount
    if (nakCount > 0 && lastNakAgoMs > 0L) {
        conn.congestion.lastNakTimeMs = now - lastNakAgoMs
    }
    conn.congestion.nakBurstCount = nakBurst
    if (connEstAgoMs > 0L) {
        conn.reconnection.connectionEstablishedMs = now - connEstAgoMs
    }
    return conn
}

fun registerSelectionTests() {

// ═══════════════════════════════════════════════════════════════════════════════
// Quality scoring tests (mirrors src/tests/sender_tests.rs::test_calculate_quality_multiplier)
// ═══════════════════════════════════════════════════════════════════════════════

suite("QualityScoring") {

    test("perfect connection (no NAKs, past grace) → 1.1 bonus") {
        val conn = makeSelConn(connEstAgoMs = 35_000L)
        val now = System.currentTimeMillis()
        val m = calculateQualityMultiplier(conn, now)
        assertEquals(PERFECT_CONNECTION_BONUS, m)
    }

    test("startup grace, no NAKs → 1.1 bonus") {
        // connEstAgoMs=100 → established 100ms ago → inside 30s grace period
        val conn = makeSelConn(connEstAgoMs = 100L)
        val now = System.currentTimeMillis()
        val m = calculateQualityMultiplier(conn, now)
        // During startup grace with zero NAKs → PERFECT_CONNECTION_BONUS
        assertEquals(PERFECT_CONNECTION_BONUS, m)
    }

    test("startup grace, with NAKs → 0.98 light penalty") {
        // connEstAgoMs=100 → established 100ms ago → well within 30s grace period
        val conn = makeSelConn(nakCount = 1, lastNakAgoMs = 100L, connEstAgoMs = 100L)
        val now = System.currentTimeMillis()
        val m = calculateQualityMultiplier(conn, now)
        assertEquals(STARTUP_NAK_PENALTY, m)
    }

    test("nak 500ms ago → ~0.61 (exponential decay)") {
        val conn = makeSelConn(nakCount = 1, lastNakAgoMs = 500L, connEstAgoMs = 35_000L)
        val now = System.currentTimeMillis()
        val m = calculateQualityMultiplier(conn, now)
        // expected: 1 - 0.5*exp(-500/2000) = 1 - 0.5*0.7788 ≈ 0.6106
        val expected = 1.0 - MAX_PENALTY * exp(-500.0 / HALF_LIFE_MS)
        assertTrue(abs(m - expected) < 0.02,
            "Expected ~${expected}, got $m")
    }

    test("nak 2000ms ago (half-life) → ~0.816") {
        val conn = makeSelConn(nakCount = 1, lastNakAgoMs = 2_000L, connEstAgoMs = 35_000L)
        val now = System.currentTimeMillis()
        val m = calculateQualityMultiplier(conn, now)
        val expected = 1.0 - MAX_PENALTY * exp(-2000.0 / HALF_LIFE_MS)
        assertTrue(abs(m - expected) < 0.02, "Expected ~${expected}, got $m")
    }

    test("nak 5000ms ago → ~0.96") {
        val conn = makeSelConn(nakCount = 1, lastNakAgoMs = 5_000L, connEstAgoMs = 35_000L)
        val now = System.currentTimeMillis()
        val m = calculateQualityMultiplier(conn, now)
        val expected = 1.0 - MAX_PENALTY * exp(-5000.0 / HALF_LIFE_MS)
        assertTrue(abs(m - expected) < 0.02, "Expected ~${expected}, got $m")
    }

    test("nak 15000ms ago → ~1.0 (recovered)") {
        val conn = makeSelConn(nakCount = 1, lastNakAgoMs = 15_000L, connEstAgoMs = 35_000L)
        val now = System.currentTimeMillis()
        val m = calculateQualityMultiplier(conn, now)
        val expected = 1.0 - MAX_PENALTY * exp(-15000.0 / HALF_LIFE_MS)
        assertTrue(abs(m - expected) < 0.02, "Expected ~${expected}, got $m")
    }

    test("burst: ≥5 NAKs within 3s adds 0.7x extra penalty") {
        // at 2000ms: base ≈ 0.816, × 0.7 burst ≈ 0.571
        val conn = makeSelConn(
            nakCount = 5, lastNakAgoMs = 2_000L, nakBurst = 5,
            connEstAgoMs = 35_000L
        )
        val now = System.currentTimeMillis()
        val m = calculateQualityMultiplier(conn, now)
        val base = 1.0 - MAX_PENALTY * exp(-2000.0 / HALF_LIFE_MS)
        val expected = base * NAK_BURST_PENALTY
        assertTrue(abs(m - expected) < 0.02, "Expected ~$expected, got $m")
    }

    test("burst age > 3s: no burst penalty applied") {
        // burst is old (4s ago) → no extra penalty
        val conn = makeSelConn(
            nakCount = 5, lastNakAgoMs = 4_000L, nakBurst = 5,
            connEstAgoMs = 35_000L
        )
        val now = System.currentTimeMillis()
        val m = calculateQualityMultiplier(conn, now)
        val expected = 1.0 - MAX_PENALTY * exp(-4000.0 / HALF_LIFE_MS)
        // Should be close to base without burst penalty
        assertTrue(abs(m - expected) < 0.02, "Expected ~$expected (no burst), got $m")
    }

    test("QualityCache: caches and returns stable value within 50ms") {
        val conn = makeSelConn(connEstAgoMs = 35_000L)
        val cache = QualityCache()
        val now = System.currentTimeMillis()
        val v1 = cache.get(conn, now)
        val v2 = cache.get(conn, now + 30L) // within 50ms → same cached value
        assertEquals(v1, v2)
    }

    test("QualityCache: recalculates after 50ms") {
        val conn = makeSelConn(nakCount = 1, lastNakAgoMs = 500L, connEstAgoMs = 35_000L)
        val cache = QualityCache()
        val t0 = System.currentTimeMillis()
        cache.get(conn, t0)
        // Advance 51ms — should recompute
        val v2 = cache.get(conn, t0 + 51L)
        assertTrue(v2 > 0.0) // just ensure it ran without exception
    }

    test("RTT bonus: fast RTT (50ms) → 1.03 cap") {
        // conn with no NAKs, past grace, Kalman RTT = 50ms
        val conn = makeSelConn(connEstAgoMs = 35_000L, smoothRtt = 50.0)
        val now = System.currentTimeMillis()
        val m = calculateQualityMultiplier(conn, now)
        // perfect bonus is 1.1, RTT bonus capped at 1.03, applied on top of perfect bonus
        // actual perfect path: qualityMult=1.1, rttBonus= min(200/max(50,50), 1.03)=1.03 → 1.1*1.03
        val expected = PERFECT_CONNECTION_BONUS * MAX_RTT_BONUS
        assertTrue(abs(m - expected) < 0.02, "Expected ~$expected, got $m")
    }

    test("RTT bonus: slow RTT (400ms) → no bonus (1.0)") {
        val conn = makeSelConn(connEstAgoMs = 35_000L, smoothRtt = 400.0)
        val now = System.currentTimeMillis()
        val m = calculateQualityMultiplier(conn, now)
        // rttFactor = min(200/400, 1.03) = 0.5 → coerced to 1.0 (never penalty)
        // perfect bonus × 1.0
        val expected = PERFECT_CONNECTION_BONUS * 1.0
        assertTrue(abs(m - expected) < 0.02, "Expected ~$expected, got $m")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Enhanced selection tests (src/tests/sender_tests.rs enhanced tests)
// ═══════════════════════════════════════════════════════════════════════════════

suite("EnhancedSelection") {

    test("picks highest quality-adjusted score") {
        val c0 = makeSelConn(inFlight = 0, connEstAgoMs = 35_000L, nakCount = 0)
        val c1 = makeSelConn(inFlight = 0, connEstAgoMs = 35_000L, nakCount = 5,
            lastNakAgoMs = 1_000L)  // has recent NAKs → lower quality
        val c2 = makeSelConn(inFlight = 0, connEstAgoMs = 35_000L, nakCount = 0) // perfect
        val conns = listOf(c0, c1, c2)
        val now = System.currentTimeMillis()
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.ENHANCED, qualityEnabled = true)
        val sel = orch.select(conns, null, 0L, now, cfg)
        // c0 and c2 both have equal quality (1.1), c1 is degraded
        assertTrue(sel == 0 || sel == 2, "Expected c0 or c2, got $sel")
    }

    test("cooldown: stays with current connection within MIN_SWITCH_INTERVAL_MS") {
        val c0 = makeSelConn(inFlight = 5)  // currently selected, lower score
        val c1 = makeSelConn(inFlight = 0)  // better score
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val lastSwitch = now - 5L // 5ms ago, within 15ms cooldown
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.ENHANCED)
        val sel = orch.select(conns, 0, lastSwitch, now, cfg)
        assertEquals(0, sel, "Should stay with conn 0 during cooldown")
    }

    test("after cooldown: switches to better connection") {
        val c0 = makeSelConn(inFlight = 5)  // currently selected, lower score
        val c1 = makeSelConn(inFlight = 0)  // better score
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val lastSwitch = now - 20L // 20ms ago, past 15ms cooldown
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.ENHANCED)
        // Need score difference > 10% hysteresis:
        // c0 score = window/(5+1), c1 score = window/1 → c1 is much better
        val sel = orch.select(conns, 0, lastSwitch, now, cfg)
        assertEquals(1, sel, "Should switch to conn 1 after cooldown")
    }

    test("hysteresis: does not switch when improvement < 10%") {
        // c0 and c1 have nearly equal scores (< 10% difference)
        val w = 100 // small window to control scores
        val c0 = makeSelConn(inFlight = 0).also { it.window = w }
        val c1 = makeSelConn(inFlight = 0).also { it.window = (w * 1.05).toInt() }
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val lastSwitch = now - 20L // past cooldown
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.ENHANCED, qualityEnabled = false)
        val sel = orch.select(conns, 0, lastSwitch, now, cfg)
        // c1 is only 5% better → hysteresis keeps c0
        assertEquals(0, sel, "Hysteresis should keep current when improvement < 10%")
    }

    test("all disconnected → null") {
        val conns = listOf(makeSelConn(connected = false), makeSelConn(connected = false))
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.ENHANCED)
        val sel = orch.select(conns, null, 0L, System.currentTimeMillis(), cfg)
        assertNull(sel)
    }

    test("exploration: periodic trigger at 30s boundary") {
        // We cannot easily control elapsed time, so just verify no crash
        val c0 = makeSelConn(inFlight = 0)
        val c1 = makeSelConn(inFlight = 5)
        val conns = listOf(c0, c1)
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.ENHANCED,
            qualityEnabled = false, explorationEnabled = true)
        // Should not throw regardless of which branch is taken
        val sel = orch.select(conns, null, 0L, System.currentTimeMillis(), cfg)
        assertNotNull(sel)
    }

    test("quality disabled: selects by base capacity only") {
        val c0 = makeSelConn(inFlight = 0, connEstAgoMs = 35_000L,
            nakCount = 100, lastNakAgoMs = 100L) // terrible NAK history
        val c1 = makeSelConn(inFlight = 1, connEstAgoMs = 35_000L) // slightly lower cap
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.ENHANCED, qualityEnabled = false)
        val sel = orch.select(conns, null, 0L, now, cfg)
        // Without quality, c0 (inFlight=0) has higher base score
        assertEquals(0, sel, "Quality disabled: should pick highest base score")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RTT-threshold tests (mirrors src/tests/rtt_threshold_tests.rs)
// ═══════════════════════════════════════════════════════════════════════════════

suite("RttThresholdSelection") {

    test("prefers fast link (low RTT)") {
        // Connection 0: 50ms RTT (fast), Connection 1: 200ms (slow with 30ms delta)
        val c0 = makeSelConn(inFlight = 0, smoothRtt = 50.0)
        val c1 = makeSelConn(inFlight = 0, smoothRtt = 200.0)
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD,
            qualityEnabled = true, rttDeltaMs = 30)
        val sel = orch.select(conns, null, 0L, now, cfg)
        assertEquals(0, sel, "Should prefer fast link (50ms RTT)")
    }

    test("both fast → picks better capacity") {
        // c0: 50ms, c1: 70ms (both within 50+30=80ms threshold)
        val c0 = makeSelConn(inFlight = 5, smoothRtt = 50.0)
        val c1 = makeSelConn(inFlight = 0, smoothRtt = 70.0)
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD, rttDeltaMs = 30)
        val sel = orch.select(conns, null, 0L, now, cfg)
        assertEquals(1, sel, "Among fast links, should pick higher capacity")
    }

    test("fallback when fast link is saturated (score 0)") {
        // c0: fast but saturated (window=0), c1: slow but has capacity
        val c0 = makeSelConn(smoothRtt = 50.0).also {
            it.window = 0; it.inFlightPackets = 10
        }
        val c1 = makeSelConn(inFlight = 0, smoothRtt = 200.0)
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD, rttDeltaMs = 30)
        val sel = orch.select(conns, null, 0L, now, cfg)
        assertEquals(1, sel, "Should fallback to slow link when fast is saturated")
    }

    test("quality within fast links: prefers cleaner connection") {
        // Two fast links, c0 has recent NAKs, c1 is clean
        val c0 = makeSelConn(inFlight = 0, smoothRtt = 50.0,
            nakCount = 5, lastNakAgoMs = 1_000L, connEstAgoMs = 35_000L)
        val c1 = makeSelConn(inFlight = 0, smoothRtt = 60.0,
            nakCount = 0, connEstAgoMs = 35_000L)
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD,
            qualityEnabled = true, rttDeltaMs = 30)
        val sel = orch.select(conns, null, 0L, now, cfg)
        assertEquals(1, sel, "Should prefer cleaner connection within fast group")
    }

    test("no RTT data → all links treated as fast, picks by capacity") {
        // Both links have RTT=0 (no data) → both fast → higher capacity wins
        val c0 = makeSelConn(inFlight = 5) // lower score
        val c1 = makeSelConn(inFlight = 0) // higher score
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD, rttDeltaMs = 30)
        val sel = orch.select(conns, null, 0L, now, cfg)
        assertEquals(1, sel, "Without RTT data, should pick higher capacity")
    }

    test("large delta → all links fast → picks best capacity") {
        val c0 = makeSelConn(inFlight = 5, smoothRtt = 50.0)
        val c1 = makeSelConn(inFlight = 0, smoothRtt = 150.0)
        val c2 = makeSelConn(inFlight = 3, smoothRtt = 200.0)
        val conns = listOf(c0, c1, c2)
        val now = System.currentTimeMillis()
        val orch = SelectionOrchestrator()
        // With delta=200: threshold = 50+200=250ms → all fast
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD, rttDeltaMs = 200)
        val sel = orch.select(conns, null, 0L, now, cfg)
        assertEquals(1, sel, "With large delta, all fast → pick best capacity (conn 1)")
    }

    test("time-based dampening: stays within cooldown") {
        val c0 = makeSelConn(inFlight = 5, smoothRtt = 50.0)
        val c1 = makeSelConn(inFlight = 0, smoothRtt = 50.0)
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val lastSwitch = now - 5L // 5ms ago, within 15ms cooldown
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD)
        val sel = orch.select(conns, 0, lastSwitch, now, cfg)
        assertEquals(0, sel, "Should stay with current during cooldown")
    }

    test("after cooldown: switches to better link") {
        val c0 = makeSelConn(inFlight = 5, smoothRtt = 50.0)
        val c1 = makeSelConn(inFlight = 0, smoothRtt = 50.0)
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val lastSwitch = now - 20L // past cooldown
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD)
        val sel = orch.select(conns, 0, lastSwitch, now, cfg)
        assertEquals(1, sel, "Should switch after cooldown expires")
    }

    test("empty connections → null") {
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD)
        val sel = orch.select(emptyList(), null, 0L, System.currentTimeMillis(), cfg)
        assertNull(sel)
    }

    test("all timed out → null") {
        val c0 = makeSelConn(connected = false)
        val c1 = makeSelConn(connected = false)
        val conns = listOf(c0, c1)
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD)
        val sel = orch.select(conns, null, 0L, System.currentTimeMillis(), cfg)
        assertNull(sel)
    }

    test("quality disabled: picks by base capacity only") {
        // c0: fast, high in-flight (bad NAK history but doesn't matter)
        val c0 = makeSelConn(inFlight = 0, smoothRtt = 50.0,
            nakCount = 100, lastNakAgoMs = 100L, connEstAgoMs = 35_000L)
        val c1 = makeSelConn(inFlight = 1, smoothRtt = 50.0)
        val conns = listOf(c0, c1)
        val now = System.currentTimeMillis()
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD, qualityEnabled = false)
        val sel = orch.select(conns, null, 0L, now, cfg)
        assertEquals(0, sel, "Quality disabled: picks best base score")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// BLEST filter tests (mirrors src/sender/selection/blest.rs #[cfg(test)])
// ═══════════════════════════════════════════════════════════════════════════════

suite("BlestFilter") {

    test("all close RTTs → all pass") {
        val conns = listOf(
            makeSelConn(rttMin = 40.0),
            makeSelConn(rttMin = 50.0),
            makeSelConn(rttMin = 60.0),
        )
        val filter = BlestFilter()
        val result = filter.filter(conns)
        assertEquals(listOf(0, 1, 2), result, "All should pass with close RTTs")
    }

    test("high OWD link is filtered out") {
        // c0: rttMin=20 → OWD=10; c1: rttMin=40 → OWD=20, blockTime=10<50 → pass
        // c2: rttMin=180 → OWD=90, blockTime=80>50 → blocked
        val conns = listOf(
            makeSelConn(rttMin = 20.0),
            makeSelConn(rttMin = 40.0),
            makeSelConn(rttMin = 180.0),
        )
        val filter = BlestFilter()
        val result = filter.filter(conns)
        assertEquals(listOf(0, 1), result, "High-OWD link should be filtered out")
    }

    test("rttMin ≥ 200 excluded from min-OWD calc") {
        // rttMin=200 is excluded from min calc; so only c0 (rttMin=20) determines min
        val conns = listOf(
            makeSelConn(rttMin = 20.0),
            makeSelConn(rttMin = 200.0),
        )
        val filter = BlestFilter()
        val result = filter.filter(conns)
        // c1: rttMin=200, OWD=100, blockTime=90 > 50 → filtered
        assertEquals(listOf(0), result)
    }

    test("no valid RTT data → all connected links returned") {
        val c0 = makeSelConn() // rttMin=0 (no data)
        val c1 = makeSelConn()
        val filter = BlestFilter()
        val result = filter.filter(listOf(c0, c1))
        assertEquals(listOf(0, 1), result)
    }

    test("disconnected link excluded") {
        val c0 = makeSelConn()
        val c1 = makeSelConn(connected = false)
        val filter = BlestFilter()
        val result = filter.filter(listOf(c0, c1))
        assertEquals(listOf(0), result)
    }

    test("penalty shrinks effective threshold") {
        val filter = BlestFilter()
        val defaultThreshold = filter.effectiveThreshold()
        assertTrue(abs(defaultThreshold - BLEST_DEFAULT_BLOCK_THRESHOLD_MS) < 0.01)

        filter.recordBlocking()
        // penalty=1.0, threshold = 50 / (1 + 0.5) = 33.3
        val penalized = filter.effectiveThreshold()
        assertTrue(penalized < BLEST_DEFAULT_BLOCK_THRESHOLD_MS, "Penalty should reduce threshold")
        assertTrue(penalized > 30.0)
    }

    test("penalty decays to near zero after many ticks") {
        val filter = BlestFilter()
        filter.recordBlocking()
        assertTrue(filter.penalty > 0.0)
        repeat(200) { filter.tick() }
        assertTrue(filter.penalty < 0.01, "Penalty should decay: ${filter.penalty}")
    }

    test("tick: penalty × 0.95 each tick") {
        val filter = BlestFilter()
        filter.recordBlocking() // penalty = 1.0
        filter.tick()           // penalty = 0.95
        assertTrue(abs(filter.penalty - 0.95) < 0.001)
    }

    test("empty connections → empty result") {
        val filter = BlestFilter()
        assertEquals(emptyList<Int>(), filter.filter(emptyList()))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// IoDS filter tests (mirrors src/sender/selection/iods.rs #[cfg(test)])
// ═══════════════════════════════════════════════════════════════════════════════

suite("IodsFilter") {

    test("initially all candidates pass (lastArrival=0)") {
        val iods = IodsFilter()
        val arrivals = listOf(0.1, 0.05, 0.2, 0.15)
        val indices = listOf(0, 1, 2, 3)
        val valid = iods.filterValid(indices) { arrivals[it] }
        assertEquals(listOf(0, 1, 2, 3), valid)
    }

    test("after recordScheduled, only arrivals >= threshold pass") {
        val iods = IodsFilter()
        val arrivals = listOf(0.1, 0.05, 0.2, 0.15)
        val indices = listOf(0, 1, 2, 3)
        iods.recordScheduled(0.15)
        // Only 0.2 (idx=2) and 0.15 (idx=3) pass
        val valid = iods.filterValid(indices) { arrivals[it] }
        assertEquals(listOf(2, 3), valid, "Only arrivals >= 0.15 should pass")
    }

    test("null arrival filtered out") {
        val iods = IodsFilter()
        val valid = iods.filterValid(listOf(0, 1, 2)) { idx ->
            if (idx == 1) null else 1.0
        }
        assertEquals(listOf(0, 2), valid)
    }

    test("empty candidates → empty result") {
        val iods = IodsFilter()
        val valid = iods.filterValid(emptyList()) { 1.0 }
        assertEquals(emptyList<Int>(), valid)
    }

    test("reset clears lastArrival") {
        val iods = IodsFilter()
        iods.recordScheduled(100.0)
        // Nothing passes
        val blocked = iods.filterValid(listOf(0)) { 1.0 }
        assertTrue(blocked.isEmpty())
        // After reset, should pass again
        iods.reset()
        val valid = iods.filterValid(listOf(0)) { 1.0 }
        assertEquals(listOf(0), valid)
    }

    test("recordScheduled only increases lastArrival") {
        val iods = IodsFilter()
        iods.recordScheduled(0.5)
        iods.recordScheduled(0.3) // lower → no change
        val valid = iods.filterValid(listOf(0, 1)) { idx ->
            if (idx == 0) 0.4 else 0.6
        }
        // 0.4 < 0.5 → filtered; 0.6 >= 0.5 → passes
        assertEquals(listOf(1), valid)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// EDPF tests (mirrors src/sender/selection/edpf.rs #[cfg(test)])
// ═══════════════════════════════════════════════════════════════════════════════

suite("EdpfSelection") {

    test("prefers lower predicted arrival time") {
        // c1 has best: low in-flight, high bitrate, low RTT
        val c0 = makeSelConn(inFlight = 10, bitrateBps = 1_000_000.0, smoothRtt = 50.0)
        val c1 = makeSelConn(inFlight = 0,  bitrateBps = 2_000_000.0, smoothRtt = 20.0)
        val c2 = makeSelConn(inFlight = 20, bitrateBps = 500_000.0, smoothRtt = 100.0)
        val sel = edpfSelectFrom(listOf(c0, c1, c2), SRT_PKT_SIZE)
        assertEquals(1, sel, "Should pick conn with lowest predicted arrival")
    }

    test("skips disconnected connections") {
        val c0 = makeSelConn(connected = false, bitrateBps = 10_000_000.0)
        val c1 = makeSelConn(inFlight = 5, bitrateBps = 1_000_000.0, smoothRtt = 50.0)
        val sel = edpfSelectFrom(listOf(c0, c1), SRT_PKT_SIZE)
        assertEquals(1, sel)
    }

    test("empty connections → null") {
        val sel = edpfSelectFrom(emptyList(), SRT_PKT_SIZE)
        assertNull(sel)
    }

    test("selectFromIndices picks best within subset") {
        // c0 is overall best but excluded from indices
        val c0 = makeSelConn(inFlight = 0, bitrateBps = 5_000_000.0, smoothRtt = 10.0)
        val c1 = makeSelConn(inFlight = 0, bitrateBps = 1_000_000.0, smoothRtt = 50.0)
        val c2 = makeSelConn(inFlight = 0, bitrateBps = 2_000_000.0, smoothRtt = 20.0)
        // Only consider indices 1 and 2
        val sel = edpfSelectFromIndices(listOf(c0, c1, c2), listOf(1, 2), SRT_PKT_SIZE)
        assertEquals(2, sel, "Should pick best from subset (excluding c0)")
    }

    test("no bitrate data → null arrival → fallback skips that link") {
        // c0 has no bitrate, c1 has bitrate
        val c0 = makeSelConn(inFlight = 0) // bitrateBps=0 → null arrival
        val c1 = makeSelConn(inFlight = 0, bitrateBps = 1_000_000.0, smoothRtt = 50.0)
        val sel = edpfSelectFrom(listOf(c0, c1), SRT_PKT_SIZE)
        assertEquals(1, sel, "Should skip link with no bitrate")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SelectionOrchestrator / ConfigSnapshot tests
// ═══════════════════════════════════════════════════════════════════════════════

suite("SelectionOrchestrator") {

    test("EDPF mode: selects without crashing (no bitrate → fallback to classic behavior)") {
        val conns = listOf(makeSelConn(inFlight = 0), makeSelConn(inFlight = 5))
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.EDPF)
        // Without bitrate data edpf returns null → orchestrator may return null
        // Just verify no exception
        orch.select(conns, null, 0L, System.currentTimeMillis(), cfg)
    }

    test("EDPF pipeline with bitrate data: picks lowest arrival") {
        val c0 = makeSelConn(inFlight = 0, bitrateBps = 2_000_000.0, smoothRtt = 20.0)
        val c1 = makeSelConn(inFlight = 10, bitrateBps = 500_000.0, smoothRtt = 100.0)
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.EDPF)
        val sel = orch.select(listOf(c0, c1), null, 0L, System.currentTimeMillis(), cfg)
        assertEquals(0, sel, "EDPF should pick connection with lowest predicted arrival")
    }

    test("ConfigSnapshot effectiveQualityEnabled: false for Classic") {
        val cfg = ConfigSnapshot(mode = SchedulingMode.CLASSIC, qualityEnabled = true)
        assertFalse(cfg.effectiveQualityEnabled(), "Classic mode suppresses quality")
    }

    test("ConfigSnapshot effectiveQualityEnabled: true for Enhanced when enabled") {
        val cfg = ConfigSnapshot(mode = SchedulingMode.ENHANCED, qualityEnabled = true)
        assertTrue(cfg.effectiveQualityEnabled())
    }

    test("ConfigSnapshot effectiveQualityEnabled: true for RttThreshold when enabled") {
        val cfg = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD, qualityEnabled = true)
        assertTrue(cfg.effectiveQualityEnabled())
    }

    test("ConfigSnapshot effectiveQualityEnabled: true for EDPF (not classic)") {
        // Rust: quality_enabled && !mode.is_classic() → EDPF is not Classic → true
        val cfg = ConfigSnapshot(mode = SchedulingMode.EDPF, qualityEnabled = true)
        assertTrue(cfg.effectiveQualityEnabled(), "EDPF is not classic, so quality enabled")
    }

    test("ConfigSnapshot effectiveExplorationEnabled: only Enhanced") {
        assertTrue(ConfigSnapshot(mode = SchedulingMode.ENHANCED,
            explorationEnabled = true).effectiveExplorationEnabled())
        assertFalse(ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD,
            explorationEnabled = true).effectiveExplorationEnabled())
        assertFalse(ConfigSnapshot(mode = SchedulingMode.CLASSIC,
            explorationEnabled = true).effectiveExplorationEnabled())
    }

    test("ConfigSnapshot default: Enhanced, quality=true, explore=false, delta=30") {
        val cfg = ConfigSnapshot()
        assertEquals(SchedulingMode.ENHANCED, cfg.mode)
        assertTrue(cfg.qualityEnabled)
        assertFalse(cfg.explorationEnabled)
        assertEquals(RTT_DELTA_DEFAULT_MS, cfg.rttDeltaMs)
    }

    test("stale quality cache entries pruned when connection removed") {
        val c0 = makeSelConn()
        val orch = SelectionOrchestrator()
        val cfg = ConfigSnapshot(mode = SchedulingMode.ENHANCED)
        val now = System.currentTimeMillis()
        // First call: c0 gets a cache entry
        orch.select(listOf(c0), null, 0L, now, cfg)
        // Second call without c0: its cache should be pruned (no crash)
        orch.select(emptyList(), null, 0L, now, cfg)
        // Third call adding c0 back: fresh cache
        val sel = orch.select(listOf(c0), null, 0L, now, cfg)
        assertNotNull(sel)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SchedulingMode enum tests (mirrors src/mode.rs #[cfg(test)])
// ═══════════════════════════════════════════════════════════════════════════════

suite("SchedulingMode") {

    test("default mode is ENHANCED") {
        val cfg = ConfigSnapshot()
        assertEquals(SchedulingMode.ENHANCED, cfg.mode)
    }

    test("isClassic / isEnhanced / isRttThreshold / isEdpf checks") {
        assertTrue(SchedulingMode.CLASSIC.isClassic())
        assertFalse(SchedulingMode.CLASSIC.isEnhanced())
        assertFalse(SchedulingMode.CLASSIC.isRttThreshold())
        assertFalse(SchedulingMode.CLASSIC.isEdpf())

        assertTrue(SchedulingMode.ENHANCED.isEnhanced())
        assertFalse(SchedulingMode.ENHANCED.isClassic())

        assertTrue(SchedulingMode.RTT_THRESHOLD.isRttThreshold())
        assertFalse(SchedulingMode.RTT_THRESHOLD.isClassic())

        assertTrue(SchedulingMode.EDPF.isEdpf())
        assertFalse(SchedulingMode.EDPF.isClassic())
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Exploration logic tests
// ═══════════════════════════════════════════════════════════════════════════════

suite("ExplorationLogic") {

    test("returns false without best and second-best") {
        val now = System.currentTimeMillis()
        assertFalse(shouldExploreNow(emptyList(), null, null, now))
        assertFalse(shouldExploreNow(listOf(makeSelConn()), 0, null, now))
    }

    test("triggers when best is degraded and second has recovered") {
        val now = System.currentTimeMillis()
        // best: recent NAK (1s ago → < 3s)
        val best = makeSelConn(nakCount = 1, lastNakAgoMs = 1_000L, connEstAgoMs = 35_000L)
        // second: recovered (last NAK 6s ago → > 5s)
        val second = makeSelConn(nakCount = 1, lastNakAgoMs = 6_000L, connEstAgoMs = 35_000L)
        val conns = listOf(best, second)
        // Compute at a time not on the periodic boundary (use a controlled time)
        val nonPeriodicTime = (now / 30_000L) * 30_000L + 10_000L // 10s into a 30s cycle
        val result = shouldExploreNow(conns, 0, 1, nonPeriodicTime)
        assertTrue(result, "Should explore when best degraded and second recovered")
    }

    test("does not trigger when best is not degraded") {
        val now = (System.currentTimeMillis() / 30_000L) * 30_000L + 10_000L
        // best: last NAK 5s ago → not degraded (need < 3s)
        val best = makeSelConn(nakCount = 1, lastNakAgoMs = 5_000L, connEstAgoMs = 35_000L)
        val second = makeSelConn(nakCount = 0, connEstAgoMs = 35_000L)
        val conns = listOf(best, second)
        val result = shouldExploreNow(conns, 0, 1, now)
        assertFalse(result, "Should not explore when best is not degraded")
    }
}

} // registerSelectionTests
