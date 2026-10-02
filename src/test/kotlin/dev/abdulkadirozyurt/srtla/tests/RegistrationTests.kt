// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/registration/tests/mod.rs, src/registration/tests/probing.rs
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.connection.STARTUP_GRACE_MS
import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.protocol.REG2_TIMEOUT
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_ID_LEN
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG_ERR
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG_NGP
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG3
import dev.abdulkadirozyurt.srtla.protocol.createReg2Packet
import dev.abdulkadirozyurt.srtla.protocol.writeU16BE
import dev.abdulkadirozyurt.srtla.registration.RegistrationEvent
import dev.abdulkadirozyurt.srtla.registration.RegistrationManager
import dev.abdulkadirozyurt.srtla.testkit.assertEquals
import dev.abdulkadirozyurt.srtla.testkit.assertFalse
import dev.abdulkadirozyurt.srtla.testkit.assertNotEquals
import dev.abdulkadirozyurt.srtla.testkit.assertNotNull
import dev.abdulkadirozyurt.srtla.testkit.assertNull
import dev.abdulkadirozyurt.srtla.testkit.assertTrue
import dev.abdulkadirozyurt.srtla.testkit.suite
import kotlin.math.max

// Helper to create a REG_NGP packet
private fun createRegNgpPacket(): ByteArray {
    val buf = ByteArray(2)
    writeU16BE(buf, 0, SRTLA_TYPE_REG_NGP)
    return buf
}

// Helper to create a REG3 packet
private fun createReg3Packet(): ByteArray {
    val buf = ByteArray(2)
    writeU16BE(buf, 0, SRTLA_TYPE_REG3)
    return buf
}

// Helper to create a REG_ERR packet
private fun createRegErrPacket(): ByteArray {
    val buf = ByteArray(2)
    writeU16BE(buf, 0, SRTLA_TYPE_REG_ERR)
    return buf
}

/**
 * Mirror of the registration arms in sender/UplinkRecv.kt, as upstream's
 * registration_tests.rs does. Kept in the test so a gate change is measured by
 * its real consequence on a connection, not just by the event enum.
 */
private fun applyShellRegistrationEffects(event: RegistrationEvent?, conn: SrtlaConnection, now: Long) {
    when (event) {
        RegistrationEvent.REG3 -> {
            conn.clearPreRegistrationState(now)
            conn.connected = true
            conn.lastReceived = now
        }
        RegistrationEvent.REG_ERR -> {
            conn.connected = false
            conn.lastReceived = null
        }
        else -> {}
    }
}

fun registerRegistrationTests() {
    suite("registration") {
        test("manager_creation") {
            val reg = RegistrationManager()

            assertEquals(0, reg.activeConnections)
            assertFalse(reg.hasConnected)
            assertFalse(reg.broadcastReg2Pending)
            assertNull(reg.pendingReg2Idx())
            assertNull(reg.reg1TargetIdx)
        }

        test("reg_ngp_handling") {
            val reg = RegistrationManager()
            val buf = createRegNgpPacket()

            val handled = reg.processRegistrationPacket(1, buf, nowMs())
            assertNotNull(handled)
            assertEquals(1, reg.reg1TargetIdx)

            val currentTime = nowMs()
            assertTrue(reg.reg1NextSendAtMs <= currentTime + 100, "Allow CI scheduler skew")
        }

        test("reg2_handling") {
            val reg = RegistrationManager()

            reg.setPendingReg2Idx(0)
            val originalId = reg.srtlaId.copyOf()

            val modifiedId = originalId.copyOf()
            modifiedId.fill(0xab.toByte(), SRTLA_ID_LEN / 2)
            val buf = createReg2Packet(modifiedId)

            val handled = reg.processRegistrationPacket(0, buf, nowMs())
            assertNotNull(handled)

            assertEquals(modifiedId.contentToString(), reg.srtlaId.contentToString())
            assertNull(reg.pendingReg2Idx())
            assertTrue(reg.broadcastReg2Pending)
            assertNull(reg.reg1TargetIdx)
        }

        test("reg3_handling") {
            val reg = RegistrationManager()

            assertEquals(0, reg.activeConnections)
            assertFalse(reg.hasConnected)

            val buf = createReg3Packet()

            reg.buildReg2(2)
            val handled = reg.processRegistrationPacket(2, buf, nowMs())
            assertNotNull(handled)

            assertTrue(reg.hasConnected)
        }

        test("reg_err_handling") {
            val reg = RegistrationManager()
            val now = nowMs()

            reg.setPendingReg2Idx(0)
            reg.setPendingTimeoutAtMs(now + 5000)
            reg.setReg1TargetIdx(0)

            val buf = createRegErrPacket()

            val handled = reg.processRegistrationPacket(0, buf, now)
            assertNotNull(handled)

            val after = now
            assertNull(reg.pendingReg2Idx())
            assertEquals(0L, reg.pendingTimeoutAtMs)
            assertNull(reg.reg1TargetIdx)
            assertTrue(reg.reg1NextSendAtMs >= after + REG2_TIMEOUT * 1000)
        }

        test("unrecognized_packet") {
            val reg = RegistrationManager()

            val buf = ByteArray(4)
            writeU16BE(buf, 0, 0x8002)  // SRT_TYPE_ACK

            val handled = reg.processRegistrationPacket(0, buf, nowMs())
            assertNull(handled)
        }

        test("reg_driver_initial_reg1") {
            val reg = RegistrationManager()
            val connections = listOf(createTestConnection())

            val ngp = createRegNgpPacket()
            reg.processRegistrationPacket(0, ngp, nowMs())

            reg.regDriverPendingSends(connections.size, nowMs())

            assertEquals(0, reg.pendingReg2Idx())
            assertTrue(reg.pendingTimeoutAtMs > nowMs())
        }

        test("reg_driver_with_target") {
            val reg = RegistrationManager()
            val connections = listOf(createTestConnection(), createTestConnection())

            reg.setReg1TargetIdx(1)

            reg.regDriverPendingSends(connections.size, nowMs())

            assertEquals(1, reg.pendingReg2Idx())
        }

        test("reg_driver_waits_for_ngp") {
            val reg = RegistrationManager()
            val connections = listOf(createTestConnection())

            reg.regDriverPendingSends(connections.size, nowMs())
            assertNull(reg.pendingReg2Idx())

            val ngp = createRegNgpPacket()
            reg.processRegistrationPacket(0, ngp, nowMs())

            reg.regDriverPendingSends(connections.size, nowMs())
            assertEquals(0, reg.pendingReg2Idx())
        }

        test("broadcast_reg2") {
            val reg = RegistrationManager()
            val connections = listOf(createTestConnection(), createTestConnection())

            reg.setBroadcastReg2Pending(true)
            reg.regDriverPendingSends(connections.size, nowMs())

            assertFalse(reg.broadcastReg2Pending)
        }

        test("send_reg1_to_sets_pending_state") {
            val reg = RegistrationManager()
            reg.buildReg1For(0, nowMs())

            assertEquals(0, reg.pendingReg2Idx())
            assertEquals(0, reg.reg1TargetIdx)
            val now = nowMs()
            assertTrue(reg.pendingTimeoutAtMs >= now)
            assertTrue(reg.reg1NextSendAtMs >= now)
            assertTrue(reg.reg1NextSendAtMs <= reg.pendingTimeoutAtMs)
        }

        test("send_reg2_to_does_not_override_state") {
            val reg = RegistrationManager()
            reg.setPendingReg2Idx(1)
            reg.setReg1TargetIdx(1)

            reg.buildReg2(0)

            assertEquals(1, reg.pendingReg2Idx())
            assertEquals(1, reg.reg1TargetIdx)
        }

        test("clear_pending_if_timed_out") {
            val reg = RegistrationManager()

            val start = nowMs()
            reg.setPendingReg2Idx(0)
            reg.setPendingTimeoutAtMs(start + 10)
            reg.setReg1TargetIdx(0)
            reg.setReg1NextSendAtMs(start + 1000)

            val clearedTime = start + 20
            val cleared = reg.clearPendingIfTimedOut(clearedTime)

            assertEquals(0, cleared)
            assertNull(reg.pendingReg2Idx())
            assertEquals(0L, reg.pendingTimeoutAtMs)
            assertNull(reg.reg1TargetIdx)
            assertEquals(clearedTime, reg.reg1NextSendAtMs)
        }

        test("multiple_reg3_connections") {
            val reg = RegistrationManager()
            val reg3Packet = createReg3Packet()

            val connections = listOf(createTestConnection(), createTestConnection(), createTestConnection())

            reg.setBroadcastReg2Pending(true)
            reg.regDriverPendingSends(connections.size, nowMs())

            for (i in 0..2) {
                val handled = reg.processRegistrationPacket(i, reg3Packet, nowMs())
                assertNotNull(handled)
            }

            assertTrue(reg.hasConnected)

            reg.updateActiveConnections(connections)

            assertEquals(3, reg.activeConnections)
        }

        test("reg_driver_timing") {
            val reg = RegistrationManager()

            reg.setReg1NextSendAtMs(nowMs() + 5000)

            val connections = listOf(createTestConnection())
            reg.regDriverPendingSends(connections.size, nowMs())
            assertNull(
                reg.pendingReg2Idx(),
                "Should not send before next-send time"
            )
        }

        test("registration_state_transitions") {
            val reg = RegistrationManager()
            val connections = listOf(createTestConnection())

            assertEquals(0, reg.activeConnections)
            assertFalse(reg.hasConnected)

            val ngpPacket = createRegNgpPacket()
            reg.processRegistrationPacket(0, ngpPacket, nowMs())
            assertEquals(0, reg.reg1TargetIdx)

            reg.setPendingReg2Idx(0)

            val modifiedId = reg.srtlaId.copyOf()
            modifiedId.fill(0xff.toByte(), SRTLA_ID_LEN / 2)
            val reg2Packet = createReg2Packet(modifiedId)
            reg.processRegistrationPacket(0, reg2Packet, nowMs())

            assertTrue(reg.broadcastReg2Pending)
            assertNull(reg.pendingReg2Idx())

            reg.regDriverPendingSends(connections.size, nowMs())

            val reg3Packet = createReg3Packet()
            reg.processRegistrationPacket(0, reg3Packet, nowMs())

            assertTrue(reg.hasConnected)

            reg.updateActiveConnections(connections)
            assertEquals(1, reg.activeConnections)
        }

        test("id_generation_uniqueness") {
            val ids = (0..7).map { RegistrationManager().srtlaId }
            val allSame = ids.zipWithNext().all { (a, b) -> a.contentEquals(b) }
            assertFalse(allSame, "All generated IDs were identical unexpectedly")
        }

        test("start_probing_emits_a_probe_per_connection") {
            val reg = RegistrationManager()
            val connections = mutableListOf(createTestConnection(), createTestConnection())

            assertNull(reg.reg1TargetIdx)

            val probes = reg.startProbing(connections, nowMs())

            assertEquals(2, probes.size, "one probe packet queued per connection")
            assertTrue(reg.isProbing(), "driver is now awaiting probe responses")
        }

        test("probing_skipped_when_active_connections") {
            val reg = RegistrationManager()
            val connections = mutableListOf(createTestConnection())

            connections[0].connected = true
            connections[0].lastReceived = nowMs()
            reg.updateActiveConnections(connections)

            val initialTarget = reg.reg1TargetIdx
            reg.startProbing(connections, nowMs())

            assertEquals(initialTarget, reg.reg1TargetIdx)
            assertFalse(reg.isProbing())
        }

        test("probe_response_tracking") {
            val reg = RegistrationManager()

            reg.setProbingStateWaiting()
            reg.simulateProbeResult(0, 100)
            reg.simulateProbeResult(1, 200)

            assertEquals(2, reg.probeResultsCount())
        }

        test("check_probing_complete_selects_lowest_rtt") {
            val reg = RegistrationManager()

            reg.setProbingStateWaiting()
            reg.simulateProbeResult(0, 150)
            reg.simulateProbeResult(1, 50)
            reg.simulateProbeResult(2, 200)

            val completed = reg.checkProbingComplete()

            assertTrue(completed)
            assertEquals(1, reg.reg1TargetIdx)
            assertFalse(reg.isProbing())
        }

        test("check_probing_timeout_selects_best_available") {
            val reg = RegistrationManager()

            reg.setProbingStateWaiting()
            reg.simulateProbeResult(0, 100)

            Thread.sleep(2100)

            val completed = reg.checkProbingComplete()

            assertTrue(completed)
            assertEquals(0, reg.reg1TargetIdx)
            assertFalse(reg.isProbing())
        }

        test("check_probing_no_responses_uses_fallback") {
            val reg = RegistrationManager()

            reg.setProbingStateWaiting()

            Thread.sleep(2100)

            val completed = reg.checkProbingComplete()

            assertTrue(completed)
            assertEquals(0, reg.reg1TargetIdx)
            assertFalse(reg.isProbing())
        }

        test("handle_probe_response_records_rtt") {
            val reg = RegistrationManager()

            reg.setProbingStateWaiting()
            reg.simulateProbeResult(0, 0)
            reg.simulateProbeResult(1, 0)

            Thread.sleep(50)
            reg.handleProbeResponse(0, nowMs())

            Thread.sleep(50)
            reg.handleProbeResponse(1, nowMs())

            val completed = reg.checkProbingComplete()

            assertTrue(completed)
            assertEquals(0, reg.reg1TargetIdx)
        }

        test("reg_ngp_during_probing_handled_as_probe_response") {
            val reg = RegistrationManager()

            reg.setProbingStateWaiting()
            reg.simulateProbeResult(0, 0)

            val ngpPacket = createRegNgpPacket()
            Thread.sleep(50)

            reg.processRegistrationPacket(0, ngpPacket, nowMs())

            assertTrue(reg.isProbing())
            assertEquals(1, reg.probeResultsCount())
        }

        test("reg_ngp_after_probing_updates_target") {
            val reg = RegistrationManager()

            reg.setProbingStateWaiting()
            reg.simulateProbeResult(0, 100)
            reg.checkProbingComplete()

            assertFalse(reg.isProbing())
            assertEquals(0, reg.reg1TargetIdx)

            val ngpPacket = createRegNgpPacket()
            reg.processRegistrationPacket(1, ngpPacket, nowMs())

            assertEquals(1, reg.reg1TargetIdx)
        }

        test("reg_handshake_two_phase_flow") {
            val reg = RegistrationManager()
            val connections = listOf(createTestConnection(), createTestConnection())

            val ngp = createRegNgpPacket()
            reg.processRegistrationPacket(0, ngp, nowMs())
            reg.regDriverPendingSends(connections.size, nowMs())
            assertEquals(
                0,
                reg.pendingReg2Idx(),
                "REG1 sent on conn 0 -> awaiting REG2"
            )

            val senderPrefix = reg.srtlaId.copyOf()
            val fullId = senderPrefix.copyOf()
            fullId.fill(0x5a, SRTLA_ID_LEN / 2)
            reg.processRegistrationPacket(0, createReg2Packet(fullId), nowMs())

            assertEquals(fullId.contentToString(), reg.srtlaId.contentToString(), "conn 0 adopts the receiver full_id")
            assertTrue(reg.broadcastReg2Pending, "REG2 broadcast queued")
            assertNull(reg.pendingReg2Idx(), "REG2 received clears pending")

            val broadcast = createReg2Packet(reg.srtlaId)
            assertEquals(
                fullId.slice(0..SRTLA_ID_LEN - 1).joinToString("") { it.toString() },
                broadcast.slice(2..SRTLA_ID_LEN + 1).joinToString("") { it.toString() },
                "broadcast REG2 carries the full_id to conn N"
            )
            reg.regDriverPendingSends(connections.size, nowMs())
            assertFalse(
                reg.broadcastReg2Pending,
                "REG2 broadcast consumed after sending to all uplinks"
            )

            val reg3 = createReg3Packet()
            for (idx in 0 until connections.size) {
                assertTrue(
                    reg.processRegistrationPacket(idx, reg3, nowMs()) != null,
                    "REG3 on conn $idx handled"
                )
            }
            assertTrue(reg.hasConnected, "REG3 marks the handshake complete")
        }

        test("full_id_propagation_byte_wise") {
            val reg = RegistrationManager()
            reg.setPendingReg2Idx(0)

            val senderId = reg.srtlaId.copyOf()
            val half = SRTLA_ID_LEN / 2

            val fullId = senderId.copyOf()
            for (i in half until SRTLA_ID_LEN) fullId[i] = 0xc3.toByte()

            reg.processRegistrationPacket(0, createReg2Packet(fullId), nowMs())

            assertEquals(
                senderId.slice(0 until half).joinToString(),
                reg.srtlaId.slice(0 until half).joinToString(),
                "first half (sender prefix) must be preserved byte-for-byte"
            )
            assertEquals(
                fullId.slice(half until SRTLA_ID_LEN).joinToString(),
                reg.srtlaId.slice(half until SRTLA_ID_LEN).joinToString(),
                "second half must equal the receiver-substituted tail"
            )
            for (i in half until SRTLA_ID_LEN) {
                assertEquals(0xc3.toByte(), reg.srtlaId[i], "tail byte $i not substituted")
            }
        }

        test("reg2_timeout_fires_at_4s_logical") {
            val reg = RegistrationManager()
            val base = nowMs()
            reg.buildReg1For(0, base)
            assertEquals(0, reg.pendingReg2Idx())

            val deadline = reg.pendingTimeoutAtMs
            assertTrue(
                deadline >= base + REG2_TIMEOUT * 1000 && deadline <= nowMs() + REG2_TIMEOUT * 1000,
                "REG2 deadline must be REG2_TIMEOUT (4s) past the REG1 send"
            )

            assertNull(
                reg.clearPendingIfTimedOut(deadline - 1),
                "must not time out before REG2_TIMEOUT"
            )
            assertEquals(
                0,
                reg.clearPendingIfTimedOut(deadline),
                "REG2 wait must time out at REG2_TIMEOUT (4s)"
            )
            assertNull(reg.pendingReg2Idx(), "timeout clears pending")
            assertEquals(
                0L,
                reg.pendingTimeoutAtMs,
                "timeout clears the deadline"
            )
        }

        test("reg3_timeout_fires_at_4s_logical") {
            val reg = RegistrationManager()

            reg.setPendingReg2Idx(0)
            val fullId = reg.srtlaId.copyOf()
            fullId.fill(0x7e, SRTLA_ID_LEN / 2)

            val base = nowMs()
            reg.processRegistrationPacket(0, createReg2Packet(fullId), nowMs())

            val deadline = reg.pendingTimeoutAtMs
            assertTrue(
                deadline >= base + REG2_TIMEOUT * 1000 && deadline <= nowMs() + REG2_TIMEOUT * 1000,
                "REG3 deadline must be REG3_TIMEOUT (4s) past the received REG2"
            )

            reg.setPendingReg2Idx(0)
            assertNull(
                reg.clearPendingIfTimedOut(deadline - 1),
                "must not time out before REG3_TIMEOUT"
            )
            assertEquals(
                0,
                reg.clearPendingIfTimedOut(deadline),
                "REG3 wait must time out at REG3_TIMEOUT (4s)"
            )
        }

        test("unsolicited_reg3_is_ignored_and_counted") {
            val reg = RegistrationManager()
            val conn = createTestConnection()
            conn.connected = false

            val packet = createReg3Packet()
            val event = reg.processRegistrationPacket(0, packet, nowMs())
            assertNull(event, "an unsolicited REG3 produces no event")
            applyShellRegistrationEffects(event, conn, nowMs())

            assertFalse(
                reg.hasConnected,
                "a REG3 with no REG2 in flight must not register anything"
            )
            assertFalse(conn.connected, "the uplink must stay unregistered")
            assertEquals(1L, reg.outOfPhaseReg3)

            reg.buildReg2(0)
            val event2 = reg.processRegistrationPacket(0, createReg3Packet(), nowMs())
            assertEquals(RegistrationEvent.REG3, event2)
            assertTrue(reg.hasConnected)
            assertEquals(1L, reg.outOfPhaseReg3, "no new rejection")
        }

        test("replayed_reg3_does_not_wipe_a_live_connection") {
            val reg = RegistrationManager()
            val conn = createTestConnection()
            val now = nowMs()

            reg.buildReg2(0)
            assertTrue(reg.isAwaitingReg3(0))
            val packet = createReg3Packet()
            val event = reg.processRegistrationPacket(0, packet, now)
            applyShellRegistrationEffects(event, conn, now)
            assertTrue(
                conn.connected,
                "the first in-phase REG3 registers the uplink"
            )
            assertFalse(
                reg.isAwaitingReg3(0),
                "the one-shot grant is consumed by the REG3 it authorized"
            )

            conn.registerPacket(7, now)
            conn.registerPacket(8, now)
            assertEquals(2, conn.inFlightPackets)

            val packet2 = createReg3Packet()
            val event2 = reg.processRegistrationPacket(0, packet2, nowMs())
            assertNull(
                event2,
                "a replayed REG3 must not reach the shell's registration effects"
            )
            applyShellRegistrationEffects(event2, conn, nowMs())

            assertEquals(1L, reg.outOfPhaseReg3)
            assertEquals(
                2,
                conn.inFlightPackets,
                "a replayed REG3 must not clear a live uplink's in-flight state"
            )
            assertEquals(
                2,
                conn.packetLog.size,
                "packet log must survive the replay"
            )
            assertTrue(conn.connected, "the link stays established")
        }

        test("reg3_flood_leaves_a_registered_link_untouched") {
            val reg = RegistrationManager()
            val conn = createTestConnection()
            val now = nowMs()

            reg.buildReg2(0)
            val packet = createReg3Packet()
            val event = reg.processRegistrationPacket(0, packet, now)
            applyShellRegistrationEffects(event, conn, now)
            conn.registerPacket(1, now)

            for (i in 0..63) {
                val ePacket = createReg3Packet()
                val e = reg.processRegistrationPacket(0, ePacket, nowMs())
                assertNull(e)
                applyShellRegistrationEffects(e, conn, nowMs())
            }

            assertEquals(64L, reg.outOfPhaseReg3)
            assertTrue(conn.connected)
            assertEquals(1, conn.inFlightPackets)
        }

        test("reconnect_reg2_rearms_the_reg3_gate") {
            val reg = RegistrationManager()
            val conn = createTestConnection()
            val now = nowMs()

            reg.buildReg2(0)
            val packet = createReg3Packet()
            val event = reg.processRegistrationPacket(0, packet, now)
            applyShellRegistrationEffects(event, conn, now)
            assertTrue(conn.connected)

            conn.markForRecovery()
            assertFalse(conn.connected)
            reg.updateActiveConnections(listOf(conn))
            reg.buildReg2(0)
            assertTrue(
                reg.isAwaitingReg3(0),
                "the reconnect REG2 must re-arm the one-shot grant"
            )

            val packet2 = createReg3Packet()
            val event2 = reg.processRegistrationPacket(0, packet2, nowMs())
            assertEquals(RegistrationEvent.REG3, event2, "the reconnect REG3 must be honored")
            applyShellRegistrationEffects(event2, conn, nowMs())
            assertTrue(conn.connected, "the link re-registers")
            assertEquals(0L, reg.outOfPhaseReg3)
        }

        test("reg2_broadcast_does_not_rearm_a_connected_uplink") {
            val reg = RegistrationManager()
            val connections = mutableListOf(createTestConnection(), createTestConnection())
            val now = nowMs()
            connections[0].connected = false
            connections[1].connected = false

            reg.buildReg2(0)
            val packet = createReg3Packet()
            val event = reg.processRegistrationPacket(0, packet, now)
            applyShellRegistrationEffects(event, connections[0], now)
            connections[0].registerPacket(42, now)
            assertTrue(connections[0].connected && connections[0].inFlightPackets == 1)

            reg.updateActiveConnections(connections)
            reg.setBroadcastReg2Pending(true)
            val sends = reg.regDriverPendingSends(connections.size, nowMs())
            assertNotNull(
                sends.broadcastReg2,
                "the broadcast still goes out"
            )
            assertFalse(
                reg.isAwaitingReg3(0),
                "the connected uplink's consumed grant must not be re-armed"
            )
            assertTrue(
                reg.isAwaitingReg3(1),
                "an unregistered uplink still gets a grant"
            )

            val packet2 = createReg3Packet()
            val event2 = reg.processRegistrationPacket(0, packet2, nowMs())
            applyShellRegistrationEffects(event2, connections[0], nowMs())
            assertEquals(
                1,
                connections[0].inFlightPackets,
                "the live uplink keeps its in-flight state"
            )
            assertTrue(connections[0].connected)

            val packet3 = createReg3Packet()
            val event3 = reg.processRegistrationPacket(1, packet3, nowMs())
            assertEquals(RegistrationEvent.REG3, event3, "uplink 1 registers normally")
            applyShellRegistrationEffects(event3, connections[1], nowMs())
            assertTrue(connections[1].connected)
        }

        test("reg_err_flood_does_not_disconnect_a_registered_link") {
            val reg = RegistrationManager()
            val conn = createTestConnection()
            val now = nowMs()

            reg.buildReg2(0)
            val packet = createReg3Packet()
            val event = reg.processRegistrationPacket(0, packet, now)
            applyShellRegistrationEffects(event, conn, now)
            conn.registerPacket(5, now)
            assertTrue(conn.connected)

            for (i in 0..63) {
                val ePacket = createRegErrPacket()
                val e = reg.processRegistrationPacket(0, ePacket, nowMs())
                assertNull(
                    e,
                    "an out-of-phase REG_ERR must not reach the shell's teardown"
                )
                applyShellRegistrationEffects(e, conn, nowMs())
            }

            assertTrue(
                conn.connected,
                "a forged REG_ERR flood must not tear down a healthy registered link"
            )
            assertEquals(1, conn.inFlightPackets)
            assertEquals(64L, reg.outOfPhaseRegErr)
            assertNull(reg.pendingReg2Idx())
        }

        test("out_of_phase_reg_err_does_not_damage_another_uplinks_handshake") {
            val reg = RegistrationManager()

            reg.buildReg1For(1, nowMs())
            assertEquals(1, reg.pendingReg2Idx())
            val deadline = reg.pendingTimeoutAtMs

            val event = reg.processRegistrationPacket(0, createRegErrPacket(), nowMs())
            assertNull(event)

            assertEquals(
                1,
                reg.pendingReg2Idx(),
                "uplink B keeps its in-flight REG2 window"
            )
            assertEquals(1, reg.reg1TargetIdx, "uplink B keeps its target")
            assertEquals(deadline, reg.pendingTimeoutAtMs)
            assertEquals(1L, reg.outOfPhaseRegErr)
        }

        test("in_phase_reg_err_still_aborts_the_registration") {
            // Awaiting REG2.
            val reg = RegistrationManager()
            val conn = createTestConnection()
            conn.connected = true
            reg.buildReg1For(0, nowMs())

            val after = nowMs()
            val packet = createRegErrPacket()
            val event = reg.processRegistrationPacket(0, packet, after)
            assertEquals(RegistrationEvent.REG_ERR, event)
            applyShellRegistrationEffects(event, conn, after)

            assertFalse(conn.connected, "an in-phase REG_ERR tears the link down")
            assertNull(reg.pendingReg2Idx())
            assertNull(reg.reg1TargetIdx)
            assertEquals(0L, reg.pendingTimeoutAtMs)
            assertTrue(reg.reg1NextSendAtMs >= after + REG2_TIMEOUT * 1000)
            assertEquals(0L, reg.outOfPhaseRegErr)

            // Awaiting REG3.
            val reg2 = RegistrationManager()
            val conn2 = createTestConnection()
            conn2.connected = true
            reg2.buildReg2(0)
            assertTrue(reg2.isAwaitingReg3(0))

            val packet2 = createRegErrPacket()
            val event2 = reg2.processRegistrationPacket(0, packet2, nowMs())
            assertEquals(RegistrationEvent.REG_ERR, event2)
            applyShellRegistrationEffects(event2, conn2, nowMs())

            assertFalse(conn2.connected, "a REG_ERR while awaiting REG3 also aborts")
            assertFalse(
                reg2.isAwaitingReg3(0),
                "the in-phase REG_ERR revokes the grant"
            )
            assertEquals(0L, reg2.outOfPhaseRegErr)
        }

        test("hardened_flow_still_registers_and_recovers") {
            val reg = RegistrationManager()
            val connections = mutableListOf(createTestConnection(), createTestConnection())

            for (conn in connections) {
                conn.connected = false
            }
            val ngp = createRegNgpPacket()
            reg.processRegistrationPacket(0, ngp, nowMs())
            reg.regDriverPendingSends(connections.size, nowMs())
            assertEquals(0, reg.pendingReg2Idx())

            val fullId = reg.srtlaId.copyOf()
            fullId.fill(0x5a, SRTLA_ID_LEN / 2)
            reg.processRegistrationPacket(0, createReg2Packet(fullId), nowMs())
            reg.updateActiveConnections(connections)
            reg.regDriverPendingSends(connections.size, nowMs())

            for ((idx, conn) in connections.withIndex()) {
                val now = nowMs()
                val pkt = createReg3Packet()
                val event = reg.processRegistrationPacket(idx, pkt, now)
                assertEquals(
                    RegistrationEvent.REG3,
                    event,
                    "uplink $idx must register on the cold-start flow"
                )
                applyShellRegistrationEffects(event, conn, now)
            }
            assertTrue(connections.all { it.connected })
            reg.updateActiveConnections(connections)
            assertEquals(2, reg.activeConnections)

            for (conn in connections) {
                conn.markForRecovery()
            }
            reg.updateActiveConnections(connections)
            assertEquals(0, reg.activeConnections)

            reg.processRegistrationPacket(1, ngp, nowMs())
            assertEquals(1, reg.reg1TargetIdx)
            reg.regDriverPendingSends(connections.size, nowMs())
            assertEquals(1, reg.pendingReg2Idx())

            val fullId2 = reg.srtlaId.copyOf()
            fullId2.fill(0x7e, SRTLA_ID_LEN / 2)
            reg.processRegistrationPacket(1, createReg2Packet(fullId2), nowMs())
            reg.regDriverPendingSends(connections.size, nowMs())

            for ((idx, conn) in connections.withIndex()) {
                val now = nowMs()
                val pkt = createReg3Packet()
                val event = reg.processRegistrationPacket(idx, pkt, now)
                assertEquals(
                    RegistrationEvent.REG3,
                    event,
                    "uplink $idx must re-register after the receiver restart"
                )
                applyShellRegistrationEffects(event, conn, now)
            }
            assertTrue(connections.all { it.connected })
            assertEquals(0L, reg.outOfPhaseReg3)
            assertEquals(0L, reg.outOfPhaseRegErr)
        }

        test("fresh_link_not_timed_out") {
            val conn = createTestConnection()

            conn.connected = false
            conn.lastReceived = null
            conn.reconnection.connectionEstablishedMs = 0

            conn.reconnection.startupGraceDeadlineMs = nowMs() + STARTUP_GRACE_MS
            assertFalse(
                conn.isTimedOut(nowMs()),
                "fresh never-received link within grace must NOT be timed out"
            )

            conn.reconnection.startupGraceDeadlineMs = nowMs().saturating_sub(1)
            assertTrue(
                conn.isTimedOut(nowMs()),
                "a fresh link past its startup grace deadline must be timed out"
            )
        }

        test("reset_for_rehome_keeps_our_id_half_and_returns_to_pre_reg1") {
            val reg = RegistrationManager()
            val clientHalf = reg.srtlaId.slice(0 until SRTLA_ID_LEN / 2).toByteArray()

            reg.srtlaId.fill(0xab.toByte(), SRTLA_ID_LEN / 2)
            reg.setPendingReg2Idx(1)
            reg.setPendingTimeoutAtMs(nowMs() + 5000)
            reg.setReg1TargetIdx(1)
            reg.setReg1NextSendAtMs(nowMs() + 1000)
            reg.setBroadcastReg2Pending(true)
            reg.armReg3Gate(0)
            reg.hasConnected = true

            reg.resetForRehome()

            assertEquals(
                clientHalf.joinToString(),
                reg.srtlaId.slice(0 until SRTLA_ID_LEN / 2).toByteArray().joinToString(),
                "our half of the SRTLA id must survive the move"
            )
            assertNotEquals(
                ByteArray(SRTLA_ID_LEN / 2) { 0xab.toByte() }.joinToString(),
                reg.srtlaId.slice(SRTLA_ID_LEN / 2 until SRTLA_ID_LEN).toByteArray().joinToString(),
                "the old receiver's half must be discarded"
            )
            assertFalse(
                reg.srtlaId.slice(SRTLA_ID_LEN / 2 until SRTLA_ID_LEN).all { it == 0.toByte() },
                "and re-randomized, not zeroed, so the REG1 looks like a fresh sender's on the wire"
            )

            assertNull(reg.pendingReg2Idx())
            assertEquals(0L, reg.pendingTimeoutAtMs)
            assertNull(reg.reg1TargetIdx)
            assertEquals(0L, reg.reg1NextSendAtMs)
            assertFalse(reg.broadcastReg2Pending)
            assertFalse(reg.isAwaitingReg3(0))
            assertEquals(0, reg.activeConnections)
            assertFalse(reg.isProbing(), "probing is reset to NotStarted, not left mid-flight")
            assertTrue(
                reg.hasConnected,
                "has_connected records that this process has streamed before"
            )
        }
    }
}

// Kotlin extensions for unsigned arithmetic
private fun Long.saturating_sub(b: Long): Long = max(0, this - b)
