// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/tests/link_weight_tests.rs
//
// Tests for operator link weights (Moblin's "connection priorities"): the
// optional second column of the IPs file, normalised so the lowest link is 1,
// applied by classic selection only through a three-band window multiplier.
// Parsing and the reload guard are covered in `sender::reload`'s own tests.
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.connection.LINK_WEIGHT_FULL_ABOVE_WINDOW
import dev.abdulkadirozyurt.srtla.connection.LINK_WEIGHT_NONE_AT_OR_BELOW_WINDOW
import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.connection.linkWeightMultiplier
import dev.abdulkadirozyurt.srtla.connection.normaliseLinkWeights
import dev.abdulkadirozyurt.srtla.core.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.core.STALL_MIN_IN_FLIGHT_PACKETS
import dev.abdulkadirozyurt.srtla.core.STALL_ACK_STALE_MS
import dev.abdulkadirozyurt.srtla.selection.selectConnectionIdx
import dev.abdulkadirozyurt.srtla.sender.applyLinkWeights
import dev.abdulkadirozyurt.srtla.telemetry.SharedStats
import dev.abdulkadirozyurt.srtla.telemetry.renderMetrics
import dev.abdulkadirozyurt.srtla.testkit.*
import java.net.InetAddress
import java.net.Inet4Address

private fun classicConfig(): ConfigSnapshot = ConfigSnapshot(
    mode = SchedulingMode.CLASSIC,
    qualityEnabled = false,
)

/**
 * Feed [packets] packets through classic selection, counting each pick as
 * one more in-flight packet on the chosen link (no ACKs): the share each
 * link ends up with is the scheduler's steady preference at these windows.
 */
private fun picks(conns: MutableList<SrtlaConnection>, packets: Int): List<Int> {
    val config = classicConfig()
    val counts = MutableList(conns.size) { 0 }
    for (_i in 0 until packets) {
        val now = nowMs()
        val idx = selectConnectionIdx(conns, null, now, config)
        if (idx != null) {
            conns[idx].inFlightPackets++
            counts[idx]++
        }
    }
    return counts
}

fun registerLinkWeightTests() {
    suite("link_weight_tests") {
        test("multiplier_full_weight_above_20k") {
            assertEquals(linkWeightMultiplier(60_000, 10), 10.0f)
            assertEquals(linkWeightMultiplier(20_001, 10), 10.0f)
            assertEquals(
                linkWeightMultiplier(LINK_WEIGHT_FULL_ABOVE_WINDOW, 10),
                10.0f
            )
        }

        test("multiplier_fades_linearly_between_10k_and_20k") {
            assertEquals(linkWeightMultiplier(15_000, 10), 5.5f)
            assertEquals(linkWeightMultiplier(12_500, 5), 2.0f)
            val justAbove = linkWeightMultiplier(LINK_WEIGHT_NONE_AT_OR_BELOW_WINDOW + 1, 10)
            assertTrue(justAbove > 1.0f && justAbove < 1.01f, "$justAbove")
        }

        test("multiplier_ignores_weight_at_or_below_10k") {
            assertEquals(
                linkWeightMultiplier(LINK_WEIGHT_NONE_AT_OR_BELOW_WINDOW, 10),
                1.0f
            )
            assertEquals(linkWeightMultiplier(1_000, 10), 1.0f)
        }

        test("weight_one_is_neutral_in_every_band") {
            for (window in listOf(60_000, 20_000, 15_000, 10_000, 1)) {
                assertEquals(linkWeightMultiplier(window, 1), 1.0f)
            }
        }

        test("normalisation_makes_the_lowest_weight_one") {
            var w = intArrayOf(10, 1)
            normaliseLinkWeights(w)
            assertContentEquals(w, intArrayOf(10, 1))

            w = intArrayOf(10, 5, 7)
            normaliseLinkWeights(w)
            assertContentEquals(w, intArrayOf(6, 1, 3))

            w = intArrayOf(4, 4)
            normaliseLinkWeights(w)
            assertContentEquals(w, intArrayOf(1, 1))

            w = intArrayOf(0, 200)
            normaliseLinkWeights(w)
            assertContentEquals(w, intArrayOf(1, 10), "clamped to 1..10 before normalising")

            w = intArrayOf()
            normaliseLinkWeights(w)
        }

        test("unweighted_links_share_evenly") {
            val conns = createTestConnections(2)
            val counts = picks(conns, 200)
            assertEquals(counts, listOf(100, 100))
        }

        test("classic_prefers_the_weighted_link_while_healthy") {
            val conns = createTestConnections(2)
            for (c in conns) {
                c.window = 40_000 // healthy band
            }
            conns[0].linkWeight = 10
            val counts = picks(conns, 220)
            val shareB = counts[1].toDouble() / 220.0
            assertTrue(counts[0] > counts[1], "$counts")
            assertTrue(
                shareB > 0.0 && shareB < 0.12,
                "weight 10 leaves roughly one packet in ten to the other link: $counts"
            )
        }

        test("preference_fades_as_the_weighted_links_window_shrinks") {
            fun shareB(windowA: Int): Double {
                val conns = createTestConnections(2)
                conns[0].window = windowA
                conns[1].window = windowA
                conns[0].linkWeight = 10
                val counts = picks(conns, 400)
                return counts[1].toDouble() / 400.0
            }

            val healthy = shareB(40_000)
            val shedding = shareB(15_000)
            val congested = shareB(10_000)
            assertTrue(healthy < shedding, "$healthy $shedding")
            assertTrue(shedding < congested, "$shedding $congested")
            assertTrue(
                Math.abs(congested - 0.5) < 0.01,
                "at or below 10k the weight is ignored: $congested"
            )
        }

        test("a_small_window_on_the_weighted_link_hands_traffic_to_the_other") {
            val conns = createTestConnections(2)
            conns[0].linkWeight = 10
            conns[0].window = 5_000 // A is congested: no multiplier
            conns[1].window = 40_000 // B healthy, weight 1
            val counts = picks(conns, 90)
            assertTrue(counts[1] > counts[0] * 4, "$counts")
        }

        test("timed_out_weighted_link_is_still_skipped") {
            val conns = createTestConnections(2)
            val now = nowMs()
            conns[0].linkWeight = 10
            conns[0].window = 60_000
            conns[0].lastReceived = now.satSub(60_000)
            conns[1].inFlightPackets = 30
            val selected = selectConnectionIdx(conns, null, now, classicConfig())
            assertEquals(selected, 1)
        }

        test("stall_gated_weighted_link_is_still_skipped") {
            val conns = createTestConnections(2)
            val now = nowMs()
            conns[0].linkWeight = 10
            conns[0].inFlightPackets = STALL_MIN_IN_FLIGHT_PACKETS
            conns[0].lastAckOrRttSampleMs = now.satSub(STALL_ACK_STALE_MS + 1000)
            conns[1].inFlightPackets = STALL_MIN_IN_FLIGHT_PACKETS * 2
            conns[1].lastAckOrRttSampleMs = now
            val selected = selectConnectionIdx(conns, null, now, classicConfig())
            assertEquals(1, selected)
            assertTrue(conns[0].stallGated)
        }

        test("enhanced_mode_ignores_weights") {
            val conns = createTestConnections(2)
            conns[0].linkWeight = 10
            conns[0].inFlightPackets = 5
            conns[1].inFlightPackets = 0
            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = false,
            )
            val selected = selectConnectionIdx(conns, null, nowMs(), config)
            assertEquals(selected, 1)
        }

        test("reload_reweights_existing_links_and_unnamed_links_fall_back_to_one") {
            val conns = createTestConnections(2)
            val a = conns[0].localIp
            val b = conns[1].localIp
            assertEquals(conns[0].localIp, a)

            applyLinkWeights(conns, listOf(a, b), listOf(10, 1))
            assertEquals(conns[0].linkWeight to conns[1].linkWeight, 10 to 1)

            // A reload without weights puts everyone back to 1.
            applyLinkWeights(conns, listOf(a, b), listOf(1, 1))
            assertEquals(conns[0].linkWeight to conns[1].linkWeight, 1 to 1)

            // Weight moves to the other link.
            applyLinkWeights(conns, listOf(a, b), listOf(1, 7))
            assertEquals(conns[0].linkWeight to conns[1].linkWeight, 1 to 7)

            // An empty weight list (legacy caller) means 1.
            applyLinkWeights(conns, listOf(a, b), emptyList())
            assertEquals(conns[0].linkWeight to conns[1].linkWeight, 1 to 1)
        }

        test("weight_is_exported_per_link_in_metrics") {
            val conns = createTestConnections(2)
            conns[0].linkWeight = 10
            val stats = SharedStats()
            stats.update(conns, classicConfig(), null, null)
            val text = renderMetrics(stats, DynamicConfig(), CriticalWindow())
            assertTrue(
                text.contains("""srtla_send_link_weight{ip="192.168.1.10"} 10"""),
                text
            )
            assertTrue(text.contains("""srtla_send_link_weight{ip="192.168.1.11"} 1"""))
        }
    }
}
