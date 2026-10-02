// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/tests/stall_deselect_tests.rs
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.connection.SrtlaIncoming
import dev.abdulkadirozyurt.srtla.connection.deliveryProofIsTimely
import dev.abdulkadirozyurt.srtla.core.CONN_TIMEOUT_MS
import dev.abdulkadirozyurt.srtla.core.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.core.SILENCE_PULL_FLOOR_MS
import dev.abdulkadirozyurt.srtla.core.STALL_ACK_STALE_MS
import dev.abdulkadirozyurt.srtla.core.STALL_MIN_IN_FLIGHT_PACKETS
import dev.abdulkadirozyurt.srtla.core.STALL_REJOIN_BACKOFF_MAX
import dev.abdulkadirozyurt.srtla.core.STALL_REJOIN_DWELL_MULT
import dev.abdulkadirozyurt.srtla.core.STALL_PROBE_ONE_IN_N
import dev.abdulkadirozyurt.srtla.core.STALL_REJOIN_PROBATION_MULT
import dev.abdulkadirozyurt.srtla.core.STALL_STALE_FLOOR_MS
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.selection.selectConnectionIdx
import dev.abdulkadirozyurt.srtla.selection.inFlightCapExceeded
import dev.abdulkadirozyurt.srtla.sender.SequenceTracker
import dev.abdulkadirozyurt.srtla.sender.ClientSink
import dev.abdulkadirozyurt.srtla.sender.processConnectionEvents
import dev.abdulkadirozyurt.srtla.testkit.assertEquals
import dev.abdulkadirozyurt.srtla.testkit.assertFalse
import dev.abdulkadirozyurt.srtla.testkit.assertTrue
import dev.abdulkadirozyurt.srtla.testkit.suite

/**
 * Tests for the stalled-link deselect guard (`stall_deselect`, default on).
 *
 * The guard excludes a link whose in-flight backlog is high while its last
 * delivery proof (earned-ACK or keepalive-RTT sample) has gone stale, but only
 * when a healthier link can carry the traffic. It is a selection penalty only:
 * it never mutates liveness state. Gating latches asymmetrically: it engages
 * the instant the stall signal fires, and releases only after an
 * uninterrupted run of fresh delivery proof spanning the rejoin dwell (no
 * blind reprobe, no single-sample flap). The staleness window is
 * RTT-adaptive between a floor and the configured ceiling.
 */
fun registerStallDeselectTests() {
    suite("stall_deselect") {
        // Helper: mark a connection as a stalled black hole at `now`.
        // A backlog at the stall threshold whose last delivery proof is older
        // than the staleness window. Kept at exactly the threshold so its raw
        // capacity score still beats a healthier link — proving the guard works.
        fun makeStalled(conn: SrtlaConnection, now: Long) {
            conn.inFlightPackets = STALL_MIN_IN_FLIGHT_PACKETS
            conn.lastAckOrRttSampleMs = now.satSub(STALL_ACK_STALE_MS + 1000)
        }

        // Helper: a busy-but-healthy link with a larger backlog and fresh proof.
        fun makeHealthyBusy(conn: SrtlaConnection, now: Long) {
            conn.inFlightPackets = STALL_MIN_IN_FLIGHT_PACKETS * 2
            conn.lastAckOrRttSampleMs = now
        }

        fun enhanced(): ConfigSnapshot =
            ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
            )

        fun dwellMs(): Long =
            STALL_ACK_STALE_MS * STALL_REJOIN_DWELL_MULT

        test("stalled_link_is_skipped_when_a_healthy_alternative_exists") {
            val conns = createTestConnections(2)
            val now = nowMs()

            makeStalled(conns[0], now)
            makeHealthyBusy(conns[1], now)

            val selected = selectConnectionIdx(conns, null, now, enhanced())
            assertEquals(
                selected,
                1,
                "the stalled link must be deselected in favour of the healthy one"
            )
        }

        test("gating_never_mutates_liveness_state") {
            val conns = createTestConnections(2)
            val now = nowMs()

            makeStalled(conns[0], now)
            conns[1].inFlightPackets = 4

            selectConnectionIdx(conns, null, now, enhanced())

            assertTrue(conns[0].connected, "gating must not clear `connected`")
            assertTrue(
                conns[0].lastReceived != null,
                "gating must not clear `lastReceived`"
            )
            assertFalse(
                conns[0].isTimedOut(nowMs()),
                "a stall-gated link must never be treated as timed out"
            )
        }

        test("all_stalled_falls_back_to_best_never_none") {
            val conns = createTestConnections(3)
            val now = nowMs()

            for (c in conns) {
                makeStalled(c, now)
            }

            val selected = selectConnectionIdx(conns, null, now, enhanced())
            assertTrue(
                selected != null,
                "with every link stalled, selection must still return a link"
            )
        }

        test("a_link_with_no_delivery_proof_yet_is_not_stalled") {
            val conns = createTestConnections(2)
            val now = nowMs()

            conns[0].inFlightPackets = STALL_MIN_IN_FLIGHT_PACKETS + 8
            conns[0].lastAckOrRttSampleMs = 0 // no proof yet
            conns[1].inFlightPackets = 4

            assertFalse(
                conns[0].isStalled(now, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS),
                "a link with no delivery proof yet must not be classed as stalled"
            )
            selectConnectionIdx(conns, null, now, enhanced())
            assertFalse(conns[0].stallGated, "sample==0 link must not be gated")
        }

        test("a_fresh_delivery_proof_ungates_the_link") {
            val conns = createTestConnections(1)
            val now = nowMs()

            makeStalled(conns[0], now)
            assertTrue(
                conns[0].isStalled(now, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            )

            conns[0].lastAckOrRttSampleMs = now
            assertFalse(
                conns[0].isStalled(now, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS),
                "a fresh delivery proof must clear the stall immediately"
            )
        }

        test("guard_off_leaves_selection_unchanged") {
            val conns = createTestConnections(2)
            val now = nowMs()

            makeStalled(conns[0], now)
            makeHealthyBusy(conns[1], now)

            val config = ConfigSnapshot(
                mode = SchedulingMode.CLASSIC,
                qualityEnabled = false,
                stallDeselect = false,
            )
            val selected = selectConnectionIdx(conns, null, now, config)
            assertEquals(
                selected,
                0,
                "with the guard off, the stalled link's raw score must win as before"
            )
            assertFalse(conns[0].stallGated)
        }

        test("classic_mode_also_deselects_stalled_links") {
            val conns = createTestConnections(2)
            val now = nowMs()

            makeStalled(conns[0], now)
            makeHealthyBusy(conns[1], now)

            val config = ConfigSnapshot(
                mode = SchedulingMode.CLASSIC,
                qualityEnabled = false,
            )
            val selected = selectConnectionIdx(conns, null, now, config)
            assertEquals(
                selected,
                1,
                "classic mode must also skip the stalled link when the guard is on"
            )
        }

        test("a_backlog_below_threshold_is_not_stalled") {
            val conns = createTestConnections(1)
            val now = nowMs()

            conns[0].inFlightPackets = STALL_MIN_IN_FLIGHT_PACKETS - 1
            conns[0].lastAckOrRttSampleMs = now.satSub(STALL_ACK_STALE_MS + 1000)
            assertFalse(
                conns[0].isStalled(now, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS),
                "a link below the in-flight threshold must not be stalled regardless of staleness"
            )
        }

        test("rejoin_requires_sustained_proof_not_a_single_sample") {
            val conns = createTestConnections(1)
            val now = nowMs()
            val c = conns[0]

            makeStalled(c, now)
            c.updateStallLatch(now, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            assertTrue(
                c.stallLatched(),
                "latch must engage the instant the stall fires"
            )
            assertEquals(c.stallGateEvents(), 1L)

            c.inFlightPackets = 0
            c.lastAckOrRttSampleMs = now
            c.updateStallLatch(now, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            assertTrue(
                c.stallLatched(),
                "a single fresh proof must not release the latch"
            )

            val mid = now + dwellMs() / 2
            c.lastAckOrRttSampleMs = mid
            c.updateStallLatch(mid, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            assertTrue(c.stallLatched(), "mid-dwell the latch must still hold")

            val done = now + dwellMs()
            c.lastAckOrRttSampleMs = done
            c.updateStallLatch(done, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            assertFalse(
                c.stallLatched(),
                "sustained proof across the dwell must release the latch"
            )
            assertEquals(
                c.stallGateEvents(),
                1L,
                "release must not bump the counter"
            )
        }

        test("proof_lapse_resets_the_rejoin_dwell") {
            val conns = createTestConnections(1)
            val now = nowMs()
            val c = conns[0]

            makeStalled(c, now)
            c.updateStallLatch(now, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            c.inFlightPackets = 0

            val t1 = now + 100
            c.lastAckOrRttSampleMs = t1
            c.updateStallLatch(t1, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            assertTrue(c.stallLatched())

            val t2 = t1 + STALL_ACK_STALE_MS
            c.updateStallLatch(t2, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            assertTrue(c.stallLatched(), "stale proof mid-run must keep the latch")

            val t3 = t2 + 100
            c.lastAckOrRttSampleMs = t3
            c.updateStallLatch(t3, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            val before = t3 + dwellMs() - 1
            c.lastAckOrRttSampleMs = before
            c.updateStallLatch(before, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            assertTrue(
                c.stallLatched(),
                "the dwell must restart from the new run, not the first sample ever"
            )
            val after = t3 + dwellMs()
            c.lastAckOrRttSampleMs = after
            c.updateStallLatch(after, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            assertFalse(c.stallLatched())
        }

        fun cycleUntilRejoin(
            c: SrtlaConnection,
            start: Long,
            limitMs: Long,
        ): Long? {
            makeStalled(c, start)
            c.updateStallLatch(start, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
            assertTrue(c.stallLatched(), "precondition: the latch engaged")
            c.inFlightPackets = 0
            var t = start
            while (t <= start + limitMs) {
                t += 100
                c.lastAckOrRttSampleMs = t
                c.updateStallLatch(t, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS)
                if (!c.stallLatched()) {
                    return t
                }
            }
            return null
        }

        test("a_rejoin_that_immediately_re_stalls_doubles_the_dwell") {
            val conns = createTestConnections(1)
            val now = nowMs()
            val c = conns[0]

            val first = cycleUntilRejoin(c, now, dwellMs() * 4)!!
            assertTrue(
                first - now <= dwellMs() + 200,
                "the first rejoin must use the base dwell"
            )
            assertEquals(c.stallRejoinBackoff(), 1, "the first gate is free")

            val second = cycleUntilRejoin(c, first + 100, dwellMs() * 8)!!
            assertEquals(
                c.stallRejoinBackoff(),
                2,
                "a rejoin that did not hold must double the dwell"
            )
            assertTrue(
                second - first > dwellMs(),
                "the second rejoin must wait longer than the base dwell: took ${second - first}ms"
            )

            val third = cycleUntilRejoin(c, second + 100, dwellMs() * 16)!!
            assertEquals(c.stallRejoinBackoff(), 4)
            assertTrue(
                third - second > 2 * dwellMs(),
                "the third wait must exceed the doubled dwell: took ${third - second}ms"
            )
        }

        test("a_rejoin_that_holds_clears_the_backoff") {
            val conns = createTestConnections(1)
            val now = nowMs()
            val c = conns[0]

            val first = cycleUntilRejoin(c, now, dwellMs() * 4)!!
            val second = cycleUntilRejoin(c, first + 100, dwellMs() * 8)!!
            assertEquals(c.stallRejoinBackoff(), 2, "precondition: escalated")

            val probationMs = STALL_ACK_STALE_MS * STALL_REJOIN_PROBATION_MULT
            val late = second + probationMs + 1000
            val third = cycleUntilRejoin(c, late, dwellMs() * 4)!!
            assertEquals(
                c.stallRejoinBackoff(),
                1,
                "a rejoin that held must clear the escalation"
            )
            assertTrue(
                third - late <= dwellMs() + 200,
                "and the next dwell must be back to the base: took ${third - late}ms"
            )
        }

        test("the_rejoin_backoff_saturates") {
            val conns = createTestConnections(1)
            var t = nowMs()
            val c = conns[0]

            for (i in 0..7) {
                t = cycleUntilRejoin(c, t + 100, dwellMs() * 64)!!
            }
            assertEquals(
                c.stallRejoinBackoff(),
                STALL_REJOIN_BACKOFF_MAX,
                "the multiplier must stop at the ceiling"
            )
        }

        test("a_long_wait_does_not_stretch_the_share_ramp") {
            val conns = createTestConnections(1)
            val now = nowMs()
            val c = conns[0]

            val first = cycleUntilRejoin(c, now, dwellMs() * 4)!!
            val second = cycleUntilRejoin(c, first + 100, dwellMs() * 8)!!
            assertEquals(c.stallRejoinBackoff(), 2, "precondition: escalated")

            assertTrue(
                c.isRejoinRamping(second + dwellMs() - 100),
                "the ramp must still be running just short of the base dwell"
            )
            assertFalse(
                c.isRejoinRamping(second + dwellMs()),
                "and must be done at the base dwell, not the backed-off one"
            )
        }

        fun setRtt(conn: SrtlaConnection, rttMs: Double) {
            for (i in 0..15) {
                conn.rtt.kalmanRtt.update(rttMs)
            }
            assertTrue(
                kotlin.math.abs(conn.getSmoothRttMs() - rttMs) < rttMs * 0.1,
                "the RTT estimator must have converged for the test to mean anything"
            )
        }

        fun withBudget(budgetMs: Int): ConfigSnapshot =
            ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
                negotiatedLatencyMs = budgetMs,
            )

        fun rejoinsAt(rttMs: Double, budgetMs: Int): Boolean {
            val conns = createTestConnections(2)
            val now = nowMs()
            val config = withBudget(budgetMs)

            setRtt(conns[0], rttMs)
            makeStalled(conns[0], now)
            makeHealthyBusy(conns[1], now)
            selectConnectionIdx(conns, null, now, config)
            assertTrue(conns[0].stallLatched(), "precondition: the latch engaged")

            conns[0].inFlightPackets = 0
            var t = now
            while (t <= now + dwellMs() * 3) {
                t += 100
                conns[0].lastAckOrRttSampleMs = t
                conns[1].lastAckOrRttSampleMs = t
                selectConnectionIdx(conns, null, t, config)
                if (!conns[0].stallLatched()) {
                    return true
                }
            }
            return false
        }

        test("proof_from_a_link_slower_than_the_buffer_does_not_count") {
            assertFalse(
                rejoinsAt(2000.0, 500),
                "a link that cannot beat the deadline must not clear the dwell"
            )
        }

        test("the_same_link_rejoins_when_the_buffer_can_absorb_it") {
            assertTrue(
                rejoinsAt(2000.0, 4000),
                "identical link with different buffer should rejoin"
            )
        }

        test("an_undeclared_buffer_leaves_the_dwell_untouched") {
            assertTrue(
                rejoinsAt(2000.0, 0),
                "no buffer should allow rejoin"
            )
        }

        test("a_link_that_speeds_back_up_is_let_in") {
            val conns = createTestConnections(2)
            val now = nowMs()
            val config = withBudget(500)

            setRtt(conns[0], 2000.0)
            makeStalled(conns[0], now)
            makeHealthyBusy(conns[1], now)
            selectConnectionIdx(conns, null, now, config)
            assertTrue(conns[0].stallLatched())

            conns[0].inFlightPackets = 0
            var t = now
            for (i in 0 until (dwellMs() * 2 / 100)) {
                t += 100
                conns[0].lastAckOrRttSampleMs = t
                conns[1].lastAckOrRttSampleMs = t
                selectConnectionIdx(conns, null, t, config)
            }
            assertTrue(
                conns[0].stallLatched(),
                "two dwells of untimely proof must not add up to a rejoin"
            )

            setRtt(conns[0], 200.0)
            val deadline = t + dwellMs() * 3
            while (t <= deadline) {
                t += 100
                conns[0].lastAckOrRttSampleMs = t
                conns[1].lastAckOrRttSampleMs = t
                selectConnectionIdx(conns, null, t, config)
                if (!conns[0].stallLatched()) {
                    return@test
                }
            }
            assertTrue(false, "a recovered link must rejoin once its proof is timely again")
        }

        test("timeliness_is_measured_one_way_against_the_buffer") {
            assertTrue(deliveryProofIsTimely(2000.0, 1000))
            assertFalse(deliveryProofIsTimely(2002.0, 1000))

            assertTrue(deliveryProofIsTimely(9999.0, 0))
            assertTrue(deliveryProofIsTimely(0.0, 10))
        }

        test("backlog_drain_alone_does_not_release_the_latch") {
            val conns = createTestConnections(2)
            val now = nowMs()

            makeStalled(conns[0], now)
            makeHealthyBusy(conns[1], now)

            selectConnectionIdx(conns, null, now, enhanced())
            assertTrue(conns[0].stallGated)

            conns[0].inFlightPackets = 0
            val later = now + 4000
            conns[0].lastReceived = later
            conns[1].lastReceived = later
            conns[1].lastAckOrRttSampleMs = later
            val selected = selectConnectionIdx(conns, null, later, enhanced())
            assertFalse(
                conns[0].isStalled(later, STALL_MIN_IN_FLIGHT_PACKETS, STALL_ACK_STALE_MS),
                "precondition: raw stall signal cleared by the drain"
            )
            assertTrue(
                conns[0].stallGated,
                "the latch must keep the drained-but-unproven link gated"
            )
            assertEquals(selected, 1)
        }

        test("disabling_the_guard_clears_the_latch") {
            val conns = createTestConnections(2)
            val now = nowMs()

            makeStalled(conns[0], now)
            makeHealthyBusy(conns[1], now)
            selectConnectionIdx(conns, null, now, enhanced())
            assertTrue(conns[0].stallLatched())

            val off = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
                stallDeselect = false,
            )
            selectConnectionIdx(conns, null, now, off)
            assertFalse(conns[0].stallGated, "guard off must clear the flag")
            assertFalse(conns[0].stallLatched(), "guard off must clear the latch")
        }

        fun route(
            conns: List<SrtlaConnection>,
            now: Long,
            count: Int,
        ): Pair<Int, Int> {
            var picks0 = 0
            var picks1 = 0
            for (i in 0 until count) {
                when (selectConnectionIdx(conns, null, now, enhanced())) {
                    0 -> {
                        picks0++
                        conns[0].inFlightPackets++
                    }
                    1 -> {
                        picks1++
                        conns[1].inFlightPackets++
                    }
                    else -> assertTrue(false, "selection returned non-0/1 with two usable links")
                }
            }
            return picks0 to picks1
        }

        fun gateThenRelease(conns: List<SrtlaConnection>, t0: Long): Long {
            makeStalled(conns[0], t0)
            makeHealthyBusy(conns[1], t0)
            selectConnectionIdx(conns, null, t0, enhanced())
            assertTrue(conns[0].stallGated, "precondition: link 0 gated")

            conns[0].inFlightPackets = 0

            val runStart = t0 + 100
            for (c in conns) {
                c.lastReceived = runStart
                c.lastAckOrRttSampleMs = runStart
            }
            selectConnectionIdx(conns, null, runStart, enhanced())
            assertTrue(conns[0].stallLatched(), "one sample must not release")

            val released = runStart + dwellMs()
            for (c in conns) {
                c.lastReceived = released
                c.lastAckOrRttSampleMs = released
            }
            selectConnectionIdx(conns, null, released, enhanced())
            assertFalse(conns[0].stallLatched(), "sustained proof must release")
            return released
        }

        test("rejoining_link_ramps_its_share_instead_of_seizing_the_stream") {
            val conns = createTestConnections(2)
            val released = gateThenRelease(conns, nowMs())

            assertTrue(
                conns[0].isRejoinRamping(released),
                "releasing the latch must arm the share ramp"
            )

            conns[0].inFlightPackets = 0
            conns[1].inFlightPackets = STALL_MIN_IN_FLIGHT_PACKETS * 2
            val (rejoiner, incumbent) = route(conns, released, 60)

            assertTrue(
                rejoiner < incumbent,
                "the rejoining link must not take the stream off the working one (rejoiner $rejoiner, incumbent $incumbent)"
            )
            assertTrue(
                rejoiner > 0,
                "it must still carry something, or it can never prove itself"
            )
        }

        test("the_ramp_expires_and_the_link_competes_at_full_score") {
            val conns = createTestConnections(2)
            val released = gateThenRelease(conns, nowMs())

            val done = released + dwellMs()
            for (c in conns) {
                c.lastReceived = done
                c.lastAckOrRttSampleMs = done
            }
            assertFalse(
                conns[0].isRejoinRamping(done),
                "the ramp must expire on its own"
            )

            conns[0].inFlightPackets = 0
            conns[1].inFlightPackets = STALL_MIN_IN_FLIGHT_PACKETS * 2
            val (rejoiner, incumbent) = route(conns, done, 60)

            assertTrue(
                rejoiner > incumbent,
                "with the ramp expired the recovered link competes on raw capacity again (rejoiner $rejoiner, incumbent $incumbent)"
            )
        }

        test("adaptive_stale_window_scales_with_smoothed_rtt") {
            val conns = createTestConnections(1)
            val c = conns[0]

            assertEquals(
                c.effectiveStallStaleMs(STALL_ACK_STALE_MS),
                STALL_ACK_STALE_MS
            )

            c.rtt.kalmanRtt.update(50.0)
            assertEquals(
                c.effectiveStallStaleMs(STALL_ACK_STALE_MS),
                STALL_STALE_FLOOR_MS
            )

            c.rtt.kalmanRtt.reset()
            c.rtt.kalmanRtt.update(400.0)
            assertEquals(c.effectiveStallStaleMs(STALL_ACK_STALE_MS), 1600L)

            c.rtt.kalmanRtt.reset()
            c.rtt.kalmanRtt.update(2000.0)
            assertEquals(
                c.effectiveStallStaleMs(STALL_ACK_STALE_MS),
                STALL_ACK_STALE_MS
            )
        }

        test("probe_cadence_is_one_in_n") {
            val conns = createTestConnections(1)
            val c = conns[0]

            var fired = 0
            for (i in 0 until (STALL_PROBE_ONE_IN_N * 3)) {
                if (c.stallProbeDue()) {
                    fired++
                }
            }
            assertEquals(fired, 3, "exactly one probe per N routed packets")
        }

        test("srtla_ack_credits_the_arrival_link_first") {
            val conns = createTestConnections(2)
            val now = nowMs()
            val seq = 4242

            // Unique copy on link 0, probe copy on link 1 (gated).
            conns[0].registerPacket(seq, now)
            conns[1].registerPacket(seq, now)
            conns[0].lastAckOrRttSampleMs = 1
            conns[1].lastAckOrRttSampleMs = 1

            val seqTracker = SequenceTracker()
            val clientSink = ClientSink { _, _, _ ->
                // Mock: do nothing
            }

            val incoming = SrtlaIncoming()
            incoming.srtlaAckNumbers.add(seq)
            incoming.readAny = true

            // ACK arrives on link 1 — the probe link must earn the proof.
            processConnectionEvents(
                1,
                conns,
                null,
                clientSink,
                seqTracker,
                false,
                incoming,
                now
            )

            assertTrue(
                conns[1].lastAckOrRttSampleMs > 1,
                "arrival link must be credited with delivery proof"
            )
            assertEquals(
                conns[0].lastAckOrRttSampleMs, 1L,
                "the other copy's owner must not be falsely credited"
            )
            assertEquals(
                conns[0].inFlightPackets, 1,
                "the unique copy stays in flight until its own ACK clears it"
            )
            assertEquals(conns[1].inFlightPackets, 0)
        }

        test("latched_link_with_timed_out_sibling_never_blacks_out") {
            val conns = createTestConnections(2)
            val now = nowMs()

            makeStalled(conns[0], now)
            makeHealthyBusy(conns[1], now)
            selectConnectionIdx(conns, null, now, enhanced())
            assertTrue(conns[0].stallGated)
            assertEquals(conns[0].stallGateEvents(), 1L)

            conns[1].lastReceived = now.satSub(CONN_TIMEOUT_MS + 1000)
            val selected = selectConnectionIdx(conns, null, now, enhanced())
            assertEquals(
                selected,
                0,
                "with the sibling timed out, the latched link must carry the stream"
            )
            assertFalse(
                conns[0].stallGated,
                "gate must yield when it is the last carrier"
            )
            assertTrue(
                conns[0].stallLatched(),
                "the latch itself must persist so the link re-gates the moment a carrier returns"
            )

            for (i in 0..4) {
                selectConnectionIdx(conns, null, now, enhanced())
            }
            assertEquals(
                conns[0].stallGateEvents(),
                1L,
                "a latch held through carrier-loss fallback must count as ONE engagement"
            )
        }

        test("stall_gate_and_in_flight_cap_never_combine_into_blackout") {
            val conns = createTestConnections(2)
            val now = nowMs()

            makeStalled(conns[0], now)
            // Healthy by the latch metric (fresh proof) but over its BDP cap:
            // a tiny CC target caps in-flight at 1 packet against the 64 carried.
            makeHealthyBusy(conns[1], now)
            conns[1].ccTargetBps = 100_000
            assertTrue(
                inFlightCapExceeded(conns[1]),
                "precondition: sibling must be over its in-flight cap"
            )

            // The stall gate holds (a latch-healthy sibling exists) and the cap
            // gate must yield (no unconstrained link left) — never an empty pool.
            val selected = selectConnectionIdx(conns, null, now, enhanced())
            assertTrue(conns[0].stallGated)
            assertEquals(
                selected,
                1,
                "the capped-but-alive link must carry the stream, not an empty pool"
            )
        }

        test("loaded_silent_link_is_pulled_and_readmitted_when_it_speaks") {
            val conns = createTestConnections(2)
            val now = nowMs()

            conns[0].inFlightPackets = STALL_MIN_IN_FLIGHT_PACKETS
            conns[0].lastAckOrRttSampleMs = now
            conns[0].lastReceived = now - 300
            makeHealthyBusy(conns[1], now)

            selectConnectionIdx(conns, null, now, enhanced())
            assertTrue(conns[0].stallGated, "a loaded mute link must be pulled")
            assertFalse(conns[0].stallLatched(), "the fast tier must not latch")
            assertEquals(conns[0].silencePulls(), 1L)

            conns[0].lastReceived = now
            selectConnectionIdx(conns, null, now, enhanced())
            assertFalse(
                conns[0].stallGated,
                "a byte must clear the pull instantly"
            )

            conns[0].lastReceived = now - 300
            selectConnectionIdx(conns, null, now, enhanced())
            assertTrue(conns[0].stallGated)
            assertEquals(conns[0].silencePulls(), 2L)
        }

        test("idle_link_keepalive_gaps_are_never_pulled") {
            val conns = createTestConnections(2)
            val now = nowMs()

            conns[0].inFlightPackets = 0
            conns[0].lastReceived = now - 900
            makeHealthyBusy(conns[1], now)

            selectConnectionIdx(conns, null, now, enhanced())
            assertFalse(conns[0].stallGated, "idle links are never silence-pulled")
            assertEquals(conns[0].silencePulls(), 0L)
        }

        test("silence_window_scales_with_rtt_and_caps_at_stale_window") {
            val conns = createTestConnections(1)
            val c = conns[0]

            assertEquals(
                c.silencePullWindowMs(STALL_ACK_STALE_MS),
                SILENCE_PULL_FLOOR_MS
            )
            c.rtt.kalmanRtt.update(50.0)
            assertEquals(
                c.silencePullWindowMs(STALL_ACK_STALE_MS),
                SILENCE_PULL_FLOOR_MS
            )
            c.rtt.kalmanRtt.reset()
            c.rtt.kalmanRtt.update(300.0)
            assertEquals(c.silencePullWindowMs(STALL_ACK_STALE_MS), 600L)
            c.rtt.kalmanRtt.reset()
            c.rtt.kalmanRtt.update(2000.0)
            assertEquals(
                c.silencePullWindowMs(STALL_ACK_STALE_MS),
                STALL_ACK_STALE_MS
            )
        }

        test("pull_holds_through_backlog_drain_and_escalates_to_latch") {
            val conns = createTestConnections(2)
            val now = nowMs()

            conns[0].inFlightPackets = STALL_MIN_IN_FLIGHT_PACKETS
            conns[0].lastReceived = now - 300
            conns[0].lastAckOrRttSampleMs = now - 300
            makeHealthyBusy(conns[1], now)

            selectConnectionIdx(conns, null, now, enhanced())
            assertTrue(conns[0].stallGated && !conns[0].stallLatched())

            conns[0].inFlightPackets = 0
            val t1 = now + 500
            conns[1].lastReceived = t1
            conns[1].lastAckOrRttSampleMs = t1
            selectConnectionIdx(conns, null, t1, enhanced())
            assertTrue(
                conns[0].stallGated,
                "the pull must hold through a backlog drain, not readmit a mute link"
            )
            assertFalse(conns[0].stallLatched())

            val t2 = now + STALL_ACK_STALE_MS
            conns[1].lastReceived = t2
            conns[1].lastAckOrRttSampleMs = t2
            selectConnectionIdx(conns, null, t2, enhanced())
            assertTrue(
                conns[0].stallLatched(),
                "sustained silence must escalate the pull into the sticky latch"
            )
            assertEquals(conns[0].stallGateEvents(), 1L)

            conns[0].lastReceived = t2
            conns[0].lastAckOrRttSampleMs = t2
            selectConnectionIdx(conns, null, t2, enhanced())
            assertTrue(
                conns[0].stallGated,
                "after escalation, a single byte must not readmit the link"
            )
        }

        test("conn_timeout_is_runtime_scaled") {
            val conns = createTestConnections(1)
            val now = nowMs()

            conns[0].lastReceived = now - 8_000

            selectConnectionIdx(conns, null, now, enhanced())
            assertTrue(conns[0].isTimedOut(now))

            val scaled = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
                connTimeoutMs = 12_000,
            )
            selectConnectionIdx(conns, null, now, scaled)
            assertFalse(
                conns[0].isTimedOut(now),
                "the scaled liveness window must reach is_timed_out callers"
            )
        }
    }
}
