// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/selection/mod.rs, enhanced.rs, classifier.rs
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.core.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.selection.*
import dev.abdulkadirozyurt.srtla.testkit.*

fun registerSelectionTests() {
    suite("Selection (mod.rs)") {
        test("test_select_connection_idx_classic") {
            // Test that classic mode always picks highest score
            val connections = createTestConnections(3)
            connections[0].inFlightPackets = 5 // Lower score
            connections[1].inFlightPackets = 0 // Highest score
            connections[2].inFlightPackets = 10 // Lowest score

            val config = ConfigSnapshot(mode = SchedulingMode.CLASSIC, qualityEnabled = false)
            val result = selectConnectionIdx(connections, 0, nowMs(), config)
            assertEquals(1, result, "Classic mode should pick highest score connection")
        }

        test("test_enhanced_switches_immediately_when_clearly_better") {
            // Regression guard for the removed switch cooldown. Selection must be
            // free to re-decide on every packet: `getScore()` counts queued packets
            // as in-flight, so routing a packet de-prioritises its own link.
            val connections = createTestConnections(3)
            connections[0].inFlightPackets = 5 // Currently selected, lower score
            connections[1].inFlightPackets = 0 // Far better score
            connections[2].inFlightPackets = 10 // Lowest score

            val config = ConfigSnapshot(mode = SchedulingMode.ENHANCED, qualityEnabled = true)
            val result = selectConnectionIdx(connections, 0, nowMs(), config)
            assertEquals(1, result, "Enhanced mode must switch to a clearly better link with no time-based delay")
        }

        test("test_enhanced_hysteresis_holds_when_gain_is_marginal") {
            // Switching is damped in score space, not time: a link that is better by
            // less than SWITCH_THRESHOLD (10%) does not win the packet.
            val connections = createTestConnections(3)
            connections[0].inFlightPackets = 20 // currently selected
            connections[1].inFlightPackets = 19 // marginally better
            connections[2].inFlightPackets = 40 // clearly worse

            val config = ConfigSnapshot(mode = SchedulingMode.ENHANCED, qualityEnabled = true)
            val result = selectConnectionIdx(connections, 0, nowMs(), config)
            assertEquals(0, result, "Enhanced mode should hold the current link when the alternative is <10% better")
        }

        test("test_select_connection_idx_empty") {
            val conns = emptyList<dev.abdulkadirozyurt.srtla.connection.SrtlaConnection>()
            val result = selectConnectionIdx(conns, null, 0, ConfigSnapshot(mode = SchedulingMode.ENHANCED, qualityEnabled = false))
            assertNull(result)
        }
    }

    suite("Selection (enhanced.rs)") {
        test("handover_needs_both_a_clear_margin_and_a_served_minimum") {
            // librist's field numbers: 126 vs 85 is noise between two failing
            // legs; 1162 vs 406 is a genuinely better path.
            assertFalse(soleCarrierHandover(126.0, 85.0, 10_000, 2000, 2.0))
            assertTrue(soleCarrierHandover(1162.0, 406.0, 10_000, 2000, 2.0))
            // ...but not before the incumbent has served its minimum.
            assertFalse(soleCarrierHandover(1162.0, 406.0, 1999, 2000, 2.0))
            // Exactly at the margin and exactly at the minimum both count.
            assertTrue(soleCarrierHandover(800.0, 400.0, 2000, 2000, 2.0))
        }

        test("handover_refuses_to_act_on_an_unmeasured_link") {
            // No RTT on either side is not evidence of a better path.
            assertFalse(soleCarrierHandover(0.0, 400.0, 10_000, 2000, 2.0))
            assertFalse(soleCarrierHandover(1200.0, 0.0, 10_000, 2000, 2.0))
            assertFalse(soleCarrierHandover(Double.NaN, 400.0, 10_000, 2000, 2.0))
        }

        test("no_election_while_a_healthy_link_exists") {
            val conns = createTestConnections(2)
            privFailingLink(conns[0], 900.0)
            val now = 100_000L
            val result = electSoleCarrier(conns, now, true)
            assertNull(result)
            assertFalse(conns[0].isSoleCarrier())
            assertFalse(conns[1].isSoleCarrier())
            assertFalse(conns[0].isSoleCarrierExcluded())
        }

        test("every_link_failing_elects_one_and_holds_it") {
            val conns = createTestConnections(2)
            privFailingLink(conns[0], 900.0)
            privFailingLink(conns[1], 1000.0)
            val now = 100_000L

            // Lowest smoothed RTT wins the first election. Taking the role when
            // nobody held it is not a handover, so the churn counter stays at 0.
            val result = electSoleCarrier(conns, now, false)
            assertEquals(0, result)
            assertTrue(conns[0].isSoleCarrier())
            assertTrue(conns[1].isSoleCarrierExcluded())
            assertEquals(0L, conns[0].soleCarrierElections())

            // Link 1 pulls marginally ahead. Re-running the election every packet
            // on the instantaneous measurement is exactly what made librist's
            // payload path swap legs once a second, so the role must not move.
            privFailingLink(conns[0], 1100.0)
            privFailingLink(conns[1], 900.0)
            for (tick in 0..9) {
                val r = electSoleCarrier(conns, now + tick * 1000, false)
                assertEquals(0, r, "a marginally better sibling must not take the role")
            }
            assertEquals(0L, conns[0].soleCarrierElections(), "no churn")
        }

        test("a_clearly_better_link_takes_the_role_once_the_hold_expires") {
            val conns = createTestConnections(2)
            privFailingLink(conns[0], 1200.0)
            privFailingLink(conns[1], 1300.0)
            val now = 100_000L
            val result = electSoleCarrier(conns, now, false)
            assertEquals(0, result)

            // Link 1 is now several times better — but the incumbent has only
            // just taken the role.
            privFailingLink(conns[1], 300.0)
            val r1 = electSoleCarrier(conns, now + SOLE_CARRIER_MIN_HOLD_MS - 1, false)
            assertEquals(0, r1)

            // Past the minimum hold, the handover goes through.
            val t = now + SOLE_CARRIER_MIN_HOLD_MS
            val r2 = electSoleCarrier(conns, t, false)
            assertEquals(1, r2)
            assertTrue(conns[1].isSoleCarrier())
            assertFalse(conns[0].isSoleCarrier())
            assertEquals(1L, conns[1].soleCarrierElections())

            // The link that just came back in ramps its share up rather than
            // resuming at the score its idle time inflated.
            assertTrue(conns[1].rejoinRampMultiplier(t) < 1.0)
        }

        test("the_role_moves_off_a_link_that_stops_being_a_candidate") {
            val conns = createTestConnections(2)
            privFailingLink(conns[0], 900.0)
            privFailingLink(conns[1], 5000.0)
            val now = 100_000L
            val result = electSoleCarrier(conns, now, false)
            assertEquals(0, result)

            // The incumbent stalls out. Stickiness must not outrank a link
            // being unusable, even though the sibling measures far worse.
            conns[0].stallGated = true
            val r2 = electSoleCarrier(conns, now + 100, false)
            assertEquals(1, r2)
        }

        test("ending_the_election_ramps_the_excluded_link_back_in") {
            val conns = createTestConnections(2)
            privFailingLink(conns[0], 900.0)
            privFailingLink(conns[1], 1000.0)
            val now = 100_000L
            electSoleCarrier(conns, now, false)
            assertTrue(conns[1].isSoleCarrierExcluded())

            // A link recovers, so the election ends and everyone competes again.
            val t = now + 5000
            val result = electSoleCarrier(conns, t, true)
            assertNull(result)
            assertFalse(conns[1].isSoleCarrierExcluded())
            assertTrue(
                conns[1].rejoinRampMultiplier(t) < 1.0,
                "a link released from exclusion drained while out, so it must ramp rather than seize the stream on its inflated score"
            )
        }

        test("a_flapping_sibling_does_not_churn_the_role_or_restart_the_hold") {
            // A third link's `weak` flag flipping at classifier cadence tears the
            // election down and rebuilds it. The same link keeps the role each
            // time, so nothing has actually happened: the churn counter must stay
            // flat and — the part that bites — the minimum hold must not restart.
            val conns = createTestConnections(2)
            privFailingLink(conns[0], 900.0)
            privFailingLink(conns[1], 1000.0)
            var now = 100_000L

            val r1 = electSoleCarrier(conns, now, false)
            assertEquals(0, r1)

            for (i in 0..4) {
                now += 500
                // A link recovers: election off.
                val r = electSoleCarrier(conns, now, true)
                assertNull(r)
                now += 500
                // ...and fails again: election back on, same winner.
                val r2 = electSoleCarrier(conns, now, false)
                assertEquals(0, r2)
            }
            assertEquals(0L, conns[0].soleCarrierElections(), "re-forming around the same link is not a handover")

            // 5s of flapping later, a clearly better challenger must be able to
            // take the role — which it can only do if the hold kept accumulating.
            privFailingLink(conns[1], 100.0)
            val r3 = electSoleCarrier(conns, now, false)
            assertEquals(1, r3)
            assertEquals(1L, conns[1].soleCarrierElections(), "a real handover")
        }

        test("an_in_progress_ramp_is_never_restarted") {
            // Same flapping, seen from the excluded sibling: each teardown ends its
            // exclusion and would re-arm a ramp. Restarting it every cycle would
            // pin the link at the ramp floor for as long as the flapping lasts.
            val conns = createTestConnections(2)
            privFailingLink(conns[0], 900.0)
            privFailingLink(conns[1], 1000.0)
            val start = 100_000L

            val result = electSoleCarrier(conns, start, false)
            assertEquals(0, result)
            assertTrue(conns[1].isSoleCarrierExcluded())
            electSoleCarrier(conns, start + 100, true) // released, ramp armed
            val afterFirst = conns[1].rejoinRampMultiplier(start + 100)
            assertTrue(afterFirst < 1.0, "release must arm a ramp")

            // Flap several more times well inside the ramp window.
            var now = start + 100
            for (i in 0..3) {
                now += 200
                electSoleCarrier(conns, now, false)
                now += 200
                electSoleCarrier(conns, now, true)
            }

            // The ramp has been climbing the whole time, not resetting to the floor.
            assertTrue(
                conns[1].rejoinRampMultiplier(now) > afterFirst,
                "a re-arm inside an active ramp must not restart it"
            )
        }

        test("lifting_a_quality_exclusion_ramps_the_link_back_in") {
            // The exclusion drains the link exactly like the stall gate does, so
            // its falling edge needs the same ramp.
            val conns = createTestConnections(2)
            val now = nowMs()
            conns[0].weak = true
            conns[0].weakReason = WeakReason.HIGH_RTT
            conns[1].inFlightPackets = 40

            selectEnhanced(conns, null, now, true)
            assertTrue(conns[0].isQualityExcluded())

            // The delay verdict clears.
            conns[0].weak = false
            conns[0].weakReason = WeakReason.HEALTHY
            val later = now + 10
            selectEnhanced(conns, null, later, true)

            assertFalse(conns[0].isQualityExcluded())
            assertTrue(
                conns[0].rejoinRampMultiplier(later) < 1.0,
                "a link released from a quality exclusion must ramp back in"
            )
        }

        test("a_late_link_is_held_out_of_the_rotation_entirely") {
            val conns = createTestConnections(2)
            val now = nowMs()
            conns[0].weak = true
            conns[0].weakReason = WeakReason.HIGH_RTT
            // Link 0 would win on raw score: the healthy link is the busy one.
            conns[1].inFlightPackets = 40

            val result = selectEnhanced(conns, null, now, true)
            assertEquals(1, result)
            assertTrue(
                conns[0].isQualityExcluded(),
                "a late link must be held out, not trickled: every unique sequence number on it is a hole the receiver waits for"
            )
        }

        test("an_under_used_link_keeps_its_trickle_of_real_traffic") {
            val conns = createTestConnections(2)
            val now = nowMs()
            conns[0].weak = true
            conns[0].weakReason = WeakReason.LOW_SHARE
            conns[1].inFlightPackets = 40

            val result = selectEnhanced(conns, null, now, true)
            assertEquals(1, result)
            assertFalse(
                conns[0].isQualityExcluded(),
                "share weakness is not lateness — the link needs real traffic to earn back the share that clears the verdict"
            )
        }

        test("a_loss_degraded_link_is_held_out_whatever_the_weak_reason") {
            val conns = createTestConnections(2)
            val now = nowMs()
            conns[0].lossDegraded = true
            conns[1].inFlightPackets = 40

            val result = selectEnhanced(conns, null, now, true)
            assertEquals(1, result)
            assertTrue(conns[0].isQualityExcluded())
        }

        test("nothing_is_held_out_when_no_healthy_link_can_carry") {
            // Both links late: the exclusion must not fire on every link at once.
            // One is elected to carry and the other is held out, but a link is
            // always returned.
            val conns = createTestConnections(2)
            val now = nowMs()
            for (c in conns) {
                c.weak = true
                c.weakReason = WeakReason.HIGH_RTT
            }
            val picked = selectEnhanced(conns, null, now, true)
            assertNotNull(picked, "selection must never drop the packet")
            assertFalse(conns[picked!!].isQualityExcluded())
            assertTrue(conns[picked].isSoleCarrier())
        }

        test("cap_no_signal_returns_unity") {
            val c = createTestConnection()
            // cc_target_bps default 0 → no cap.
            assertClose(1.0, ccSoftCapMultiplier(c), 1e-9)
        }

        test("cap_idle_link_returns_unity") {
            val c = createTestConnection()
            c.ccTargetBps = 1_000_000L
            c.bitrate.currentBitrateBps = 0.0
            // Plenty of headroom on an idle link.
            assertClose(1.0, ccSoftCapMultiplier(c), 1e-9)
        }

        test("cap_at_target_falls_to_floor") {
            val c = createTestConnection()
            c.ccTargetBps = 1_000_000L
            c.bitrate.currentBitrateBps = 1_000_000.0
            // Saturated → floor multiplier (10%).
            val m = ccSoftCapMultiplier(c)
            assertClose(CC_SOFT_CAP_FLOOR, m, 1e-9, "got $m")
        }

        test("in_flight_cap_no_signal") {
            // cc_target_bps == 0 → cap inactive regardless of in_flight.
            assertNull(inFlightCapPackets(0, 50.0))
            val c = createTestConnection()
            c.ccTargetBps = 0
            c.inFlightPackets = 10_000
            assertFalse(inFlightCapExceeded(c))
        }

        test("in_flight_cap_floors_at_one") {
            // 100 kbps over a 20 ms RTT: BDP = 1e5 * 0.02 / 8 = 250 bytes,
            // x1.5 = 375 bytes < one packet, so the cap floors at 1.
            val cap = inFlightCapPackets(100_000L, 20.0)
            assertNotNull(cap)
            assertEquals(1, cap)
        }

        test("in_flight_cap_scales_with_bdp") {
            // 10 Mbps over 50 ms: BDP = 1e7 * 0.05 / 8 = 62_500 bytes, x1.5
            // = 93_750, / 1316 ≈ 71 packets.
            val cap = inFlightCapPackets(10_000_000L, 50.0)
            assertNotNull(cap)
            assertTrue((68..74).contains(cap!!), "got $cap")
            // Same rate at 4x the RTT gives ~4x the cap (path-relative).
            val capHighRtt = inFlightCapPackets(10_000_000L, 200.0)
            assertNotNull(capHighRtt)
            assertTrue(capHighRtt!! > cap!! * 3, "got $capHighRtt vs $cap")
        }

        test("in_flight_cap_engaged_when_exceeded") {
            val c = createTestConnection()
            c.ccTargetBps = 10_000_000L
            val cap = inFlightCapPackets(c.ccTargetBps, c.getRttMinMs())
            assertNotNull(cap)
            c.inFlightPackets = cap!!
            assertFalse(inFlightCapExceeded(c), "at cap is allowed, only above triggers")
            c.inFlightPackets = cap!! + 1
            assertTrue(inFlightCapExceeded(c))
        }

        test("cap_half_target_returns_half") {
            val c = createTestConnection()
            c.ccTargetBps = 1_000_000L
            c.bitrate.currentBitrateBps = 500_000.0
            val m = ccSoftCapMultiplier(c)
            assertClose(0.5, m, 0.01, "got $m")
        }
    }

    suite("Selection (classifier.rs)") {
        test("target_tier_math") {
            assertEquals(400, targetBestDelayMs(1000))
            assertEquals(500, targetSafeDelayMs(1000))
            assertEquals(600, targetMaxDelayMs(1000))

            // Caps
            assertEquals(TARGET_BEST_SAFE_CAP_MS, targetBestDelayMs(10_000))
            assertEquals(TARGET_BEST_SAFE_CAP_MS, targetSafeDelayMs(10_000))
            assertEquals(TARGET_MAX_CAP_MS, targetMaxDelayMs(10_000))
        }

        test("budget_floor_and_ceiling") {
            assertEquals(MIN_BUDGET_MS, deriveMaxDelayBudget(50))
            assertEquals(MAX_BUDGET_MS, deriveMaxDelayBudget(2000))
            assertEquals(1500, deriveMaxDelayBudget(500))
        }

        test("the_peers_declared_buffer_wins_over_the_rtt_estimate") {
            // A 4s receive buffer is an 8s round-trip budget, whatever our own RTT
            // happens to be. Left to the estimate, a 200ms link would have produced
            // a 600ms budget and judged everything against that.
            assertEquals(8000, delayBudgetMs(4000, 200))
            assertEquals(600, deriveMaxDelayBudget(200))

            // Nothing declared: the estimate still stands.
            assertEquals(deriveMaxDelayBudget(500), delayBudgetMs(0, 500))

            // The estimate's own ceiling must not apply here.
            assertTrue(delayBudgetMs(4000, 200) > MAX_BUDGET_MS)
        }

        test("a_declared_buffer_is_bounded_at_both_ends") {
            // These 16 bits come off the network.
            assertEquals(MIN_BUDGET_MS, delayBudgetMs(10, 200))
            assertEquals(MAX_NEGOTIATED_BUDGET_MS, delayBudgetMs(65535, 200))
        }

        test("a_link_is_late_against_the_real_buffer_not_the_guess") {
            // Two links at 900ms and 50ms. The estimate derives its budget from the
            // *longest* RTT — 3 x 900 = 2700, max tier 1620 — so the slow link
            // clears a bar it set itself.
            val conns = createTestConnections(2)
            conns[0].bitrate.currentBitrateBps = 1_000_000.0
            privSetRtt(conns[0], 50.0)
            conns[1].bitrate.currentBitrateBps = 1_000_000.0
            privSetRtt(conns[1], 900.0)
            val slow = conns[1].connId

            // Guessing: the slow link sets its own bar and passes.
            var filter = WeakLinkFilter()
            for (i in 0..WEAK_SUSTAIN_TICKS) {
                filter.classify(conns, 0)
            }
            var (weak, _) = privVerdict(filter.classify(conns, 0), slow)
            assertFalse(weak, "the RTT estimate cannot see that 900ms is too slow")

            // A 500ms receive buffer: budget 1000, max tier 600. 900ms of round
            // trip is 450ms one way, most of the buffer gone before a
            // retransmission is even possible.
            filter = WeakLinkFilter()
            for (i in 0..WEAK_SUSTAIN_TICKS) {
                filter.classify(conns, 500)
            }
            var result = filter.classify(conns, 500)
            var verdict = privVerdict(result, slow)
            assertTrue(verdict.first, "a link that busts the real buffer must be weak")
            assertEquals(WeakReason.HIGH_RTT, verdict.second)

            // A 4s buffer over the same links: 900ms is comfortably inside it.
            filter = WeakLinkFilter()
            for (i in 0..WEAK_SUSTAIN_TICKS) {
                filter.classify(conns, 4000)
            }
            verdict = privVerdict(filter.classify(conns, 4000), slow)
            assertFalse(verdict.first, "a generous buffer must not condemn the same link")
        }

        test("pick_tier_picks_best_when_85pct_fits") {
            val tier = pickTier(1000.0, 900.0, 950.0, 1000.0, 100, 200, 300)
            assertEquals(100, tier)
        }

        test("pick_tier_falls_back_to_safe") {
            val tier = pickTier(1000.0, 100.0, 900.0, 1000.0, 100, 200, 300)
            assertEquals(200, tier)
        }

        test("pick_tier_falls_back_to_max") {
            val tier = pickTier(1000.0, 0.0, 0.0, 100.0, 100, 200, 300)
            assertEquals(300, tier)
        }

        test("empty_classification_returns_bypassed") {
            val filter = WeakLinkFilter()
            val result = filter.classify(emptyList(), 0)
            assertEquals(0, result.selectedDelayMs)
            assertTrue(result.perLink.isEmpty())
        }

        test("probation_re_tests_a_share_starved_link") {
            val conns = privStarvedPair()
            val id = conns[1].connId
            val filter = WeakLinkFilter()

            // Share-weak every tick, and stays gated right up to the trigger tick.
            for (tick in 0 until PROBATION_INTERVAL_TICKS) {
                val r = filter.classify(conns, 0)
                val (weak, reason) = privVerdict(r, id)
                assertTrue(weak, "tick $tick: starved link should be weak")
                assertEquals(WeakReason.LOW_SHARE, reason)
            }

            // Window opens: real traffic is the only way to re-prove share.
            val (weak, reason) = privVerdict(filter.classify(conns, 0), id)
            assertFalse(weak, "probation must re-test the starved link")
            assertEquals(WeakReason.HEALTHY, reason)
        }

        test("a_delay_verdict_cancels_the_probation_window") {
            // The hole this closes: probation used to override whatever the tick
            // computed, so a link that went late *during* its re-test kept a full
            // share of unique payload until the window ran out.
            val conns = privStarvedPair()
            val id = conns[1].connId
            val filter = WeakLinkFilter()

            for (i in 0 until PROBATION_INTERVAL_TICKS) {
                filter.classify(conns, 0)
            }
            // First window tick: still fast, so the re-test proceeds.
            val (weak, _) = privVerdict(filter.classify(conns, 0), id)
            assertFalse(weak, "precondition: the window opened")

            // Loaded at last, the link turns out to be badly late. A delay verdict
            // needs WEAK_SUSTAIN_TICKS consecutive samples to latch.
            privSetRtt(conns[1], 3000.0)
            var verdict = privVerdict(filter.classify(conns, 0), id)
            assertFalse(verdict.first, "one late sample is still just a blip")

            verdict = privVerdict(filter.classify(conns, 0), id)
            assertTrue(verdict.first, "a sustained delay verdict must end the re-test")
            assertEquals(WeakReason.HIGH_RTT, verdict.second)

            // ...and it is cancelled, not merely suspended: the remaining window
            // ticks must not resume handing the link unique payload.
            for (tick in 0 until PROBATION_WINDOW_TICKS) {
                val (w, r) = privVerdict(filter.classify(conns, 0), id)
                assertTrue(w, "tick $tick after cancellation must stay gated")
                assertEquals(WeakReason.HIGH_RTT, r)
            }
        }

        test("probation_backoff_doubles_and_saturates") {
            // First window is free; each failure thereafter doubles the wait.
            assertEquals(2, probationBackoffNext(0))
            assertEquals(2, probationBackoffNext(1))
            assertEquals(4, probationBackoffNext(2))
            assertEquals(16, probationBackoffNext(8))
            // ...up to the ceiling, and no further.
            assertEquals(PROBATION_BACKOFF_MAX, probationBackoffNext(PROBATION_BACKOFF_MAX))
        }

        test("a_second_re_test_waits_twice_as_long_as_the_first") {
            // The hole this closes: the interval was fixed, so a link that could
            // never carry its share drew a full window of unique payload every
            // PROBATION_INTERVAL_TICKS forever.
            val conns = privStarvedPair()
            val id = conns[1].connId
            val filter = WeakLinkFilter()

            // First re-test, at the base interval.
            for (i in 0 until PROBATION_INTERVAL_TICKS) {
                filter.classify(conns, 0)
            }
            var (weak, _) = privVerdict(filter.classify(conns, 0), id)
            assertFalse(weak, "precondition: the first window opened")
            // Run the window out. The link is still starved, so it fails.
            for (i in 1 until PROBATION_WINDOW_TICKS) {
                filter.classify(conns, 0)
            }

            // Where the old code re-tested again, the link must stay gated.
            for (tick in 0 until PROBATION_INTERVAL_TICKS) {
                val (w, _) = privVerdict(filter.classify(conns, 0), id)
                assertTrue(w, "tick $tick: a failed re-test must not retry on time")
            }
            // It gets its second chance only after the doubled interval.
            for (tick in 0 until PROBATION_INTERVAL_TICKS) {
                val (w, _) = privVerdict(filter.classify(conns, 0), id)
                assertTrue(w, "tick $tick: still inside the doubled interval")
            }
            val (w, _) = privVerdict(filter.classify(conns, 0), id)
            assertFalse(w, "the doubled interval must still re-test eventually")
        }

        test("a_re_test_that_holds_resets_the_backoff") {
            // A link recovering from a transient dip must not inherit the
            // escalation earned by whatever starved it earlier.
            val conns = privStarvedPair()
            val id = conns[1].connId
            val filter = WeakLinkFilter()

            // Earn and fail one window, escalating to 2x.
            for (i in 0..PROBATION_INTERVAL_TICKS) {
                filter.classify(conns, 0)
            }
            for (i in 1 until PROBATION_WINDOW_TICKS) {
                filter.classify(conns, 0)
            }
            assertEquals(2, filter.probationBackoff[id], "precondition: the failed window escalated")

            // The link recovers and carries a real share outside any window.
            conns[1].bitrate.currentBitrateBps = 900_000.0
            var (weak, _) = privVerdict(filter.classify(conns, 0), id)
            assertFalse(weak, "a link at full share is not weak")
            assertEquals(1, filter.probationBackoff[id], "a re-test that held must clear the escalation")
        }

        test("a_cancelled_re_test_keeps_its_escalation") {
            // Cancellation is the *worst* outcome — the link proved it goes late
            // under load — so it must not be cheaper than simply staying starved.
            val conns = privStarvedPair()
            val id = conns[1].connId
            val filter = WeakLinkFilter()

            for (i in 0..PROBATION_INTERVAL_TICKS) {
                filter.classify(conns, 0)
            }
            // Loaded at last, the link turns out to be late; the window cancels.
            privSetRtt(conns[1], 3000.0)
            for (i in 0..WEAK_SUSTAIN_TICKS) {
                filter.classify(conns, 0)
            }
            var (weak, reason) = privVerdict(filter.classify(conns, 0), id)
            assertTrue(weak, "precondition: the re-test was cancelled")
            assertEquals(WeakReason.HIGH_RTT, reason)
            assertEquals(2, filter.probationBackoff[id], "a link gated for lateness must keep the escalation it earned")
        }

        test("a_link_that_is_late_never_earns_a_probation_window") {
            // Delay weakness must not arm probation in the first place: it clears
            // from live RTT, which keepalive echoes and probe ACKs keep supplying
            // even while the link is held out of the rotation.
            val conns = privStarvedPair()
            val id = conns[1].connId
            privSetRtt(conns[1], 3000.0)
            val filter = WeakLinkFilter()

            for (tick in 0 until (PROBATION_INTERVAL_TICKS * 2)) {
                var (weak, reason) = privVerdict(filter.classify(conns, 0), id)
                assertTrue(weak, "tick $tick: a late link stays weak")
                if (tick >= WEAK_SUSTAIN_TICKS) {
                    assertEquals(WeakReason.HIGH_RTT, reason, "tick $tick: and stays late, never re-tested")
                }
            }
        }
    }
}

// ── Private test helpers ────────────────────────────────────────────────────

/** Mark a link quality-gated with a given smoothed RTT. */
private fun privFailingLink(c: dev.abdulkadirozyurt.srtla.connection.SrtlaConnection, rttMs: Double) {
    c.weak = true
    privSetRtt(c, rttMs)
}

/** Set a connection's RTT to a stable value. */
private fun privSetRtt(c: dev.abdulkadirozyurt.srtla.connection.SrtlaConnection, rttMs: Double) {
    c.rtt.kalmanRtt.update(rttMs)
    // Kalman needs a couple of samples to sit on the value.
    for (i in 0..11) {
        c.rtt.kalmanRtt.update(rttMs)
    }
}

/** One healthy link carrying the stream plus one starved link. */
private fun privStarvedPair(): MutableList<dev.abdulkadirozyurt.srtla.connection.SrtlaConnection> {
    val conns = createTestConnections(2)
    conns[0].bitrate.currentBitrateBps = 1_000_000.0
    privSetRtt(conns[0], 50.0)
    conns[1].bitrate.currentBitrateBps = 10_000.0
    privSetRtt(conns[1], 50.0)
    return conns
}

/** Extract a link's weak verdict from a classification result. */
private fun privVerdict(result: ClassificationResult, connId: Long): Pair<Boolean, WeakReason> {
    val e = result.perLink.find { it.connId == connId } ?: error("link classified")
    return e.weak to e.reason
}

private const val TARGET_BEST_SAFE_CAP_MS = 2500
private const val TARGET_MAX_CAP_MS = 5000
