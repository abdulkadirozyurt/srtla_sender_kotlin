// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/selection/link_cc.rs
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.selection.*
import dev.abdulkadirozyurt.srtla.testkit.*

private const val LOSS_WINDOW_MS: Long = 1_000L
private const val BACKOFF_EFFICACY_IMPROVEMENT_PERMILLE: Int = 800

fun registerLinkCcTests() {
    suite("linkcc") {
        test("bootstrap_holds_floor") {
            val cc = LinkCongestionState()
            cc.tick(0, 0)
            assertEquals(CcState.BOOTSTRAP, cc.state)
            assertEquals(MIN_TARGET_BPS, cc.targetBps)
        }

        test("climbing_grows_target") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 1_000)
            cc.tick(2_000_000, 1_000)
            assertEquals(CcState.CLIMBING, cc.state)
            val first = cc.targetBps
            cc.tick(2_000_000, 1_100)
            assertTrue(cc.targetBps >= first)
        }

        test("holding_when_rtt_inflates") {
            val cc = LinkCongestionState()
            cc.recordRtt(20.0, 0)
            cc.tick(2_000_000, 0)
            for (i in 1..10) {
                cc.recordRtt(35.0, (i * 600).toLong())
                cc.tick(2_000_000, (i * 600).toLong())
            }
            assertEquals(CcState.HOLDING, cc.state)
        }

        test("starved_link_with_wire_loss_does_not_ratchet_to_the_floor") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.tick(3_000_000, 0)
            val seeded = cc.targetBps
            assertTrue(seeded >= 3_000_000)

            for (i in 1..30) {
                driveTick(cc, (i * 1_000).toLong(), 50_000, 50)
            }

            assertNotEquals(CcState.BACKING_OFF, cc.state)
            assertTrue(
                cc.targetBps >= seeded,
                "starved link was ratcheted from $seeded to ${cc.targetBps} by loss it did not cause"
            )
        }

        test("loaded_link_stops_cutting_when_the_backoff_does_not_move_the_loss") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.tick(2_000_000, 0)

            for (i in 1..25) {
                val delivered = minOf(cc.targetBps, 2_000_000)
                driveTick(cc, (i * 1_000).toLong(), delivered, 100)
            }

            assertTrue(cc.lossUncongestive, "should have given up attributing the loss to itself")
            assertNotEquals(CcState.BACKING_OFF, cc.state)
            assertTrue(
                cc.targetBps > 1_000_000,
                "target collapsed to ${cc.targetBps} despite the link delivering 2 Mbps"
            )
        }

        test("congestive_loss_still_converges_on_capacity") {
            val capacityBps = 1_500_000L
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.tick(3_000_000, 0)
            assertTrue(cc.targetBps > capacityBps)

            for (i in 1..25) {
                val offered = cc.targetBps
                val delivered = minOf(offered, capacityBps)
                val lossPm = ((offered - delivered) * 1_000 / maxOf(offered, 1L)).toInt()
                driveTick(cc, (i * 1_000).toLong(), delivered, lossPm)
            }

            assertFalse(cc.lossUncongestive, "congestive loss was misread as wire loss — the backoff was working")
            assertTrue(
                cc.targetBps > capacityBps / 2 && cc.targetBps < capacityBps * 2,
                "target ${cc.targetBps} did not settle near capacity $capacityBps"
            )
        }

        test("rtt_inflation_does_not_reopen_the_backoff_path") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.tick(2_000_000, 0)

            for (i in 1..10) {
                val delivered = minOf(cc.targetBps, 2_000_000)
                driveTick(cc, (i * 1_000).toLong(), delivered, 100)
            }
            assertTrue(cc.lossUncongestive)

            for (i in 11..20) {
                cc.recordRtt(120.0, (i * 1_000).toLong())
                cc.recordLoss(1_000, 100, (i * 1_000).toLong())
                cc.tick(minOf(cc.targetBps, 2_000_000), (i * 1_000).toLong())
            }

            assertTrue(
                cc.lossUncongestive,
                "RTT inflation must not re-open the loss-backoff path — that nullifies the latch"
            )
            assertNotEquals(
                CcState.BACKING_OFF,
                cc.state,
                "the loss path must stay shut; Drain is what answers RTT inflation"
            )
        }

        test("uncongestive_verdict_is_retested_on_a_timer") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.tick(2_000_000, 0)

            for (i in 1..10) {
                val delivered = minOf(cc.targetBps, 2_000_000)
                driveTick(cc, (i * 1_000).toLong(), delivered, 100)
            }
            assertTrue(cc.lossUncongestive)

            var released = false
            for (i in 11L..(12 + LOSS_UNCONGESTIVE_RETEST_TICKS)) {
                val delivered = minOf(cc.targetBps, 2_000_000)
                driveTick(cc, (i * 1_000).toLong(), delivered, 100)
                if (!cc.lossUncongestive) {
                    released = true
                }
            }
            assertTrue(
                released,
                "verdict never expired — it should be re-tested every $LOSS_UNCONGESTIVE_RETEST_TICKS ticks, not trusted forever"
            )
        }

        test("backoff_never_caps_below_delivered_throughput") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.tick(1_000_000, 0)

            for (i in 1..30) {
                driveTick(cc, (i * 1_000).toLong(), 4_000_000, 20)
            }

            assertTrue(
                cc.targetBps >= 4_000_000,
                "target ${cc.targetBps} was cut below the 4 Mbps the link is demonstrably carrying"
            )
        }

        test("clean_loss_window_rearms_the_efficacy_test") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.tick(2_000_000, 0)

            for (i in 1..10) {
                val delivered = minOf(cc.targetBps, 2_000_000)
                driveTick(cc, (i * 1_000).toLong(), delivered, 100)
            }
            assertTrue(cc.lossUncongestive)

            for (i in 11..14) {
                driveTick(cc, (i * 1_000).toLong(), 2_000_000, 0)
            }
            assertFalse(cc.lossUncongestive, "a clean window should clear the verdict")
            assertEquals(CcState.CLIMBING, cc.state)
        }

        test("backing_off_on_loss") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.tick(2_000_000, 0)
            val before = cc.targetBps

            cc.recordLoss(1_000, 100, 100)
            cc.tick(2_000_000, 100)
            assertEquals(CcState.BACKING_OFF, cc.state)
            assertTrue(cc.targetBps < before)
        }

        test("loss_window_evicts") {
            val cc = LinkCongestionState()
            cc.recordLoss(1_000, 100, 0)
            assertEquals(100, cc.lossPermille())
            cc.recordLoss(0, 0, LOSS_WINDOW_MS + 10)
            assertEquals(0, cc.lossPermille())
        }

        test("rtt_min_is_windowed_not_lifetime") {
            val cc = LinkCongestionState()
            cc.recordRtt(20.0, 0)
            for (t in 1_000..40_000 step 1_000) {
                cc.recordRtt(80.0, t.toLong())
            }
            assertTrue(
                cc.rttMinMs >= 70.0,
                "windowed rtt_min should follow the raised floor, got ${cc.rttMinMs}"
            )
        }

        test("rtt_min_holds_within_window") {
            val cc = LinkCongestionState()
            cc.recordRtt(20.0, 0)
            for (t in 1_000..10_000 step 1_000) {
                cc.recordRtt(80.0, t.toLong())
            }
            assertTrue(
                Math.abs(cc.rttMinMs - 20.0) < 1.0,
                "rtt_min should hold the true floor within the window, got ${cc.rttMinMs}"
            )
        }

        test("rtt_ewma_resets_after_2s_gap") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.recordRtt(200.0, 2_500)
            assertClose(200.0, cc.rttEwmaMs, 0.01)
        }

        test("rtt_ewma_weights_by_age") {
            val cc = LinkCongestionState()
            cc.recordRtt(100.0, 0)
            cc.recordRtt(200.0, 100)
            assertTrue(cc.rttEwmaMs < 110.0)
        }

        test("hai_kicks_in_when_rtt_is_stable") {
            val cc = LinkCongestionState()
            for (i in 0..9) {
                cc.recordRtt(50.0, (i * 100).toLong())
                cc.tick(2_000_000, (i * 100).toLong())
            }
            assertEquals(CcState.CLIMBING, cc.state)
            assertEquals(ClimbMode.HAI, cc.climbMode)
        }

        test("hai_yields_to_normal_when_rtt_is_jittery") {
            val cc = LinkCongestionState()
            for (i in 0..9) {
                val rtt = if (i % 2 == 0) 30.0 else 80.0
                cc.recordRtt(rtt, (i * 100).toLong())
                cc.tick(2_000_000, (i * 100).toLong())
            }
            assertEquals(CcState.CLIMBING, cc.state)
            assertEquals(ClimbMode.NORMAL, cc.climbMode)
        }

        test("fast_recovery_engages_after_backoff") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.tick(2_000_000, 0)

            cc.recordLoss(1_000, 100, 100)
            cc.tick(2_000_000, 100)
            assertEquals(CcState.BACKING_OFF, cc.state)

            cc.tick(2_000_000, 1_200)
            assertEquals(CcState.CLIMBING, cc.state)
            assertEquals(ClimbMode.FAST_RECOVERY, cc.climbMode)

            for (i in 1..FAST_RECOVERY_TICKS) {
                cc.tick(2_000_000, 1_200 + i.toLong())
            }
            assertEquals(CcState.CLIMBING, cc.state)
            assertTrue(cc.climbMode == ClimbMode.NORMAL || cc.climbMode == ClimbMode.HAI)
        }

        test("drain_triggers_on_high_rtt_inflation_no_loss") {
            val cc = LinkCongestionState()
            cc.recordRtt(20.0, 0)
            cc.tick(2_000_000, 0)

            for (i in 1..19) {
                cc.recordRtt(60.0, (i * 600).toLong())
                cc.tick(2_000_000, (i * 600).toLong())
            }
            assertTrue(cc.state == CcState.DRAIN || cc.state == CcState.HOLDING)
            if (cc.state == CcState.DRAIN) {
                assertTrue(cc.targetBps < 2_000_000)
            }
        }

        test("drain_then_recovery_path") {
            val cc = LinkCongestionState()
            cc.recordRtt(20.0, 0)
            cc.tick(2_000_000, 0)

            for (i in 1..14) {
                cc.recordRtt(60.0, (i * 600).toLong())
                cc.tick(2_000_000, (i * 600).toLong())
            }

            for (i in 15..29) {
                cc.recordRtt(20.0, (i * 600).toLong())
                cc.tick(2_000_000, (i * 600).toLong())
            }
            assertEquals(CcState.CLIMBING, cc.state)
            assertTrue(cc.rttEwmaMs < 30.0)
        }

        test("loss_ewma_latches_degraded_after_sustained_loss_and_clears_with_hysteresis") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)

            var t = 0L
            for (unused in 0..39) {
                t += 200
                cc.recordLoss(1_000, 800, t)
                cc.tick(2_000_000, t)
            }
            assertTrue(
                cc.snapshot().lossDegraded,
                "sustained high loss should latch the degraded verdict (ewma=${cc.lossEwma})"
            )

            for (unused in 0..59) {
                t += 200
                cc.recordLoss(1_000, 0, t)
                cc.tick(2_000_000, t)
            }
            assertFalse(
                cc.snapshot().lossDegraded,
                "recovered loss should clear the verdict (ewma=${cc.lossEwma})"
            )
        }

        test("outlier_burst_at_seed_is_clamped") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.tick(50_000_000, 0)
            assertTrue(
                cc.targetBps <= 5 * INITIAL_TARGET_BPS,
                "seed inflated to ${cc.targetBps} from a burst"
            )
            assertTrue(cc.targetBps >= INITIAL_TARGET_BPS)
        }

        test("outlier_burst_does_not_run_the_target_away") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            for (i in 0..7) {
                cc.recordRtt(50.0, (i * 100).toLong())
                cc.tick(2_000_000, (i * 100).toLong())
            }
            val before = cc.targetBps

            cc.recordRtt(50.0, 900)
            cc.tick(50_000_000, 900)
            assertTrue(
                cc.targetBps <= before.saturatingMul(4).coerceAtLeast(INITIAL_TARGET_BPS),
                "one burst tick inflated target to ${cc.targetBps} from $before"
            )
        }

        test("loss_ewma_does_not_latch_on_a_transient_spike") {
            val cc = LinkCongestionState()
            cc.recordRtt(50.0, 0)
            cc.recordLoss(1_000, 900, 200)
            cc.tick(2_000_000, 200)
            for (unused in 0..9) {
                cc.recordLoss(1_000, 0, 400)
            }
            cc.tick(2_000_000, 1_600)
            assertFalse(cc.snapshot().lossDegraded, "a single bad window must not demote the link")
        }

        test("observe_traffic_first_call_sets_baseline_without_sample") {
            val cc = LinkCongestionState()
            cc.observeTraffic(1_000_000, 5, 100)
            assertTrue(cc.lossPermille() == 0, "first call must not emit a sample")
        }

        test("observe_traffic_delta_flows_into_record_loss") {
            val cc = LinkCongestionState()
            cc.observeTraffic(0, 0, 0)
            cc.observeTraffic(1_000_000, 5, 100)
            val pm = cc.lossPermille()
            assertTrue(pm > 0, "loss permille should be non-zero after delta")
            assertTrue(pm < 20, "expected ~6 permille, got $pm")
        }

        test("observe_traffic_quiet_tick_with_naks_does_not_panic") {
            val cc = LinkCongestionState()
            cc.observeTraffic(1_000_000, 0, 0)
            cc.observeTraffic(1_000_000, 5, 100)
            val pm = cc.lossPermille()
            assertTrue(pm > 0, "loss with no fresh bytes should still register, got $pm")
            assertTrue(pm <= 1_000_000)
        }
    }
}

/// Drive one 1Hz tick: report `lossPm` permille of loss and
/// `deliveredBps` of throughput, at a flat RTT.
private fun driveTick(cc: LinkCongestionState, tMs: Long, deliveredBps: Long, lossPm: Int) {
    cc.recordRtt(50.0, tMs)
    cc.recordLoss(1_000, lossPm.toLong(), tMs)
    cc.tick(deliveredBps, tMs)
}

private fun Long.saturatingMul(factor: Int): Long {
    val result = this * factor
    return if (result < this) Long.MAX_VALUE else result
}
