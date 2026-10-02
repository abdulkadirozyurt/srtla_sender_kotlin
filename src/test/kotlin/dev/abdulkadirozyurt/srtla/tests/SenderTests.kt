// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/tests/sender_tests.rs
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.connection.LinkPhase
import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.net.SourceIpBinder
import dev.abdulkadirozyurt.srtla.selection.calculateQualityMultiplier
import dev.abdulkadirozyurt.srtla.selection.selectConnectionIdx
import dev.abdulkadirozyurt.srtla.sender.ConnIo
import dev.abdulkadirozyurt.srtla.sender.GLOBAL_TIMEOUT_MS
import dev.abdulkadirozyurt.srtla.sender.PendingConnectionChanges
import dev.abdulkadirozyurt.srtla.sender.SEQ_TRACKING_SIZE
import dev.abdulkadirozyurt.srtla.sender.SenderState
import dev.abdulkadirozyurt.srtla.sender.SequenceTracker
import dev.abdulkadirozyurt.srtla.sender.applyConnectionChanges
import dev.abdulkadirozyurt.srtla.sender.createConnectionsFromIps
import dev.abdulkadirozyurt.srtla.sender.recoverConnection
import dev.abdulkadirozyurt.srtla.sender.readWeightedIpList
import dev.abdulkadirozyurt.srtla.testkit.*
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files

fun registerSenderTests() {
    suite("sender_tests") {
        test("test_select_connection_idx_classic") {
            val connections = createTestConnections(3)

            // Test classic mode - should pick connection with highest score
            connections[1].inFlightPackets = 0 // Best score
            connections[0].inFlightPackets = 5 // Lower score
            connections[2].inFlightPackets = 10 // Lowest score

            val config = ConfigSnapshot(
                mode = SchedulingMode.CLASSIC,
                qualityEnabled = false,
            )

            val selected = selectConnectionIdx(connections, null, 0, config)
            assertEquals(selected, 1)
        }

        test("test_enhanced_skips_weak_when_alternative_exists") {
            val connections = createTestConnections(3)
            val currentTime = nowMs()

            // Connection 1 has the highest base score but is flagged weak.
            // Connection 0 is healthy. Selection should pick 0, not 1.
            connections[0].inFlightPackets = 5
            connections[1].inFlightPackets = 0
            connections[1].weak = true
            connections[2].inFlightPackets = 10

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
            )
            val selected = selectConnectionIdx(connections, null, currentTime, config)
            assertEquals(
                selected,
                0,
                "weak connection 1 must be skipped when a non-weak alternative exists"
            )
        }

        test("test_enhanced_falls_back_when_all_weak") {
            val connections = createTestConnections(3)
            val currentTime = nowMs()

            // Every link is weak. Selection must still pick the best — better
            // a weak link than a dropped packet.
            connections[0].weak = true
            connections[0].inFlightPackets = 5
            connections[1].weak = true
            connections[1].inFlightPackets = 0 // best score among the weak
            connections[2].weak = true
            connections[2].inFlightPackets = 10

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = false,
            )
            val selected = selectConnectionIdx(connections, null, currentTime, config)
            assertEquals(
                selected,
                1,
                "with no non-weak alternatives, selection must fall back to the best available link"
            )
        }

        test("test_enhanced_skips_in_flight_cap_when_alternative_exists") {
            val connections = createTestConnections(3)
            val currentTime = nowMs()

            // Connection 1 would have the best base score (lowest in_flight)
            // but is over its BDP in-flight cap: cc_target_bps = 200 kbps at
            // the test RTT (~200 ms) gives a cap of ~5 packets, and
            // in_flight = 6 exceeds it. Connection 0 is unconstrained, so the
            // capped link must be skipped even though its score is higher.
            connections[0].inFlightPackets = 12
            connections[1].inFlightPackets = 6
            connections[1].ccTargetBps = 200_000L
            connections[2].inFlightPackets = 20

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = false,
            )
            val selected = selectConnectionIdx(connections, null, currentTime, config)
            assertEquals(
                selected,
                0,
                "in-flight-capped link must be skipped when an un-gated alternative exists"
            )
        }

        test("test_enhanced_falls_back_when_all_in_flight_capped") {
            val connections = createTestConnections(3)
            val currentTime = nowMs()

            // Every link is over its BDP in-flight cap (cc_target = 200 kbps
            // at ~200 ms RTT → cap ~5 packets). Fallback rule: pick the best
            // base score rather than drop the packet.
            for (c in connections) {
                c.ccTargetBps = 200_000L
                c.inFlightPackets = 10
            }
            connections[1].inFlightPackets = 6 // best score among the capped, still > cap

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = false,
            )
            val selected = selectConnectionIdx(connections, null, currentTime, config)
            assertEquals(
                selected,
                1,
                "with no un-gated alternatives, selection falls back to the best capped link"
            )
        }

        test("test_enhanced_treats_loss_degraded_as_weak") {
            val connections = createTestConnections(3)
            val currentTime = nowMs()

            connections[0].inFlightPackets = 5
            connections[1].inFlightPackets = 0
            // Sustained loss latch (not the raw per-window cc_backing_off) is the
            // routing-admission gate, so a single noisy loss window can't demote.
            connections[1].lossDegraded = true
            connections[2].inFlightPackets = 10

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = false,
            )
            val selected = selectConnectionIdx(connections, null, currentTime, config)
            assertEquals(
                selected,
                0,
                "loss-degraded link must be skipped when a healthy alternative exists"
            )
        }

        test("test_enhanced_does_not_gate_on_raw_backing_off") {
            // cc_backing_off drives the CC controller's bitrate backoff but is
            // intentionally NOT a routing gate (it flips on a single loss window).
            // A link flagged only cc_backing_off, with the best base score, still
            // wins selection.
            val connections = createTestConnections(3)
            val currentTime = nowMs()

            connections[0].inFlightPackets = 5
            connections[1].inFlightPackets = 0 // best base score
            connections[1].ccBackingOff = true
            connections[2].inFlightPackets = 10

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = false,
            )
            val selected = selectConnectionIdx(connections, null, currentTime, config)
            assertEquals(
                selected,
                1,
                "cc_backing_off alone must not demote a link's routing weight"
            )
        }

        test("test_enhanced_weak_link_stays_rankable") {
            // A quality-gated link is crushed in score but not removed, so it keeps
            // a trickle of traffic and can still earn the ACK/loss samples that
            // clear the gate. Without that it earns zero throughput share, the
            // classifier reads NoTraffic/LowShare, and it stays weak forever: a
            // starvation lock. This trickle is what makes an explicit re-probing
            // mechanism unnecessary (measured: a 70%-loss link gated to 0.00 Mbps
            // re-adopts itself ~7s after it silently heals).
            val connections = createTestConnections(2)
            val currentTime = nowMs()

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
            )

            // The healthy link wins while it is healthy -- the weak link is crushed
            // by GATED_LINK_PENALTY, not removed.
            connections[0].inFlightPackets = 0 // healthy
            connections[1].inFlightPackets = 0
            connections[1].weak = true
            val selected = selectConnectionIdx(connections, 0, currentTime, config)
            assertEquals(selected, 0, "healthy link should win over a weak one")

            // Crushed, but still in the ranking: once the healthy link is loaded
            // enough that even a 0.02x score beats it, the weak link takes the
            // packet. An *excluded* link could never do this, and would earn zero
            // share forever.
            connections[0].inFlightPackets = 10_000
            val selected2 = selectConnectionIdx(connections, 0, currentTime, config)
            assertEquals(
                selected2,
                1,
                "weak link must remain rankable so its trickle can clear the gate"
            )
        }

        test("test_select_connection_idx_quality_scoring") {
            val connections = createTestConnections(3)
            val currentTime = nowMs()

            // Connection 0: Recent NAKs - should get low score
            connections[0].congestion.nakCount = 5
            connections[0].congestion.lastNakTimeMs = currentTime - 1000 // 1 second ago

            // Connection 1: No NAKs - should get bonus
            connections[1].congestion.nakCount = 0

            // Connection 2: Old NAKs - should get partial penalty
            connections[2].congestion.nakCount = 3
            connections[2].congestion.lastNakTimeMs = currentTime - 8000 // 8 seconds ago

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
            )

            val selected = selectConnectionIdx(connections, null, currentTime, config)

            // Should prefer connection 1 (no NAKs)
            assertEquals(selected, 1)
        }

        test("test_select_connection_idx_burst_nak_penalty") {
            val connections = createTestConnections(3)
            val currentTime = nowMs()

            // Connection 0: NAK burst
            connections[0].congestion.nakCount = 5
            connections[0].congestion.nakBurstCount = 3
            connections[0].congestion.lastNakTimeMs = currentTime - 2000 // 2 seconds ago

            // Connection 1: Same NAK count but no burst
            connections[1].congestion.nakCount = 5
            connections[1].congestion.nakBurstCount = 0
            connections[1].congestion.lastNakTimeMs = currentTime - 2000 // 2 seconds ago

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
            )

            val selected = selectConnectionIdx(connections, null, currentTime, config)

            // Should prefer connection 2 (never had NAKs, best quality)
            assertEquals(selected, 2)
        }

        test("test_enhanced_reselects_immediately_no_time_lock") {
            val connections = createTestConnections(3)

            // Setup: Connection 0 is currently selected, Connection 1 has better score
            connections[0].inFlightPackets = 5 // Lower score
            connections[1].inFlightPackets = 0 // Best score
            connections[2].inFlightPackets = 10 // Worst score

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
            )

            // There is no switch cooldown: selection must re-decide on every packet.
            // `getScore()` counts queued packets as in-flight, so routing a packet
            // lowers its own link's score -- that feedback loop is what bounds
            // per-link queue depth, and a time lock would open it.
            val selected = selectConnectionIdx(connections, 0, nowMs(), config)
            assertEquals(
                selected,
                1,
                "Enhanced mode must be free to switch to a better link on the very next packet"
            )
        }

        test("test_enhanced_switches_to_clearly_better_connection") {
            val connections = createTestConnections(3)

            // Setup: Connection 0 is currently selected, Connection 1 has significantly better score
            connections[0].inFlightPackets = 5 // Lower score
            connections[1].inFlightPackets = 0 // Best score (significantly better, exceeds 2% hysteresis)
            connections[2].inFlightPackets = 10 // Worst score

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
            )

            // A link better by more than SWITCH_THRESHOLD wins the packet.
            val selected = selectConnectionIdx(connections, 0, nowMs(), config)
            assertEquals(selected, 1, "Should route to the better connection")
        }

        test("test_enhanced_switches_away_from_timed_out_connection") {
            val connections = createTestConnections(3)

            // Setup: Connection 0 is currently selected but becomes timed out
            connections[0].inFlightPackets = 5
            // Simulate timeout by stamping last_received 6 seconds ago (CONN_TIMEOUT is 5 seconds)
            connections[0].lastReceived = nowMs() - 6000
            connections[1].inFlightPackets = 0 // Best score
            connections[2].inFlightPackets = 10

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
            )

            val selected = selectConnectionIdx(connections, 0, nowMs(), config)
            assertEquals(
                selected,
                1,
                "Should route via a valid connection when the current one has timed out"
            )
        }

        test("test_classic_mode_picks_highest_score") {
            val connections = createTestConnections(3)

            // Setup: Connection 0 is currently selected, Connection 1 has better score
            connections[0].inFlightPackets = 5 // Lower score
            connections[1].inFlightPackets = 0 // Best score
            connections[2].inFlightPackets = 10 // Worst score

            val config = ConfigSnapshot(
                mode = SchedulingMode.CLASSIC,
                qualityEnabled = false,
            )

            // Classic mode: per-packet selection ALWAYS picks highest score connection
            // No hysteresis - matches original C implementation
            val selected = selectConnectionIdx(connections, 0, nowMs(), config)

            // Per-packet routing immediately uses connection 1 (best score)
            assertEquals(
                selected,
                1,
                "Classic mode per-packet selection should ignore time-based dampening and always route via highest score connection"
            )
        }

        test("test_nak_attribution_to_correct_connection") {
            val connections = createTestConnections(3)

            val currentTime = nowMs()
            connections[0].registerPacket(100, currentTime)
            connections[1].registerPacket(200, currentTime)
            connections[2].registerPacket(300, currentTime)

            val initialCounts = intArrayOf(
                connections[0].congestion.nakCount,
                connections[1].congestion.nakCount,
                connections[2].congestion.nakCount,
            )

            val found0 = connections[0].handleNak(100, nowMs())
            assertTrue(found0)
            assertEquals(connections[0].congestion.nakCount, initialCounts[0] + 1)
            assertEquals(connections[1].congestion.nakCount, initialCounts[1])
            assertEquals(connections[2].congestion.nakCount, initialCounts[2])

            val found1 = connections[1].handleNak(200, nowMs())
            assertTrue(found1)
            assertEquals(connections[0].congestion.nakCount, initialCounts[0] + 1)
            assertEquals(connections[1].congestion.nakCount, initialCounts[1] + 1)
            assertEquals(connections[2].congestion.nakCount, initialCounts[2])

            val notFound0 = connections[0].handleNak(999, nowMs())
            val notFound1 = connections[1].handleNak(999, nowMs())
            val notFound2 = connections[2].handleNak(999, nowMs())
            assertFalse(notFound0)
            assertFalse(notFound1)
            assertFalse(notFound2)
            assertEquals(connections[0].congestion.nakCount, initialCounts[0] + 1)
            assertEquals(connections[1].congestion.nakCount, initialCounts[1] + 1)
            assertEquals(connections[2].congestion.nakCount, initialCounts[2])
        }

        test("test_sequence_tracking_limits") {
            val seqTracker = SequenceTracker()
            val now = nowMs()

            // Fill beyond capacity - ring buffer naturally handles this
            for (i in 0 until SEQ_TRACKING_SIZE + 100) {
                seqTracker.insert(i, 1L, now)
            }

            // Ring buffer should have overwritten older entries
            // Recent entries should still be accessible
            val recentSeq = SEQ_TRACKING_SIZE + 50
            assertNotNull(seqTracker.get(recentSeq, now))

            // Old entries that were overwritten should not be accessible
            // (due to collision with newer sequence numbers)
            val oldSeq = 50
            // The old entry was overwritten when seq SEQ_TRACKING_SIZE + 50 was inserted
            // because they map to the same index
            assertNull(seqTracker.get(oldSeq, now))
        }

        test("test_connection_selection_with_all_disconnected") {
            val connections = createTestConnections(3)

            // Disconnect all connections
            for (conn in connections) {
                conn.connected = false
            }

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = false,
            )

            val selected = selectConnectionIdx(connections, null, 0, config)

            // Should return None when all connections have score -1
            assertNull(selected)
        }

        test("test_calculate_quality_multiplier") {
            val conn = createTestConnection()

            val currentTime = nowMs()
            conn.reconnection.connectionEstablishedMs = currentTime - 35000

            assertEquals(calculateQualityMultiplier(conn, currentTime), 1.1)

            // Test connection with recent NAK - exponential decay formula
            // With exponential decay: penalty = 0.5 * e^(-age_ms / 2000), multiplier = 1.0 - penalty
            conn.congestion.nakCount = 1

            // 500ms ago: multiplier ≈ 0.61 (strong penalty, recent NAK)
            conn.congestion.lastNakTimeMs = currentTime - 500
            val mult500 = calculateQualityMultiplier(conn, currentTime)
            assertClose(
                mult500,
                0.61,
                0.02,
                "Expected ~0.61, got $mult500"
            )

            // 2000ms ago (half-life): multiplier ≈ 0.816 (moderate penalty)
            conn.congestion.lastNakTimeMs = currentTime - 2000
            val mult2000 = calculateQualityMultiplier(conn, currentTime)
            assertClose(
                mult2000,
                0.816,
                0.02,
                "Expected ~0.816, got $mult2000"
            )

            // 5000ms ago: multiplier ≈ 0.96 (light penalty)
            conn.congestion.lastNakTimeMs = currentTime - 5000
            val mult5000 = calculateQualityMultiplier(conn, currentTime)
            assertClose(
                mult5000,
                0.96,
                0.02,
                "Expected ~0.96, got $mult5000"
            )

            // 15000ms ago: multiplier ≈ 1.0 (essentially recovered)
            conn.congestion.lastNakTimeMs = currentTime - 15000
            val mult15000 = calculateQualityMultiplier(conn, currentTime)
            assertClose(
                mult15000,
                1.0,
                0.02,
                "Expected ~1.0, got $mult15000"
            )

            // Test connection with no NAKs ever - bonus
            // Need to clear the last_nak_time_ms to simulate truly no NAKs
            conn.congestion.nakCount = 0
            conn.congestion.lastNakTimeMs = 0L // Clear NAK history
            assertEquals(calculateQualityMultiplier(conn, currentTime), 1.1)

            // Test burst NAK penalty (requires ≥5 NAKs in burst, within 3s)
            // Burst penalty is 0.7x additional multiplier
            conn.congestion.nakCount = 5
            conn.congestion.lastNakTimeMs = currentTime - 2000
            conn.congestion.nakBurstCount = 5
            val multBurst = calculateQualityMultiplier(conn, currentTime)
            // At 2000ms: base multiplier ≈ 0.816, with burst: 0.816 * 0.7 ≈ 0.571
            assertClose(
                multBurst,
                0.571,
                0.02,
                "Expected ~0.571, got $multBurst"
            )
        }

        test("test_warming_link_is_schedulable") {
            // At go-live EVERY link is warming. When Warming was a hard exclusion the
            // candidate pool was empty and the sender dropped the stream until the
            // first link was promoted. A warming link must be usable.
            val connections = createTestConnections(2)
            val now = nowMs()

            for (c in connections) {
                c.phase = LinkPhase.Warming(rttProbes = 0, enteredMs = now)
            }
            connections[0].inFlightPackets = 5
            connections[1].inFlightPackets = 0 // best

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = false,
            )
            val selected = selectConnectionIdx(connections, null, now, config)
            assertEquals(
                selected,
                1,
                "an all-warming pool must still schedule, not drop the packet"
            )
        }

        test("test_warming_link_is_derated_against_a_live_one") {
            // The de-rating is what Warming buys us: a link whose RTT baseline is a
            // keepalive old should not take a full share while a characterised link
            // is available. 0.8 x a marginally better raw score loses to Live.
            val connections = createTestConnections(2)
            val now = nowMs()

            // Warming link has the better *raw* score (fewer in flight)...
            connections[0].phase = LinkPhase.Warming(rttProbes = 1, enteredMs = now)
            connections[0].inFlightPackets = 4
            // ...but the Live link is close enough that the 0.8 weight flips it.
            connections[1].inFlightPackets = 5

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = false,
            )
            val selected = selectConnectionIdx(connections, null, now, config)
            assertEquals(
                selected,
                1,
                "a warming link's 0.8 weight should cede a close call to a live link"
            )
        }

        test("test_registering_link_is_never_scheduled") {
            // The one hard exclusion, and it is not a quality judgement: without REG3
            // the receiver discards data on this link, so sending is pointless.
            val connections = createTestConnections(2)
            val now = nowMs()

            connections[0].phase = LinkPhase.Registering
            connections[0].inFlightPackets = 0 // would otherwise be the best score
            connections[1].inFlightPackets = 10

            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = false,
            )
            val selected = selectConnectionIdx(connections, null, now, config)
            assertEquals(
                selected,
                1,
                "a link that has not completed REG3 must never be scheduled"
            )
        }

        test("test_config_integration") {
            val config = DynamicConfig()
            val snap = config.snapshot()

            // Default values from DynamicConfig::new()
            assertEquals(snap.mode, SchedulingMode.ENHANCED)
            assertTrue(snap.qualityEnabled)
        }

        test("test_constants") {
            assertTrue(SEQ_TRACKING_SIZE > 0)
            assertTrue(GLOBAL_TIMEOUT_MS > 0)

            // Should handle decent throughput (16384 entries)
            assertTrue(SEQ_TRACKING_SIZE >= 1000)
            // Should allow time for connections
            assertTrue(GLOBAL_TIMEOUT_MS >= 5000)
        }

        test("test_read_ip_list") {
            val tempFile = Files.createTempFile("test_ips", ".txt")
            try {
                Files.write(tempFile, "192.168.1.1\n192.168.1.2\n\n192.168.1.3\ninvalid-ip\n".toByteArray())

                val (ips, weights) = readWeightedIpList(tempFile.toString())

                assertEquals(ips.size, 3)
                assertEquals(ips[0], InetAddress.getByName("192.168.1.1"))
                assertEquals(ips[1], InetAddress.getByName("192.168.1.2"))
                assertEquals(ips[2], InetAddress.getByName("192.168.1.3"))
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }

        test("test_read_ip_list_empty") {
            val tempFile = Files.createTempFile("test_ips_empty", ".txt")
            try {
                Files.write(tempFile, ByteArray(0))

                val (ips, weights) = readWeightedIpList(tempFile.toString())

                assertTrue(ips.isEmpty())
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }

        test("test_read_ip_list_nonexistent") {
            assertFailsWith<IOException>("read IPs file") {
                readWeightedIpList("/nonexistent/file.txt")
            }
        }

        test("test_apply_connection_changes_remove_stale") {
            val connections = createTestConnections(3)
            val initialCount = connections.size

            // New IPs that don't include all current connections
            val newIps = listOf(
                InetAddress.getByName("192.168.1.10"), // Keep first connection
                InetAddress.getByName("192.168.1.50")  // New IP
            )

            val state = SenderState(
                connections = connections,
                connIo = createTestConnIoMap(connections)
            )
            state.lastSelectedIdx = 1

            val now = nowMs()
            // Insert entries for connections that will be removed
            state.seqTracker.insert(100, connections[1].connId, now)
            state.seqTracker.insert(200, connections[2].connId, now)

            val binder = SourceIpBinder
            applyConnectionChanges(state, newIps, emptyList(), "127.0.0.1", 8080, binder)

            // Should have removed some connections
            assertTrue(state.connections.size < initialCount)

            // Should have reset selection
            assertNull(state.lastSelectedIdx)

            // Entries for removed connections should now return None
            assertNull(state.seqTracker.get(100, now))
            assertNull(state.seqTracker.get(200, now))
        }

        test("recover_connection_clears_sequence_ownership") {
            val connections = createTestConnections(2)
            val now = nowMs()
            val seqTracker = SequenceTracker()
            seqTracker.insert(100, connections[0].connId, now)
            seqTracker.insert(101, connections[1].connId, now)

            recoverConnection(connections[0], seqTracker)

            // Connection should be marked for recovery (not connected)
            assertFalse(connections[0].connected, "the link must be marked for recovery")

            // The recovered link must not keep owning the sequences it queued
            assertNull(seqTracker.get(100, now), "the recovered link must not keep owning the sequences it queued")

            // The other link's ownership must be untouched
            assertEquals(seqTracker.get(101, now), connections[1].connId, "the other link's ownership must be untouched")
        }

        test("test_create_connections_from_ips") {
            val ips = listOf(
                InetAddress.getByName("127.0.0.1"),
                InetAddress.getByName("127.0.0.1")
            )

            // This will likely fail to connect but should not panic
            val binder = SourceIpBinder
            val connIo = HashMap<Long, ConnIo>()
            val connections = createConnectionsFromIps(ips, emptyList(), "127.0.0.1", 9999, binder, connIo)

            // Connections may be empty due to connection failures, which is OK for testing
            assertTrue(connections.size <= ips.size)

            // Clean up sockets
            for (io in connIo.values) {
                io.socket.close()
            }
        }

        test("test_pending_connection_changes") {
            val newIps = listOf(InetAddress.getByName("192.168.1.100"))
            val changes = PendingConnectionChanges(
                newIps = newIps,
                newWeights = listOf(1),
                receiverHost = "test-host",
                receiverPort = 9090
            )

            assertNotNull(changes.newIps)
            assertEquals(changes.newIps.size, 1)
            assertEquals(changes.receiverHost, "test-host")
            assertEquals(changes.receiverPort, 9090)
        }
    }
}
