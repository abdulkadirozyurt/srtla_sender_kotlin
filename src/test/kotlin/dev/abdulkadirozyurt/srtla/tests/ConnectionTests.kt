// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/tests/connection_tests.rs
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.connection.LinkPhase
import dev.abdulkadirozyurt.srtla.core.CONN_TIMEOUT_MS
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.protocol.IDLE_TIME
import dev.abdulkadirozyurt.srtla.protocol.PKT_LOG_SIZE
import dev.abdulkadirozyurt.srtla.protocol.SRT_TYPE_ACK
import dev.abdulkadirozyurt.srtla.protocol.SRT_TYPE_DATA
import dev.abdulkadirozyurt.srtla.protocol.SRT_TYPE_NAK
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_DEF
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_INCR
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_MAX
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_MULT
import dev.abdulkadirozyurt.srtla.sender.attributeNak
import dev.abdulkadirozyurt.srtla.sender.SequenceTracker
import dev.abdulkadirozyurt.srtla.sender.SEQUENCE_TRACKING_MAX_AGE_MS
import dev.abdulkadirozyurt.srtla.testkit.*

/** Build minimal 16-byte SRT control packet with srtType in first two bytes (big-endian). */
private fun makeSrtControl(srtType: Int): ByteArray {
    val pkt = ByteArray(16)
    pkt[0] = (srtType shr 8).toByte()
    pkt[1] = srtType.toByte()
    return pkt
}

/** Check if packet is an SRT ACK by examining first two bytes (big-endian). */
private fun isSrtAck(pkt: ByteArray): Boolean {
    if (pkt.size < 2) return false
    val type = ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF)
    return type == SRT_TYPE_ACK
}

/** Extract SRT packet type from first two bytes (big-endian). */
private fun getPacketType(pkt: ByteArray): Int? {
    if (pkt.size < 2) return null
    return ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF)
}

fun registerConnectionTests() {
    suite("connection") {
        test("connection_score") {
            val conn = createTestConnection()

            // Test basic score calculation
            var score = conn.getScore()
            var expected = WINDOW_DEF * WINDOW_MULT
            assertEquals(expected, score, "initial score should be WINDOW_DEF * WINDOW_MULT")

            // Test with in-flight packets
            conn.inFlightPackets = 5
            score = conn.getScore()
            expected = (WINDOW_DEF * WINDOW_MULT) / (5 + 1)
            assertEquals(expected, score, "score with in-flight packets")

            // Test disconnected connection
            conn.connected = false
            assertEquals(-1, conn.getScore(), "disconnected connection score should be -1")
        }

        test("packet_tracking") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            // Test packet registration
            val initialInFlight = conn.inFlightPackets
            conn.registerPacket(100, currentTime)
            assertEquals(initialInFlight + 1, conn.inFlightPackets, "in-flight count after registration")
            assertTrue(conn.packetLog.containsKey(100), "packet should be in log")

            // Test multiple packets
            for (i in 1..5) {
                conn.registerPacket(100 + i, currentTime)
            }
            assertEquals(initialInFlight + 6, conn.inFlightPackets, "in-flight count after multiple registrations")
        }

        test("srt_ack_handling") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            // Register some packets
            for (i in 1..5) {
                conn.registerPacket(i * 10, currentTime)
            }
            val initialInFlight = conn.inFlightPackets
            assertEquals(5, initialInFlight, "should have 5 packets in flight")

            // ACK the first three packets
            conn.handleSrtAck(30, nowMs(), true)
            assertTrue(conn.inFlightPackets < 5, "in-flight should decrease after ACK")

            // Test window increase behavior
            val initialWindow = conn.window
            conn.registerPacket(40, currentTime)
            conn.handleSrtlaAckSpecific(40, true, nowMs())
            assertTrue(conn.window >= initialWindow, "window should grow on SRTLA ACK")
        }

        test("nak_handling") {
            val conn = createTestConnection()
            val initialWindow = conn.window
            val currentTime = nowMs()

            // Register packets first
            conn.registerPacket(100, currentTime)
            conn.registerPacket(101, currentTime)
            conn.registerPacket(102, currentTime)
            conn.registerPacket(103, currentTime)

            // Test single NAK
            conn.handleNak(100, nowMs())
            assertEquals(1, conn.congestion.nakCount, "NAK count should increase")
            assertTrue(conn.window < initialWindow, "window should shrink on NAK")

            // Test NAK burst detection
            conn.handleNak(101, nowMs())
            assertTrue(conn.congestion.nakBurstCount >= 1, "NAK burst count should increase")
        }

        test("nak_handling_with_logged_packet") {
            val conn = createTestConnection()
            val initialWindow = conn.window
            val initialNakCount = conn.congestion.nakCount
            val currentTime = nowMs()

            // Register a packet
            conn.registerPacket(100, currentTime)

            // Handle NAK for that packet
            conn.handleNak(100, nowMs())

            assertTrue(conn.window < initialWindow, "Window should shrink on NAK")
            assertEquals(initialNakCount + 1, conn.congestion.nakCount, "NAK count should increment")
        }

        test("nak_burst_timing") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            for (i in 100..109) {
                conn.registerPacket(i, currentTime)
            }

            conn.handleNak(100, nowMs())
            assertEquals(0, conn.congestion.nakBurstCount, "first NAK should have burst count 0")
            assertEquals(1, conn.congestion.nakCount, "NAK count should be 1")

            conn.handleNak(101, nowMs())
            assertTrue(conn.congestion.nakBurstCount >= 1, "NAK burst count should increase")
            assertEquals(2, conn.congestion.nakCount, "NAK count should be 2")

            conn.handleNak(102, nowMs())
            assertTrue(conn.congestion.nakBurstCount >= 2, "NAK burst count should increase again")
            assertEquals(3, conn.congestion.nakCount, "NAK count should be 3")
        }

        test("nak_burst_reset_on_timeout") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            for (i in 100..109) {
                conn.registerPacket(i, currentTime)
            }

            conn.handleNak(100, nowMs())
            conn.handleNak(101, nowMs())
            conn.handleNak(102, nowMs())
            assertEquals(3, conn.congestion.nakBurstCount, "NAK burst count should be 3")

            conn.performWindowRecovery(nowMs())
            assertEquals(3, conn.congestion.nakBurstCount, "NAK burst count should stay 3 immediately")

            // Simulate time passing without NAKs
            conn.congestion.lastNakTimeMs = nowMs() - 1100
            conn.performWindowRecovery(nowMs())
            assertEquals(0, conn.congestion.nakBurstCount, "NAK burst count should reset after timeout")
        }

        test("nak_burst_warning_threshold") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            for (i in 100..109) {
                conn.registerPacket(i, currentTime)
            }

            conn.handleNak(100, nowMs())
            conn.handleNak(101, nowMs())
            assertEquals(2, conn.congestion.nakBurstCount)

            conn.handleNak(102, nowMs())
            assertEquals(3, conn.congestion.nakBurstCount)

            // Simulate timeout without NAKs to reset burst
            conn.congestion.lastNakTimeMs = nowMs() - 1100
            conn.performWindowRecovery(nowMs())
            assertEquals(0, conn.congestion.nakBurstCount, "NAK burst count should reset")
        }

        test("nak_burst_reconnect_reset") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            for (i in 100..104) {
                conn.registerPacket(i, currentTime)
            }

            conn.handleNak(100, nowMs())
            conn.handleNak(101, nowMs())
            conn.handleNak(102, nowMs())
            assertEquals(3, conn.congestion.nakBurstCount)
            assertTrue(conn.congestion.lastNakTimeMs > 0)

            // Reset for reconnect
            conn.resetForReconnect(nowMs())

            assertEquals(0, conn.congestion.nakBurstCount, "NAK burst count should reset on reconnect")
            assertEquals(0, conn.congestion.lastNakTimeMs, "last NAK time should reset on reconnect")
            assertEquals(0, conn.congestion.nakCount, "NAK count should reset on reconnect")
        }

        test("socket_rebuild_keeps_the_retry_count") {
            val conn = createTestConnection()
            conn.reconnection.connectionEstablishedMs = 1
            val now = nowMs()

            // Record multiple reconnect attempts
            for (i in 0..2) {
                conn.recordReconnectAttempt(now + i)
            }

            val attemptCountBefore = conn.reconnection.reconnectFailureCount
            assertTrue(attemptCountBefore >= 1, "should have recorded reconnect attempts")

            // Reset for reconnect (socket rebuild)
            conn.resetForReconnect(now + 3)

            assertEquals(attemptCountBefore, conn.reconnection.reconnectFailureCount, "reconnect failure count should survive socket rebuild")
        }

        test("nak_not_found_doesnt_affect_stats") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            conn.registerPacket(100, currentTime)

            val initialNakCount = conn.congestion.nakCount
            val initialBurstCount = conn.congestion.nakBurstCount
            val initialWindow = conn.window

            val found = conn.handleNak(999, nowMs())
            assertFalse(found, "NAK for nonexistent packet should return false")
            assertEquals(initialNakCount, conn.congestion.nakCount, "NAK count should not change")
            assertEquals(initialBurstCount, conn.congestion.nakBurstCount, "Burst count should not change")
            assertEquals(initialWindow, conn.window, "Window should not change")
        }

        test("srtla_ack_handling") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            // Register some packets
            for (i in 1..3) {
                conn.registerPacket(i * 100, currentTime)
            }
            assertEquals(3, conn.inFlightPackets, "should have 3 packets in flight")

            // Test specific SRTLA ACK
            val found = conn.handleSrtlaAckSpecific(200, true, nowMs())
            assertTrue(found, "SRTLA ACK should find the packet")
            assertEquals(2, conn.inFlightPackets, "in-flight should decrease")

            // Test not found
            val notFound = conn.handleSrtlaAckSpecific(999, true, nowMs())
            assertFalse(notFound, "SRTLA ACK for nonexistent packet should return false")
            assertEquals(2, conn.inFlightPackets, "in-flight should not change")

            // Test global SRTLA ACK
            val initialWindow = conn.window
            conn.handleSrtlaAckGlobal()
            assertEquals(initialWindow + 1, conn.window, "global ACK should increase window by 1")
        }

        test("cumulative_ack_only_measures_rtt_on_the_link_that_carried_the_seq") {
            val conns = createTestConnections(2)
            val t0 = nowMs()

            // Link 0 carries the unique copy; link 1 holds a duplicate probe
            conns[0].registerPacket(30, t0)
            conns[1].registerPacket(30, t0)

            // The ACK lands 40ms later. Link 0 owns seq 30; link 1 does not.
            conns[0].handleSrtAck(30, t0 + 40, true)
            conns[1].handleSrtAck(30, t0 + 40, false)

            assertTrue(conns[0].rtt.kalmanRtt.isInitialized, "carrying link should measure RTT")
            assertFalse(conns[1].rtt.kalmanRtt.isInitialized, "probing link should not measure RTT")
            assertEquals(0, conns[1].inFlightPackets, "probing link should still prune packets")
        }

        test("a_probe_survives_the_cumulative_ack_sweep_and_is_still_measurable") {
            val conn = createTestConnection()
            val t0 = nowMs()

            conn.queueProbePacket(ByteArray(100), 30, t0)
            assertEquals(0, conn.inFlightPackets, "probe should not count as in-flight")

            // The healthy link delivers the twin; the cumulative ACK sweeps past 30
            conn.handleSrtAck(30, t0 + 40, false)

            // A full second later, this link's own ACK for the probe finally lands
            assertTrue(conn.handleSrtlaAckSpecific(30, false, t0 + 1000), "probe should still be matchable after sweep")
            assertEquals(t0 + 1000, conn.lastAckOrRttSampleMs, "probe ACK should set delivery proof")
            val rtt = conn.getSmoothRttMs()
            assertTrue(kotlin.math.abs(rtt - 1000.0) < 1.0, "probe should measure real round trip")
        }

        test("a_probe_ack_does_not_grow_the_congestion_window") {
            val conn = createTestConnection()
            val t0 = nowMs()

            conn.window = 5000
            conn.queueProbePacket(ByteArray(100), 77, t0)
            assertTrue(conn.handleSrtlaAckSpecific(77, false, t0 + 50))

            assertEquals(5000, conn.window, "probe ACK should not grow window")
        }

        test("an_unanswered_probe_log_stays_bounded") {
            val conn = createTestConnection()
            val t0 = nowMs()

            for (seq in 0 until 5000) {
                conn.queueProbePacket(ByteArray(100), seq, t0 + seq.toLong())
            }
            assertTrue(conn.probeLog.size <= 8192, "probe log should stay bounded") // PROBE_LOG_SOFT_CAP
        }

        test("test_srtla_ack_feeds_the_smoothed_rtt") {
            val conn = createTestConnection()
            val t0 = nowMs()

            assertFalse(conn.rtt.kalmanRtt.isInitialized, "precondition: no RTT measured yet")

            conn.registerPacket(100, t0)
            assertTrue(conn.handleSrtlaAckSpecific(100, false, t0 + 50))

            assertTrue(conn.rtt.kalmanRtt.isInitialized, "ACK round trip should reach estimator")
            val srtt = conn.getSmoothRttMs()
            assertTrue(kotlin.math.abs(srtt - 50.0) < 1.0, "smoothed RTT should be ~50ms")
        }

        test("test_srtla_ack_rejects_implausible_round_trip") {
            val conn = createTestConnection()
            val t0 = nowMs()

            conn.registerPacket(100, t0)
            conn.registerPacket(200, t0)

            assertTrue(conn.handleSrtlaAckSpecific(100, false, t0))
            assertFalse(conn.rtt.kalmanRtt.isInitialized, "same-millisecond ACK is not a measurement")

            assertTrue(conn.handleSrtlaAckSpecific(200, false, t0 + 20_000))
            assertFalse(conn.rtt.kalmanRtt.isInitialized, "20s round trip is a clock jump")
        }

        test("test_classic_vs_enhanced_mode_ack_handling") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            // Set up connection with some packets in flight
            conn.registerPacket(100, currentTime)
            conn.registerPacket(200, currentTime)
            conn.registerPacket(300, currentTime)
            assertEquals(3, conn.inFlightPackets)

            conn.window = 1500
            val initialWindow = conn.window

            // Test CLASSIC MODE
            val found = conn.handleSrtlaAckSpecific(100, true, nowMs())
            assertTrue(found)
            assertEquals(initialWindow + WINDOW_INCR - 1, conn.window, "classic mode window increase")
            assertEquals(2, conn.inFlightPackets, "in-flight should decrease")
        }

        test("test_keepalive_needs") {
            val conn = createTestConnection()
            val now = nowMs()

            // Should need keepalive initially
            assertTrue(conn.needsKeepalive(now), "should need keepalive initially")

            // After sending keepalive
            conn.lastKeepaliveSent = now
            assertFalse(conn.needsKeepalive(now), "should not need keepalive immediately after")

            // After timeout
            conn.lastKeepaliveSent = now - (IDLE_TIME + 1) * 1000
            assertTrue(conn.needsKeepalive(now), "should need keepalive after timeout")
        }

        test("test_rtt_measurement_needs") {
            val conn = createTestConnection()

            assertTrue(conn.needsRttMeasurement(nowMs()), "should need RTT measurement initially")

            // After waiting for response
            conn.rtt.waitingForKeepaliveResponse = true
            assertFalse(conn.needsRttMeasurement(nowMs()), "should not need while waiting for response")

            // After timeout
            conn.rtt.waitingForKeepaliveResponse = false
            conn.rtt.lastRttMeasurementMs = nowMs() - 4000
            assertTrue(conn.needsRttMeasurement(nowMs()), "should need again after timeout")
        }

        test("test_window_recovery") {
            val conn = createTestConnection()

            // Simulate some NAKs to reduce window
            for (i in 0..4) {
                conn.registerPacket(100 + i, nowMs())
                conn.handleNak(100 + i, nowMs())
            }
            val reducedWindow = conn.window

            // Simulate time passing without NAKs
            conn.congestion.lastNakTimeMs = nowMs() - 3000
            conn.congestion.lastWindowIncreaseMs = nowMs() - 2500

            conn.performWindowRecovery(nowMs())
            assertTrue(conn.window > reducedWindow, "window should recover")
        }

        test("test_reconnect_logic") {
            val conn = createTestConnection()
            val now = nowMs()

            // Should allow first reconnect attempt
            assertTrue(conn.shouldAttemptReconnect(now), "should allow first reconnect attempt")

            // Record attempt
            conn.recordReconnectAttempt(now)
            assertTrue(conn.reconnection.reconnectFailureCount >= 1, "reconnect failure count should increment")

            // Test reconnect success
            conn.markReconnectSuccess()
            assertEquals(0, conn.reconnection.reconnectFailureCount, "reconnect failure count should reset on success")
        }

        test("test_timeout_detection") {
            val conn = createTestConnection()

            // Fresh connection should not be timed out
            assertFalse(conn.isTimedOut(nowMs()), "fresh connection should not be timed out")

            // Stamp last_received CONN_TIMEOUT_MS + 1 second in the past
            conn.lastReceived = nowMs() - (CONN_TIMEOUT_MS + 1000)
            assertTrue(conn.isTimedOut(nowMs()), "connection should be timed out after timeout period")

            // Disconnected connection
            conn.connected = false
            assertTrue(conn.isTimedOut(nowMs()), "disconnected connection should be timed out")
        }

        test("monotonic_clock_timeout") {
            val conn = createTestConnection()
            val now = nowMs()

            conn.lastReceived = now
            assertFalse(conn.isTimedOut(nowMs()), "just-received link is live")

            conn.lastReceived = now - (CONN_TIMEOUT_MS + 1000)
            assertTrue(conn.isTimedOut(nowMs()), "stamp past CONN_TIMEOUT_MS marks link timed out")
        }

        test("fresh_link_not_timed_out_yet") {
            val conn = createTestConnection()
            assertFalse(conn.isTimedOut(nowMs()), "freshly created link must not be timed out")
        }

        test("test_connection_state_management") {
            val conn = createTestConnection()

            assertTrue(conn.connected, "connection should be connected initially")

            // Test manual disconnection
            conn.connected = false
            assertFalse(conn.connected)
            assertEquals(-1, conn.getScore(), "disconnected connection score should be -1")
        }

        test("test_connection_recovery_mode") {
            val conn = createTestConnection()

            assertTrue(conn.connected)
            assertTrue(conn.getScore() > 0)
            val initialWindow = conn.window

            // Mark for recovery
            conn.markForRecovery()

            // Connection should now be marked disconnected
            assertFalse(conn.connected)

            // Should have reset state
            assertTrue(conn.isTimedOut(nowMs()), "should be timed out after recovery")
            assertEquals(WINDOW_DEF * WINDOW_MULT, conn.window, "window should reset")
            assertEquals(0, conn.inFlightPackets, "in-flight should reset")

            // Score should be -1 because link is disconnected
            assertEquals(-1, conn.getScore())
        }

        test("test_nak_statistics") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            assertEquals(0, conn.congestion.nakCount)
            assertEquals(0, conn.congestion.nakBurstCount)
            assertNull(conn.timeSinceLastNakMs(currentTime), "time since last NAK should be null initially")

            conn.registerPacket(100, currentTime)
            conn.handleNak(100, nowMs())
            assertEquals(1, conn.congestion.nakCount)
            assertNotNull(conn.timeSinceLastNakMs(nowMs()), "time since last NAK should be available")
        }

        test("test_fast_recovery_mode") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            assertFalse(conn.congestion.fastRecoveryMode)

            // Register packet and reduce window
            conn.registerPacket(100, currentTime)
            conn.window = 1500
            conn.handleNak(100, nowMs())

            assertTrue(conn.congestion.fastRecoveryMode, "fast recovery mode should be enabled")
        }

        test("test_packet_log_capacity") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            // Add more packets than default capacity
            for (i in 0..(PKT_LOG_SIZE + 10)) {
                conn.registerPacket(i, currentTime)
            }

            assertEquals(PKT_LOG_SIZE + 11, conn.packetLog.size, "packet log should store all packets")
            assertEquals(PKT_LOG_SIZE + 11, conn.inFlightPackets, "in-flight should match packet log size")
        }

        test("ack_reduces_in_flight") {
            // Test the broadcast eligibility predicate
            val ackPkt = makeSrtControl(SRT_TYPE_ACK)
            val nakPkt = makeSrtControl(SRT_TYPE_NAK)
            val dataPkt = makeSrtControl(SRT_TYPE_DATA)

            assertTrue(isSrtAck(ackPkt), "ACK packet must be ACK-classified")
            assertFalse(isSrtAck(nakPkt), "NAK packet is not an ACK")
            assertEquals(SRT_TYPE_NAK, getPacketType(nakPkt), "NAK packet must classify as NAK")
            assertFalse(isSrtAck(dataPkt), "data packet is never ACK-broadcast-eligible")

            // Test cumulative ACK reduces in-flight on correct uplinks
            val connections = createTestConnections(3)
            val now = nowMs()

            connections[0].registerPacket(10, now)
            connections[0].registerPacket(20, now)
            connections[0].registerPacket(30, now)
            connections[1].registerPacket(15, now)
            connections[1].registerPacket(25, now)
            connections[2].registerPacket(100, now)

            assertEquals(3, connections[0].inFlightPackets)
            assertEquals(2, connections[1].inFlightPackets)
            assertEquals(1, connections[2].inFlightPackets)

            // Broadcast cumulative ACK of 30 to every uplink
            for ((i, c) in connections.withIndex()) {
                c.handleSrtAck(30, nowMs(), i == 0)
            }

            assertEquals(0, connections[0].inFlightPackets, "uplink 0: 10/20/30 all cleared")
            assertEquals(0, connections[1].inFlightPackets, "uplink 1: 15/25 cleared")
            assertEquals(1, connections[2].inFlightPackets, "uplink 2: seq 100 > 30 stays in-flight")
        }

        test("test_progressive_window_recovery_rates") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            // Reduce window through NAKs
            conn.registerPacket(100, currentTime)
            conn.handleNak(100, nowMs())
            val reducedWindow = conn.window

            // Test 1: Recent NAKs (3 seconds ago) - should recover at 25% rate
            conn.window = reducedWindow
            conn.congestion.lastNakTimeMs = nowMs() - 3000
            conn.congestion.lastWindowIncreaseMs = nowMs() - 2500
            val before = conn.window
            conn.performWindowRecovery(nowMs())
            val recovery25 = conn.window - before
            assertEquals(WINDOW_INCR / 4, recovery25, "recent NAKs should recover at 25% rate")

            // Test 2: 6 seconds ago - should recover at 50% rate
            conn.window = reducedWindow
            conn.congestion.lastNakTimeMs = nowMs() - 6000
            conn.congestion.lastWindowIncreaseMs = nowMs() - 2500
            val before2 = conn.window
            conn.performWindowRecovery(nowMs())
            val recovery50 = conn.window - before2
            assertEquals(WINDOW_INCR / 2, recovery50, "5-7 seconds should recover at 50% rate")

            // Test 3: 8 seconds ago - should recover at 100% rate
            conn.window = reducedWindow
            conn.congestion.lastNakTimeMs = nowMs() - 8000
            conn.congestion.lastWindowIncreaseMs = nowMs() - 2500
            val before3 = conn.window
            conn.performWindowRecovery(nowMs())
            val recovery100 = conn.window - before3
            assertEquals(WINDOW_INCR, recovery100, "7-10 seconds should recover at 100% rate")

            // Test 4: 11 seconds ago - should recover at 200% rate
            conn.window = reducedWindow
            conn.congestion.lastNakTimeMs = nowMs() - 11000
            conn.congestion.lastWindowIncreaseMs = nowMs() - 2500
            val before4 = conn.window
            conn.performWindowRecovery(nowMs())
            val recovery200 = conn.window - before4
            assertEquals(WINDOW_INCR * 2, recovery200, "10+ seconds should recover at 200% rate")

            // Verify progressive rates
            assertTrue(recovery25 < recovery50)
            assertTrue(recovery50 < recovery100)
            assertTrue(recovery100 < recovery200)
        }

        test("test_progressive_recovery_with_fast_mode") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            // Reduce window and trigger fast recovery mode
            conn.registerPacket(100, currentTime)
            conn.window = 1500
            conn.handleNak(100, nowMs())
            assertTrue(conn.congestion.fastRecoveryMode)

            val reducedWindow = conn.window

            // Test fast mode with recent NAKs - should be 50% of normal rate
            conn.congestion.lastNakTimeMs = nowMs() - 3000
            conn.congestion.lastWindowIncreaseMs = nowMs() - 600
            val before = conn.window
            conn.performWindowRecovery(nowMs())
            val fastRecovery25 = conn.window - before
            assertEquals((WINDOW_INCR * 2) / 4, fastRecovery25, "fast mode recent NAKs 50% rate")

            // Test fast mode with 10+ seconds - should be 400% of normal rate
            conn.window = reducedWindow
            conn.congestion.lastNakTimeMs = nowMs() - 11000
            conn.congestion.lastWindowIncreaseMs = nowMs() - 600
            val before2 = conn.window
            conn.performWindowRecovery(nowMs())
            val fastRecovery200 = conn.window - before2
            assertEquals(WINDOW_INCR * 2 * 2, fastRecovery200, "fast mode 10+ seconds 400% rate")

            assertTrue(fastRecovery200 > WINDOW_INCR * 2)
        }

        test("test_progressive_recovery_timing_constraints") {
            val conn = createTestConnection()
            val currentTime = nowMs()

            // Reduce window
            conn.registerPacket(100, currentTime)
            conn.handleNak(100, nowMs())
            val reducedWindow = conn.window

            // Test normal mode timing constraint (2000ms min wait + 1000ms increment wait)
            conn.congestion.lastNakTimeMs = nowMs() - 8000
            conn.congestion.lastWindowIncreaseMs = nowMs() - 500 // Too recent
            val before = conn.window
            conn.performWindowRecovery(nowMs())
            assertEquals(before, conn.window, "should not recover when timing constraint not met")

            // Now allow enough time
            conn.congestion.lastWindowIncreaseMs = nowMs() - 1500 // Enough time
            conn.performWindowRecovery(nowMs())
            assertTrue(conn.window > before, "should recover when timing constraint is met")

            // Test fast mode timing constraint (500ms min wait + 300ms increment wait)
            conn.window = reducedWindow
            conn.congestion.fastRecoveryMode = true
            conn.congestion.lastNakTimeMs = nowMs() - 8000
            conn.congestion.lastWindowIncreaseMs = nowMs() - 200 // Too recent even for fast mode
            val beforeFast = conn.window
            conn.performWindowRecovery(nowMs())
            assertEquals(beforeFast, conn.window, "fast mode should not recover when timing constraint not met")

            // Now allow enough time for fast mode
            conn.congestion.lastWindowIncreaseMs = nowMs() - 400 // Enough for fast mode
            conn.performWindowRecovery(nowMs())
            assertTrue(conn.window > beforeFast, "fast mode should recover when timing constraint is met")
        }

        test("nak_attributed_to_sending_uplink") {
            val connections = createTestConnections(3).toMutableList()
            val now = nowMs()

            val seq: Int = 500
            // Uplink 1 is the sender: register in its packet_log + record attribution
            connections[1].registerPacket(seq, now)
            val seqTracker = SequenceTracker()
            seqTracker.insert(seq, connections[1].connId, now)

            val before: List<Int> = connections.map { it.congestion.nakCount }
            val beforeInflight = connections[1].inFlightPackets

            val counted = attributeNak(connections, seqTracker, seq, now)

            assertEquals(1, counted, "NAK must be attributed to uplink 1")
            assertEquals(before[1] + 1, connections[1].congestion.nakCount, "sending uplink NAK count increments")
            assertEquals(beforeInflight - 1, connections[1].inFlightPackets, "sending uplink in-flight decreases")
            assertEquals(before[0], connections[0].congestion.nakCount, "uplink 0 untouched")
            assertEquals(before[2], connections[2].congestion.nakCount, "uplink 2 untouched")
        }

        test("nak_unknown_uplink_fallback") {
            val connections = createTestConnections(3).toMutableList()
            val now = nowMs()
            val seqTracker = SequenceTracker() // deliberately empty

            // (a) untracked but present in uplink 2's packet_log → fallback finds it
            val known: Int = 700
            connections[2].registerPacket(known, now)
            val before2 = connections[2].congestion.nakCount

            val counted = attributeNak(connections, seqTracker, known, now)
            assertEquals(2, counted, "fallback attributes NAK to uplink 2")
            assertEquals(before2 + 1, connections[2].congestion.nakCount)
            assertEquals(0, connections[0].congestion.nakCount)
            assertEquals(0, connections[1].congestion.nakCount)

            // (b) truly unknown: not tracked and in no packet_log → no-op
            val countsBefore: List<Int> = connections.map { it.congestion.nakCount }
            val countedUnknown = attributeNak(connections, seqTracker, 999_999, now)
            assertNull(countedUnknown, "unknown sequence is attributable to none")
            val countsAfter: List<Int> = connections.map { it.congestion.nakCount }
            assertEquals(countsBefore, countsAfter, "unattributable NAK must not perturb any uplink")
        }

        test("nak_dedup_within_window") {
            val connections = createTestConnections(2).toMutableList()
            val base = nowMs()

            val seq: Int = 800
            connections[0].registerPacket(seq, base)
            val seqTracker = SequenceTracker()
            seqTracker.insert(seq, connections[0].connId, base)
            assertEquals(1, connections[0].inFlightPackets)

            // First sighting inside window: counted once on sending uplink
            val first = attributeNak(connections, seqTracker, seq, base)
            assertEquals(0, first, "first NAK should be attributed to uplink 0")
            assertEquals(1, connections[0].congestion.nakCount, "first NAK is counted")
            assertEquals(0, connections[0].inFlightPackets, "first NAK clears the in-flight packet")

            // Advance 50ms within dedup window (explicit now, no paused clock)
            val within = base + 50
            // 50ms must be inside dedup window
            assertTrue(within - base < SEQUENCE_TRACKING_MAX_AGE_MS.toLong(), "50ms is within tracking window")
            assertEquals(connections[0].connId, seqTracker.get(seq, within), "tracker still resolves sequence to uplink 0")

            // Duplicate NAK inside window: routed to same uplink, packet_log no longer has it → not counted
            val dup = attributeNak(connections, seqTracker, seq, within)
            assertNull(dup, "duplicate NAK is a no-op (single accounting)")
            assertEquals(1, connections[0].congestion.nakCount, "duplicate NAK within window is NOT double-counted")
            assertEquals(0, connections[0].inFlightPackets, "in-flight stays cleared after duplicate")
            assertEquals(0, connections[1].congestion.nakCount, "duplicate never leaks onto another uplink")
        }
    }
}
