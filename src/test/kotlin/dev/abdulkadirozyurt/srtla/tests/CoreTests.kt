// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/seq.rs, ewma.rs, kalman.rs, mode.rs, priority.rs,
// connection/{bitrate.rs, rtt.rs, reconnection.rs, batch_send.rs, congestion/*.rs, mod.rs, ack_nak.rs}
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.protocol.WINDOW_INCR
import dev.abdulkadirozyurt.srtla.core.seqAfter
import dev.abdulkadirozyurt.srtla.core.seqDiff
import dev.abdulkadirozyurt.srtla.core.seqNext
import dev.abdulkadirozyurt.srtla.core.seqNormalize
import dev.abdulkadirozyurt.srtla.core.SEQ_MASK
import dev.abdulkadirozyurt.srtla.core.NO_ACK_YET
import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import dev.abdulkadirozyurt.srtla.core.selectBestQualityIdx
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.filter.Ewma
import dev.abdulkadirozyurt.srtla.filter.KalmanConfig
import dev.abdulkadirozyurt.srtla.filter.KalmanFilter
import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.connection.BitrateTracker
import dev.abdulkadirozyurt.srtla.connection.RttTracker
import dev.abdulkadirozyurt.srtla.connection.ReconnectionState
import dev.abdulkadirozyurt.srtla.connection.BatchSender
import dev.abdulkadirozyurt.srtla.connection.BatchRegime
import dev.abdulkadirozyurt.srtla.connection.congestion.CongestionControl
import dev.abdulkadirozyurt.srtla.connection.congestion.IntRef
import dev.abdulkadirozyurt.srtla.testkit.*
import kotlin.math.abs

private const val SEQ_HALF = 0x4000_0000  // 2^30

fun registerCoreTests() {
    // ===== Seq Tests =====
    suite("seq") {
        test("normal_ordering") {
            assertTrue(seqAfter(10, 5))
            assertFalse(seqAfter(5, 10))
            assertEquals(seqDiff(10, 5), 5)
            assertEquals(seqDiff(5, 10), -5)
        }

        test("equal_is_not_after") {
            assertFalse(seqAfter(42, 42))
            assertEquals(seqDiff(42, 42), 0)
            assertFalse(seqAfter(0, 0))
            assertFalse(seqAfter(SEQ_MASK, SEQ_MASK))
        }

        test("wrap_boundary_counts_as_advancing") {
            assertTrue(seqAfter(0, SEQ_MASK))
            assertEquals(seqDiff(0, SEQ_MASK), 1)
            assertEquals(seqNext(SEQ_MASK), 0)
            assertEquals(seqDiff(2, SEQ_MASK - 1), 4)
            assertTrue(seqAfter(2, SEQ_MASK - 1))
            assertFalse(seqAfter(SEQ_MASK - 1, 2))
        }

        test("half_space_is_the_ordering_horizon") {
            assertTrue(seqAfter(SEQ_HALF - 1, 0))
            assertEquals(seqDiff(SEQ_HALF - 1, 0), SEQ_HALF - 1)
            assertFalse(seqAfter(SEQ_HALF, 0))
            assertEquals(seqDiff(SEQ_HALF, 0), -(SEQ_HALF))
        }

        test("diff_is_antisymmetric") {
            for ((a, b) in listOf(0 to 1, 7 to 900_001, SEQ_MASK to 3, 12345 to 12345)) {
                assertEquals(seqDiff(a, b), -seqDiff(b, a), "a=$a b=$b")
            }
        }

        test("normalize_clears_a_corrupt_msb") {
            assertEquals(seqNormalize(0), 0)
            assertEquals(seqNormalize(SEQ_MASK), SEQ_MASK)
            assertEquals(seqNormalize(-1), SEQ_MASK)
            assertEquals(seqNormalize(Int.MIN_VALUE), 0)
        }

        test("next_never_leaves_the_space") {
            assertEquals(seqNext(0), 1)
            assertEquals(seqNext(SEQ_MASK - 1), SEQ_MASK)
            assertEquals(seqNext(SEQ_MASK), 0)
            assertEquals(seqNext(seqNext(SEQ_MASK)), 1)
        }

        test("sentinel_is_outside_the_sequence_space") {
            assertTrue(NO_ACK_YET != seqNormalize(NO_ACK_YET))
            for (s in listOf(0, 1, SEQ_MASK, SEQ_MASK - 1, 123_456)) {
                assertTrue(s != NO_ACK_YET)
            }
        }
    }

    // ===== Ewma Tests (plain Ewma only; skip AsymmetricEwma) =====
    suite("ewma") {
        test("test_ewma_logic") {
            val ewma = Ewma(0.5)
            ewma.update(10.0)
            assertClose(ewma.value, 10.0, 1e-9)
            ewma.update(20.0)
            assertClose(ewma.value, 15.0, 1e-9)
            ewma.update(30.0)
            assertClose(ewma.value, 22.5, 1e-9)
        }

        test("test_ewma_smoothing") {
            val ewma = Ewma(0.1)
            ewma.update(100.0)
            assertClose(ewma.value, 100.0, 1e-9)
            ewma.update(0.0)
            assertClose(ewma.value, 90.0, 1e-9)
        }

        test("test_ewma_uninitialized_value_is_zero") {
            val ewma = Ewma(0.5)
            assertClose(ewma.value, 0.0, 1e-9)
        }

        test("test_ewma_alpha_one_follows_input") {
            val ewma = Ewma(1.0)
            ewma.update(10.0)
            assertClose(ewma.value, 10.0, 1e-9)
            ewma.update(50.0)
            assertClose(ewma.value, 50.0, 1e-9)
        }

        test("test_ewma_alpha_near_zero_retains_history") {
            val ewma = Ewma(0.001)
            ewma.update(100.0)
            assertClose(ewma.value, 100.0, 1e-9)
            ewma.update(0.0)
            assertClose(ewma.value, 99.9, 0.01)
        }

        test("test_ewma_negative_values") {
            val ewma = Ewma(0.5)
            ewma.update(-10.0)
            assertClose(ewma.value, -10.0, 1e-9)
            ewma.update(10.0)
            assertClose(ewma.value, 0.0, 1e-9)
        }

        test("test_ewma_converges_to_constant") {
            val ewma = Ewma(0.5)
            repeat(100) { ewma.update(42.0) }
            assertClose(ewma.value, 42.0, 0.001)
        }

        test("test_ewma_nan_guard") {
            val ewma = Ewma(0.5)
            ewma.update(10.0)
            assertClose(ewma.value, 10.0, 1e-9)
            ewma.update(Double.NaN)
            assertClose(ewma.value, 10.0, 1e-9)
            ewma.update(Double.POSITIVE_INFINITY)
            assertClose(ewma.value, 10.0, 1e-9)
            ewma.update(Double.NEGATIVE_INFINITY)
            assertClose(ewma.value, 10.0, 1e-9)
            ewma.update(20.0)
            assertClose(ewma.value, 15.0, 1e-9)
        }

        test("test_ewma_nan_on_first_sample") {
            val ewma = Ewma(0.5)
            ewma.update(Double.NaN)
            assertClose(ewma.value, 0.0, 1e-9)
            ewma.update(42.0)
            assertClose(ewma.value, 42.0, 1e-9)
        }

        test("test_ewma_reset") {
            val ewma = Ewma(0.5)
            ewma.update(100.0)
            ewma.reset()
            assertClose(ewma.value, 0.0, 1e-9)
            ewma.update(50.0)
            assertClose(ewma.value, 50.0, 1e-9)
        }

        // SKIPPED: AsymmetricEwma tests — type not ported
    }

    // ===== Kalman Filter Tests =====
    suite("kalman") {
        test("test_initializes_on_first_measurement") {
            val kf = KalmanFilter(KalmanConfig.forRtt())
            assertFalse(kf.isInitialized)
            kf.update(50.0)
            assertTrue(kf.isInitialized)
            assertClose(kf.value, 50.0, 1e-9)
            assertClose(kf.velocity, 0.0, 1e-9)
        }

        test("test_tracks_constant_signal") {
            val kf = KalmanFilter(KalmanConfig.forRtt())
            repeat(100) { kf.update(42.0) }
            assertClose(kf.value, 42.0, 0.1, "should converge to constant: ${kf.value}")
            assertTrue(abs(kf.velocity) < 0.1)
        }

        test("test_tracks_rising_trend") {
            val kf = KalmanFilter(KalmanConfig.forRtt())
            for (i in 0..49) {
                kf.update(50.0 + i)
            }
            assertTrue(kf.velocity > 0.5, "velocity should be positive: ${kf.velocity}")
            assertTrue(kf.value > 90.0, "value should track rising input: ${kf.value}")
        }

        test("test_smooths_noisy_signal") {
            val kf = KalmanFilter(KalmanConfig.forRtt())
            for (i in 0..99) {
                val noise = if (i % 2 == 0) 10.0 else -10.0
                kf.update(50.0 + noise)
            }
            assertClose(kf.value, 50.0, 5.0, "should smooth noise: ${kf.value}")
        }

        test("test_nan_guard") {
            val kf = KalmanFilter(KalmanConfig.forRtt())
            kf.update(50.0)
            kf.update(Double.NaN)
            assertClose(kf.value, 50.0, 1.0)
        }

        test("test_reset") {
            val kf = KalmanFilter(KalmanConfig.forRtt())
            kf.update(50.0)
            assertTrue(kf.isInitialized)
            kf.reset()
            assertFalse(kf.isInitialized)
            assertClose(kf.value, 0.0, 1e-9)
        }

        test("kalman_init_value_is_zero") {
            val kf = KalmanFilter(KalmanConfig.forRtt())
            assertFalse(kf.isInitialized)
            assertEquals(kf.value, 0.0)
            assertEquals(kf.velocity, 0.0)
        }

        test("kalman_converges_toward_input") {
            val kf = KalmanFilter(KalmanConfig.forRtt())
            val INPUT = 73.0
            repeat(200) { kf.update(INPUT) }
            assertClose(kf.value, INPUT, 0.1, "should converge toward input: got ${kf.value}")
            assertTrue(abs(kf.velocity) < 0.1, "velocity should flatten: got ${kf.velocity}")
        }
    }

    // ===== Scheduling Mode Tests =====
    suite("mode") {
        test("test_mode_default") {
            assertEquals(SchedulingMode.ENHANCED, SchedulingMode.DEFAULT)
        }

        test("test_mode_roundtrip_string") {
            assertEquals(SchedulingMode.parse("classic"), SchedulingMode.CLASSIC)
            assertEquals(SchedulingMode.parse("enhanced"), SchedulingMode.ENHANCED)
            try {
                SchedulingMode.parse("invalid")
                assertFalse(true, "should have thrown")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }

        test("test_mode_display") {
            assertEquals(SchedulingMode.CLASSIC.toString(), "classic")
            assertEquals(SchedulingMode.ENHANCED.toString(), "enhanced")
        }

        test("test_mode_is_classic") {
            assertTrue(SchedulingMode.CLASSIC.isClassic())
            assertFalse(SchedulingMode.ENHANCED.isClassic())
        }
    }

    // ===== Critical Window / Priority Tests =====
    suite("priority") {
        test("is_critical_respects_deadline") {
            val w = CriticalWindow()
            assertFalse(w.isCriticalNow(100))
            w.extendTo(500)
            assertTrue(w.isCriticalNow(100))
            assertTrue(w.isCriticalNow(499))
            assertFalse(w.isCriticalNow(500))
            assertFalse(w.isCriticalNow(501))
        }

        test("extend_to_is_monotonic") {
            val w = CriticalWindow()
            w.extendTo(200)
            w.extendTo(100)  // older: ignored
            w.extendTo(300)  // newer: applied
            assertTrue(w.isCriticalNow(250))
            assertTrue(w.isCriticalNow(299))
            assertFalse(w.isCriticalNow(300))
            assertEquals(w.windowsReceived(), 3L)
        }

        test("best_quality_idx_picks_highest") {
            val conns = createTestConnections(3, nowMs())
            conns[0].qualityCache.multiplier = 0.8
            conns[1].qualityCache.multiplier = 1.1
            conns[2].qualityCache.multiplier = 0.95
            assertEquals(selectBestQualityIdx(conns, nowMs()), 1)
        }

        test("best_quality_idx_skips_disconnected") {
            val conns = createTestConnections(3, nowMs())
            conns[0].qualityCache.multiplier = 0.8
            conns[1].qualityCache.multiplier = 1.1
            conns[1].connected = false  // best quality but disconnected
            conns[2].qualityCache.multiplier = 0.95
            assertEquals(selectBestQualityIdx(conns, nowMs()), 2)
        }

        test("best_quality_idx_empty") {
            val conns: MutableList<SrtlaConnection> = mutableListOf()
            assertEquals(selectBestQualityIdx(conns, 0), null)
        }

        test("best_quality_idx_skips_links_held_out_of_the_rotation") {
            val conns = createTestConnections(3, nowMs())
            // The held-out links have pristine quality precisely *because* they are
            // held out: carrying no unique payload, they accrue no NAKs, while the
            // link doing the work absorbs them all. Without the gate check they
            // would win the override and take the keyframes.
            conns[0].qualityCache.multiplier = 1.0
            conns[0].stallGated = true
            conns[1].qualityCache.multiplier = 1.0
            conns[1].qualityExcluded = true
            conns[2].qualityCache.multiplier = 0.4
            assertEquals(selectBestQualityIdx(conns, nowMs()), 2)
        }

        test("best_quality_idx_declines_when_every_link_is_held_out") {
            val conns = createTestConnections(2, nowMs())
            conns[0].stallGated = true
            conns[1].qualityExcluded = true
            // `None` hands the decision back to normal selection, which has its own
            // never-empty-the-pool guarantees. The override must not invent a
            // carrier here.
            assertEquals(selectBestQualityIdx(conns, nowMs()), null)
        }
    }

    // ===== Bitrate Tracker Tests =====
    suite("bitrate") {
        test("bitrate_send_raises_estimate") {
            val t = BitrateTracker(1_000_000)
            assertEquals(t.currentBitrateBps, 0.0)
            t.updateOnSend(500_000)
            assertEquals(t.bytesSentTotal, 500_000)
            t.calculate(1_000_000 + 2_500)
            assertTrue(t.currentBitrateBps > 0.0, "sending bytes must raise estimate, got ${t.currentBitrateBps}")
        }

        test("bitrate_idle_decay") {
            val t = BitrateTracker(1_000_000)
            t.updateOnSend(500_000)
            t.calculate(1_000_000 + 2_500)
            assertTrue(t.currentBitrateBps > 0.0)
            t.calculate(1_000_000 + 5_000)
            assertEquals(t.currentBitrateBps, 0.0, "idle window must decay to zero")
        }

        test("bitrate_wire_bytes_basis") {
            val before = 1_000_000L
            val t = BitrateTracker(before)
            t.updateOnSend(1_000_000)
            t.calculate(before + 4_000)
            val elapsed = t.lastRateUpdateMs.satSub(before)
            val expected = (1_000_000L * 8) * 1000.0 / elapsed
            assertClose(t.currentBitrateBps, expected, 1.0,
                "bitrate is wire-bytes/s x8 (bps): got ${t.currentBitrateBps}, expected $expected")
        }
    }

    // ===== RTT Tracker Tests =====
    suite("rtt") {
        test("test_dual_window_adapts_to_handover") {
            val tracker = RttTracker()
            val T0 = 1_000_000L
            // Establish baseline at 50ms
            repeat(10) { tracker.updateEstimate(50, T0) }
            assertClose(tracker.rttMinMs, 50.0, 1.0,
                "baseline should be ~50ms, got ${tracker.rttMinMs}")
            // Handover
            repeat(15 + 100) { tracker.updateEstimate(120, T0) }
            assertClose(tracker.rttMinMs, 120.0, 1.0,
                "baseline should adapt to ~120ms, got ${tracker.rttMinMs}")
        }

        test("test_dual_window_tracks_minimum") {
            val tracker = RttTracker()
            val T0 = 1_000_000L
            tracker.updateEstimate(100, T0)
            tracker.updateEstimate(80, T0)
            tracker.updateEstimate(60, T0)
            tracker.updateEstimate(90, T0)
            tracker.updateEstimate(70, T0)
            assertClose(tracker.rttMinMs, 60.0, 1.0,
                "baseline should track minimum of 60ms, got ${tracker.rttMinMs}")
        }

        test("test_dual_window_reset_clears_windows") {
            val tracker = RttTracker()
            val T0 = 1_000_000L
            repeat(20) { tracker.updateEstimate(50, T0) }
            assertClose(tracker.rttMinMs, 50.0, 1.0)
            tracker.reset()
            assertEquals(tracker.rttMinMs, 200.0)
            tracker.updateEstimate(80, T0)
            assertClose(tracker.rttMinMs, 80.0, 1.0,
                "after reset, baseline should be 80ms, got ${tracker.rttMinMs}")
        }

        test("test_queue_building_ignores_pure_jitter") {
            val tracker = RttTracker()
            val T0 = 1_000_000L
            for (i in 0..79) {
                val rtt = if (i % 2 == 0) 40L else 60L
                tracker.updateEstimate(rtt, T0)
            }
            assertFalse(tracker.queueBuildingSuspected(),
                "pure jitter must not be read as queue (gradient=${tracker.rttGradientMs()}, masd=${tracker.rttMasdMs})")
        }

        test("test_queue_building_trips_on_standing_queue") {
            val tracker = RttTracker()
            val T0 = 1_000_000L
            repeat(30) { tracker.updateEstimate(20, T0) }
            assertFalse(tracker.queueBuildingSuspected())
            var rtt = 20L
            repeat(60) {
                rtt += 2
                tracker.updateEstimate(rtt, T0)
            }
            assertTrue(tracker.queueBuildingSuspected(),
                "sustained delay ramp must trip detector (gradient=${tracker.rttGradientMs()}, masd=${tracker.rttMasdMs})")
        }

        test("test_kalman_smooths_rtt") {
            val tracker = RttTracker()
            val T0 = 1_000_000L
            repeat(50) { tracker.updateEstimate(50, T0) }
            assertClose(tracker.estimatedRttMs, 50.0, 1.0,
                "Kalman should converge: ${tracker.estimatedRttMs}")
            repeat(20) { tracker.updateEstimate(80, T0) }
            assertTrue(tracker.kalmanRtt.velocity > 0.0 || tracker.estimatedRttMs > 60.0,
                "should track rising RTT: est=${tracker.estimatedRttMs}, vel=${tracker.kalmanRtt.velocity}")
        }

        test("test_keepalive_zero_rtt_rejected") {
            val tracker = RttTracker()
            val T0 = 1_000_000L
            assertEquals(tracker.rttMinMs, 200.0)
            tracker.recordKeepaliveSent(T0)
            assertTrue(tracker.waitingForKeepaliveResponse)
            val futureTs = T0 + 1_000_000
            // Build a fake keepalive packet
            assertNull(tracker.recordRoundTrip(futureTs, T0), "zero-RTT keepalive must be rejected")
            assertFalse(tracker.kalmanRtt.isInitialized, "zero-RTT must not initialize Kalman")
            assertEquals(tracker.rttMinMs, 200.0, "rtt_min_ms must stay at baseline")
        }
    }

    // ===== Reconnection State Tests =====
    suite("reconnection") {
        test("fast_attempts_then_flat_five_seconds") {
            val gaps = ticksBetweenAttempts(established(), 8)
            assertEquals(gaps, listOf(1L, 1L, 1L, 5L, 5L, 5L, 5L))
        }

        test("retry_lands_on_the_next_tick_despite_service_jitter") {
            val state = established()
            state.recordAttempt("test", 50_003)
            assertTrue(state.shouldAttemptReconnect(51_000))
        }

        test("slow_retry_does_not_come_a_tick_early") {
            val state = ReconnectionState(
                lastReconnectAttemptMs = 0,
                reconnectFailureCount = 3,  // FAST_RETRY_ATTEMPTS - 1 = 4 - 1 = 3
                connectionEstablishedMs = 1_000,
                startupGraceDeadlineMs = 0
            )
            state.recordAttempt("test", 50_000)
            assertFalse(state.shouldAttemptReconnect(54_000))
            assertTrue(state.shouldAttemptReconnect(55_000))
        }

        test("registration_restores_the_fast_attempts") {
            val state = established()
            repeat(10) { i ->
                state.recordAttempt("test", 50_000L + i * 5000L)
            }
            state.markSuccess("test")
            val gaps = ticksBetweenAttempts(state, 5)
            assertEquals(gaps, listOf(1L, 1L, 1L, 5L))
        }

        test("initial_registration_retries_every_tick_without_counting") {
            val state = ReconnectionState(
                lastReconnectAttemptMs = 0,
                reconnectFailureCount = 0,
                connectionEstablishedMs = 0,
                startupGraceDeadlineMs = 10_000
            )
            assertFalse(state.shouldAttemptReconnect(10_000))
            assertTrue(state.shouldAttemptReconnect(10_001))
            state.recordAttempt("test", 10_001)
            assertEquals(state.reconnectFailureCount, 0)
            assertTrue(state.shouldAttemptReconnect(11_000))
        }
    }

    // ===== Batch Sender Tests =====
    suite("batch_send") {
        test("test_batch_sender_queue") {
            val sender = BatchSender()
            val data = ByteArray(100)
            for (i in 0..14) {  // BATCH_SIZE_THRESHOLD - 1 = 16 - 1 = 15
                assertFalse(sender.queuePacket(data, i.toInt(), 0))
                assertEquals(sender.queuedCount().toInt(), i + 1)
            }
            assertTrue(sender.queuePacket(data, 15, 0))
            assertEquals(sender.queuedCount(), 16)
        }

        test("test_batch_sender_time_flush") {
            val sender = BatchSender()
            val data = ByteArray(100)
            sender.queuePacket(data, 1, 0)
            assertFalse(sender.needsTimeFlush(0))
            assertFalse(sender.needsTimeFlush(14))  // FLUSH_INTERVAL_MS - 1
            assertTrue(sender.needsTimeFlush(15))
            assertTrue(sender.needsTimeFlush(20))
        }

        test("test_batch_sender_reset") {
            val sender = BatchSender()
            val data = ByteArray(100)
            sender.queuePacket(data, 1, 0)
            sender.queuePacket(data, 2, 0)
            sender.reset()
            assertEquals(sender.queuedCount(), 0)
        }

        test("regime_from_bps_thresholds") {
            assertEquals(BatchRegime.fromBps(100_000.0), BatchRegime.LOW_ACTIVITY,
                "well below 500 kbps → LowActivity")
            assertEquals(BatchRegime.fromBps(500_000.0), BatchRegime.LOW_ACTIVITY,
                "exactly at threshold stays LowActivity")
            assertEquals(BatchRegime.fromBps(2_000_000.0), BatchRegime.NORMAL,
                "between thresholds → Normal")
            assertEquals(BatchRegime.fromBps(5_000_000.0), BatchRegime.NORMAL,
                "exactly at high threshold stays Normal")
            assertEquals(BatchRegime.fromBps(5_000_001.0), BatchRegime.HIGH_LOAD,
                "just above 5 Mbps → HighLoad")
        }
    }

    // ===== Congestion Control Tests (Classic) =====
    suite("congestion_classic") {
        test("test_classic_ack_increases_window") {
            val ref = IntRef(1500)
            val cc = CongestionControl()
            cc.handleSrtlaAckSpecificClassic(ref, 3, 100, "test")
            // Should increase: 3 * 1000 = 3000 > 1500
            assertEquals(ref.value, 1500 + WINDOW_INCR - 1)
        }

        test("test_classic_ack_no_increase_when_window_high") {
            val ref = IntRef(5000)
            val cc = CongestionControl()
            cc.handleSrtlaAckSpecificClassic(ref, 3, 100, "test")
            // Should NOT increase: 3 * 1000 = 3000 < 5000
            assertEquals(ref.value, 5000)
        }

        test("test_classic_ack_respects_max_window") {
            val ref = IntRef(2_147_000_000)  // Near WINDOW_MAX * WINDOW_MULT
            val cc = CongestionControl()
            cc.handleSrtlaAckSpecificClassic(ref, 100, 100, "test")
            assertTrue(ref.value <= 2_147_483_647)  // WINDOW_MAX * WINDOW_MULT
        }

        test("test_classic_ack_no_overflow_at_extreme_in_flight") {
            val ref = IntRef(1500)
            val cc = CongestionControl()
            cc.handleSrtlaAckSpecificClassic(ref, Int.MAX_VALUE / 1000 + 1, 100, "test")
            assertEquals(ref.value, 1500 + WINDOW_INCR - 1)
        }
    }

    // ===== Congestion Control Tests (Enhanced) =====
    suite("congestion_enhanced") {
        test("test_enhanced_ack_increases_window") {
            val ref = IntRef(1500)
            val cc = CongestionControl()
            cc.handleSrtlaAckEnhanced(ref, 3, "test", 1_000_000)
            assertEquals(ref.value, 1500 + WINDOW_INCR - 1)
        }

        test("test_enhanced_ack_no_overflow_at_extreme_in_flight") {
            val ref = IntRef(1500)
            val cc = CongestionControl()
            cc.handleSrtlaAckEnhanced(ref, Int.MAX_VALUE / 1000 + 1, "test", 1_000_000)
            assertEquals(ref.value, 1500 + WINDOW_INCR - 1)
        }

        test("test_window_recovery_progressive") {
            val T0 = 1_000_000L
            val ref = IntRef(5000)
            val cc = CongestionControl()
            cc.lastNakTimeMs = T0 - 10_500
            cc.performWindowRecovery(ref, true, 0.0, "test", T0)
            assertTrue(ref.value > 5000)
        }

        test("test_window_recovery_with_no_nak_history") {
            val T0 = 1_000_000L
            val ref = IntRef(5000)
            val cc = CongestionControl()
            // last_nak = 0 (never) - performWindowRecovery uses member lastNakTimeMs
            cc.performWindowRecovery(ref, true, 0.0, "test", T0)
            assertTrue(ref.value > 5000, "Window should grow for connections with no NAK history")
            assertEquals(ref.value, 5000 + WINDOW_INCR * 2, "Should use aggressive recovery")
        }

        test("test_window_recovery_gated_by_rtt_velocity") {
            val T0 = 1_000_000L
            val windowStable = IntRef(5000)
            val windowRising = IntRef(5000)
            val cc = CongestionControl()
            cc.lastNakTimeMs = T0 - 10_500
            cc.performWindowRecovery(windowStable, true, 0.0, "test", T0)
            cc.performWindowRecovery(windowRising, true, 3.0, "test", T0)
            val stableIncr = windowStable.value - 5000
            val risingIncr = windowRising.value - 5000
            assertTrue(risingIncr < stableIncr,
                "Rising RTT recovery ($risingIncr) should be less than stable ($stableIncr)")
        }
    }

    // ===== Ramp Tests =====
    suite("connection/ramp") {
        test("ramp_is_inert_when_no_ramp_is_running") {
            assertEquals(rejoinRampMultiplier(0, 1000, 500), 1.0)
            assertEquals(rejoinRampMultiplier(100, 0, 500), 1.0)
            assertEquals(rejoinRampMultiplier(100, 1000, 1100), 1.0)
            assertEquals(rejoinRampMultiplier(100, 1000, 9999), 1.0)
        }

        test("ramp_rises_linearly_from_the_floor_to_full") {
            assertEquals(rejoinRampMultiplier(100, 1000, 100), STALL_REJOIN_RAMP_FLOOR)
            val quarter = rejoinRampMultiplier(100, 1000, 350)
            val half = rejoinRampMultiplier(100, 1000, 600)
            val threeQuarters = rejoinRampMultiplier(100, 1000, 850)
            assertClose(quarter, STALL_REJOIN_RAMP_FLOOR + 0.95 * 0.25, 1e-9)
            assertClose(half, STALL_REJOIN_RAMP_FLOOR + 0.95 * 0.50, 1e-9)
            assertClose(threeQuarters, STALL_REJOIN_RAMP_FLOOR + 0.95 * 0.75, 1e-9)
            assertTrue(quarter < half && half < threeQuarters && threeQuarters < 1.0)
        }

        test("ramp_never_scores_a_rejoining_link_to_zero") {
            for (elapsed in 0..9) {
                val m = rejoinRampMultiplier(1000L, 100_000L, 1000L + elapsed)
                assertTrue(m >= STALL_REJOIN_RAMP_FLOOR, "ramp dropped to $m")
            }
        }

        test("ramp_tolerates_a_clock_that_went_backwards") {
            assertEquals(rejoinRampMultiplier(1000, 500, 900), STALL_REJOIN_RAMP_FLOOR)
        }
    }

    // ===== ACK/NAK Tests =====
    suite("ack_nak") {
        test("first_ack_after_sentinel_is_accepted") {
            val conn = createTestConnection()
            assertEquals(conn.highestAckedSeq, NO_ACK_YET)
            conn.registerPacket(10, 1_000)
            conn.registerPacket(20, 1_000)
            conn.handleSrtAck(10, 1_040, true)
            assertEquals(conn.highestAckedSeq, 10)
            assertFalse(conn.packetLog.containsKey(10))
            assertTrue(conn.packetLog.containsKey(20))
            assertEquals(conn.inFlightPackets, 1)
            assertClose(conn.rtt.estimatedRttMs, 40.0, 1.0)
        }

        test("duplicate_and_reordered_acks_are_ignored") {
            val conn = createTestConnection()
            for (seq in listOf(10, 20, 30)) {
                conn.registerPacket(seq, 1_000)
            }
            conn.handleSrtAck(20, 1_040, true)
            assertEquals(conn.inFlightPackets, 1)
            conn.handleSrtAck(20, 5_000, true)
            conn.handleSrtAck(10, 5_000, true)
            assertEquals(conn.highestAckedSeq, 20)
            assertEquals(conn.inFlightPackets, 1)
            assertTrue(conn.packetLog.containsKey(30))
            assertClose(conn.rtt.estimatedRttMs, 40.0, 1.0)
        }

        test("cumulative_ack_across_the_wrap_keeps_pruning_and_sampling") {
            val conn = createTestConnection()
            val ACROSS_WRAP = intArrayOf(SEQ_MASK - 1, SEQ_MASK, 0, 1, 2)
            for (seq in ACROSS_WRAP) {
                conn.registerPacket(seq, 1_000)
            }
            assertEquals(conn.inFlightPackets, 5)
            conn.handleSrtAck(SEQ_MASK - 1, 1_040, true)
            assertEquals(conn.inFlightPackets, 4)
            conn.handleSrtAck(0, 1_060, true)
            assertEquals(conn.highestAckedSeq, 0)
            assertFalse(conn.packetLog.containsKey(SEQ_MASK))
            assertFalse(conn.packetLog.containsKey(0))
            assertEquals(conn.inFlightPackets, 2)
            assertTrue(conn.rtt.estimatedRttMs > 40.0)
            conn.handleSrtAck(2, 1_080, true)
            assertEquals(conn.inFlightPackets, 0)
            assertTrue(conn.packetLog.isEmpty())
        }

        test("wrap_spanning_range_removes_only_the_acked_keys") {
            val conn = createTestConnection()
            val ACROSS_WRAP = intArrayOf(SEQ_MASK - 1, SEQ_MASK, 0, 1, 2)
            for (seq in ACROSS_WRAP) {
                conn.registerPacket(seq, 1_000)
            }
            conn.registerPacket(3, 1_000)
            conn.registerPacket(50, 1_000)
            conn.handleSrtAck(SEQ_MASK - 1, 1_010, false)
            conn.handleSrtAck(2, 1_020, false)
            assertEquals(conn.highestAckedSeq, 2)
            val left = conn.packetLog.keys.sorted()
            assertEquals(left, listOf(3, 50))
            assertEquals(conn.inFlightPackets, 2)
        }

        test("post_wrap_ack_is_not_a_duplicate_even_after_a_long_pre_wrap_run") {
            val conn = createTestConnection()
            conn.registerPacket(SEQ_MASK, 1_000)
            conn.handleSrtAck(SEQ_MASK, 1_010, false)
            assertEquals(conn.highestAckedSeq, SEQ_MASK)
            conn.registerPacket(500, 2_000)
            conn.registerPacket(1_000, 2_000)
            conn.handleSrtAck(500, 2_050, true)
            assertEquals(conn.highestAckedSeq, 500)
            assertFalse(conn.packetLog.containsKey(500))
            assertTrue(conn.packetLog.containsKey(1_000))
            assertClose(conn.rtt.estimatedRttMs, 50.0, 1.0)
        }

        test("far_future_ack_is_stale_and_prunes_nothing") {
            val conn = createTestConnection()
            conn.registerPacket(10, 1_000)
            conn.handleSrtAck(10, 1_040, false)
            conn.registerPacket(20, 1_000)
            conn.handleSrtAck(0x4000_0010, 1_050, false)
            assertEquals(conn.highestAckedSeq, 10)
            assertTrue(conn.packetLog.containsKey(20))
            assertEquals(conn.inFlightPackets, 1)
        }

        test("corrupt_msb_ack_is_normalized_not_negative") {
            val conn = createTestConnection()
            conn.registerPacket(30, 1_000)
            conn.handleSrtAck(Int.MIN_VALUE or 30, 1_040, true)
            assertEquals(conn.highestAckedSeq, 30)
            assertTrue(conn.packetLog.isEmpty())
            assertClose(conn.rtt.estimatedRttMs, 40.0, 1.0)
        }
    }
}

// ===== Private Helper Functions =====

private fun established(): ReconnectionState {
    return ReconnectionState(
        lastReconnectAttemptMs = 0,
        reconnectFailureCount = 0,
        connectionEstablishedMs = 1_000,
        startupGraceDeadlineMs = 0
    )
}

private fun ticksBetweenAttempts(mut: ReconnectionState, attempts: Int): List<Long> {
    var state = mut
    var now = 50_000L
    var lastAttemptTick: Long? = null
    val gaps = mutableListOf<Long>()
    var tick = 0L
    while (gaps.size < attempts - 1) {
        if (state.shouldAttemptReconnect(now)) {
            state.recordAttempt("test", now)
            if (lastAttemptTick != null) {
                gaps.add(tick - lastAttemptTick)
            }
            lastAttemptTick = tick
        }
        now += 1_000
        tick++
    }
    return gaps
}

private const val STALL_REJOIN_RAMP_FLOOR = 0.05
private const val STALL_REJOIN_RAMP_DWELL_MS = 1000L

private fun rejoinRampMultiplier(enteredMs: Long, dwellMs: Long, nowMs: Long): Double {
    if (dwellMs == 0L || enteredMs == 0L) return 1.0
    val elapsed = nowMs.satSub(enteredMs)
    if (elapsed > dwellMs) return 1.0
    val progress = if (dwellMs > 0) (elapsed.toDouble() / dwellMs.toDouble()).coerceIn(0.0, 1.0) else 0.0
    return (STALL_REJOIN_RAMP_FLOOR + (1.0 - STALL_REJOIN_RAMP_FLOOR) * progress).coerceAtLeast(STALL_REJOIN_RAMP_FLOOR)
}
