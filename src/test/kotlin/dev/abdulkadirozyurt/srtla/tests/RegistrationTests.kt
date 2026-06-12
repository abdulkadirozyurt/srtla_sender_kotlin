// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/tests/registration_tests.rs
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.connection.*
import dev.abdulkadirozyurt.srtla.protocol.*
import dev.abdulkadirozyurt.srtla.registration.*
import dev.abdulkadirozyurt.srtla.testkit.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

// ── Test helper ───────────────────────────────────────────────────────────────

private fun makeConn(): SrtlaConnection {
    val ch = DatagramChannel.open()
    ch.configureBlocking(false)
    ch.socket().bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    val port = ch.socket().localPort
    ch.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
    return SrtlaConnection(
        connId     = System.nanoTime(),
        socket     = UplinkSocket(ch),
        remoteAddr = InetSocketAddress(InetAddress.getLoopbackAddress(), port),
        localIp    = InetAddress.getLoopbackAddress(),
        label      = "test",
    )
}

private fun makeMgr() = RegistrationManager()

fun registerRegistrationTests() {

// ── test_registration_manager_creation ───────────────────────────────────────
suite("RegistrationManagerCreation") {
    test("initial state") {
        val reg = makeMgr()
        assertEquals(0, reg.activeConnections)
        assertFalse(reg.hasConnected)
        assertFalse(reg.broadcastReg2Pending)
        assertNull(reg.pendingReg2Idx())
        assertNull(reg.reg1TargetIdx)
        assertFalse(reg.srtlaId.all { it == 0.toByte() })
    }
}

// ── test_reg_ngp_handling ─────────────────────────────────────────────────────
suite("RegNgpHandling") {
    test("REG_NGP sets reg1 target") {
        val reg = makeMgr()
        val buf = ByteArray(4).also {
            it[0] = (SRTLA_TYPE_REG_NGP ushr 8).toByte()
            it[1] = (SRTLA_TYPE_REG_NGP and 0xFF).toByte()
        }
        val event = reg.processRegistrationPacket(1, buf)
        assertNotNull(event)
        assertEquals(1, reg.reg1TargetIdx)
        val now = System.currentTimeMillis()
        assertTrue(reg.reg1NextSendAtMs <= now + 100L)
    }
}

// ── test_reg2_handling ────────────────────────────────────────────────────────
suite("Reg2Handling") {
    test("REG2 updates ID and sets broadcast pending") {
        val reg = makeMgr()
        reg.setPendingReg2Idx(0)
        val originalId = reg.srtlaId.copyOf()
        val modifiedId = originalId.copyOf().also { id ->
            for (i in SRTLA_ID_LEN / 2 until SRTLA_ID_LEN) id[i] = 0xAB.toByte()
        }
        val buf = createReg2Packet(modifiedId)
        val event = reg.processRegistrationPacket(0, buf)
        assertNotNull(event)
        assertTrue(reg.srtlaId.contentEquals(modifiedId))
        assertNull(reg.pendingReg2Idx())
        assertTrue(reg.broadcastReg2Pending)
        assertNull(reg.reg1TargetIdx)
    }
}

// ── test_reg3_handling ────────────────────────────────────────────────────────
suite("Reg3Handling") {
    test("REG3 sets hasConnected") {
        val reg = makeMgr()
        assertFalse(reg.hasConnected)
        val buf = byteArrayOf((SRTLA_TYPE_REG3 ushr 8).toByte(), (SRTLA_TYPE_REG3 and 0xFF).toByte())
        val event = reg.processRegistrationPacket(2, buf)
        assertNotNull(event)
        assertTrue(reg.hasConnected)
    }
}

// ── test_reg_err_handling ─────────────────────────────────────────────────────
suite("RegErrHandling") {
    test("REG_ERR clears pending state") {
        val reg = makeMgr()
        reg.setPendingReg2Idx(1)
        reg.testSetPendingTimeoutAtMs(System.currentTimeMillis() + 5000L)
        reg.testSetReg1TargetIdx(1)
        val buf = ByteArray(4).also {
            it[0] = (SRTLA_TYPE_REG_ERR ushr 8).toByte()
            it[1] = (SRTLA_TYPE_REG_ERR and 0xFF).toByte()
        }
        val before = System.currentTimeMillis()
        val event = reg.processRegistrationPacket(1, buf)
        assertNotNull(event)
        assertNull(reg.pendingReg2Idx())
        assertEquals(0L, reg.pendingTimeoutAtMs)
        assertNull(reg.reg1TargetIdx)
        // 'before' is captured BEFORE processing: on Windows the ~16ms clock
        // granularity made an after-captured timestamp overshoot the deadline.
        assertTrue(reg.reg1NextSendAtMs >= before + REG2_TIMEOUT * 1000L)
    }
}

// ── test_unrecognized_packet ──────────────────────────────────────────────────
suite("UnrecognizedPacket") {
    test("non-registration packet returns null") {
        val reg = makeMgr()
        val buf = ByteArray(4).also {
            it[0] = (SRT_TYPE_ACK ushr 8).toByte()
            it[1] = (SRT_TYPE_ACK and 0xFF).toByte()
        }
        val event = reg.processRegistrationPacket(0, buf)
        assertNull(event)
    }
}

// ── test_reg_driver_initial_reg1 ─────────────────────────────────────────────
suite("RegDriverInitialReg1") {
    test("driver sends REG1 when NGP sets target") {
        val reg = makeMgr()
        val conns = listOf(makeConn())
        val ngp = ByteArray(2).also {
            it[0] = (SRTLA_TYPE_REG_NGP ushr 8).toByte()
            it[1] = (SRTLA_TYPE_REG_NGP and 0xFF).toByte()
        }
        reg.processRegistrationPacket(0, ngp)
        reg.regDriverSendIfNeeded(conns)
        assertEquals(0, reg.pendingReg2Idx())
        assertTrue(reg.pendingTimeoutAtMs > System.currentTimeMillis())
    }
}

// ── test_reg_driver_with_target ───────────────────────────────────────────────
suite("RegDriverWithTarget") {
    test("driver sends to specified target") {
        val reg = makeMgr()
        val conns = listOf(makeConn(), makeConn())
        reg.testSetReg1TargetIdx(1)
        reg.regDriverSendIfNeeded(conns)
        assertEquals(1, reg.pendingReg2Idx())
    }
}

// ── test_reg_driver_waits_for_ngp ────────────────────────────────────────────
suite("RegDriverWaitsForNgp") {
    test("no REG1 sent without NGP") {
        val reg = makeMgr()
        val conns = listOf(makeConn())
        reg.regDriverSendIfNeeded(conns)
        assertNull(reg.pendingReg2Idx())
        val ngp = ByteArray(2).also {
            it[0] = (SRTLA_TYPE_REG_NGP ushr 8).toByte()
            it[1] = (SRTLA_TYPE_REG_NGP and 0xFF).toByte()
        }
        reg.processRegistrationPacket(0, ngp)
        reg.regDriverSendIfNeeded(conns)
        assertEquals(0, reg.pendingReg2Idx())
    }
}

// ── test_broadcast_reg2 ───────────────────────────────────────────────────────
suite("BroadcastReg2") {
    test("broadcast flag is cleared after sending") {
        val reg = makeMgr()
        val conns = listOf(makeConn(), makeConn())
        reg.testSetBroadcastReg2Pending(true)
        reg.regDriverSendIfNeeded(conns)
        assertFalse(reg.broadcastReg2Pending)
    }
}

// ── test_send_reg1_to_sets_pending_state ─────────────────────────────────────
suite("SendReg1ToSetsState") {
    test("sendReg1To sets correct pending state") {
        val reg = makeMgr()
        val conn = makeConn()
        reg.sendReg1To(0, conn)
        assertEquals(0, reg.pendingReg2Idx())
        assertEquals(0, reg.reg1TargetIdx)
        val now = System.currentTimeMillis()
        assertTrue(reg.pendingTimeoutAtMs >= now)
        assertTrue(reg.reg1NextSendAtMs >= now)
        assertTrue(reg.reg1NextSendAtMs <= reg.pendingTimeoutAtMs)
    }
}

// ── test_clear_pending_if_timed_out ──────────────────────────────────────────
suite("ClearPendingIfTimedOut") {
    test("clears pending when timeout exceeded") {
        val reg = makeMgr()
        val start = System.currentTimeMillis()
        reg.setPendingReg2Idx(0)
        reg.testSetPendingTimeoutAtMs(start + 10L)
        reg.testSetReg1TargetIdx(0)
        reg.testSetReg1NextSendAtMs(start + 1000L)
        val clearedTime = start + 20L
        val cleared = reg.clearPendingIfTimedOut(clearedTime)
        assertEquals(0, cleared)
        assertNull(reg.pendingReg2Idx())
        assertEquals(0L, reg.pendingTimeoutAtMs)
        assertNull(reg.reg1TargetIdx)
        assertEquals(clearedTime, reg.reg1NextSendAtMs)
    }
}

// ── test_id_generation_uniqueness ────────────────────────────────────────────
suite("IdGenerationUniqueness") {
    test("multiple managers have unique IDs") {
        val ids = (0 until 8).map { RegistrationManager().srtlaId }
        val allSame = ids.zipWithNext().all { (a, b) -> a.contentEquals(b) }
        assertFalse(allSame)
    }
}

// ── test_reg_driver_timing ────────────────────────────────────────────────────
suite("RegDriverTiming") {
    test("no REG1 sent before reg1NextSendAtMs") {
        val reg = makeMgr()
        reg.testSetReg1NextSendAtMs(System.currentTimeMillis() + 5000L)
        val conns = listOf(makeConn())
        reg.regDriverSendIfNeeded(conns)
        assertNull(reg.pendingReg2Idx())
    }
}

// ── test_registration_state_transitions ──────────────────────────────────────
suite("RegistrationStateTransitions") {
    test("full REG_NGP → REG2 → REG3 flow") {
        val reg = makeMgr()
        val conns = listOf(makeConn())
        assertEquals(0, reg.activeConnections)
        assertFalse(reg.hasConnected)

        // NGP → target selected
        val ngp = byteArrayOf(0x92.toByte(), 0x11.toByte(), 0x00, 0x00)
        reg.processRegistrationPacket(0, ngp)
        assertEquals(0, reg.reg1TargetIdx)

        // Send REG1
        reg.setPendingReg2Idx(0)

        // REG2 response
        val modifiedId = reg.srtlaId.copyOf().also { for (i in SRTLA_ID_LEN/2 until SRTLA_ID_LEN) it[i] = 0xFF.toByte() }
        val reg2Pkt = createReg2Packet(modifiedId)
        reg.processRegistrationPacket(0, reg2Pkt)
        assertTrue(reg.broadcastReg2Pending)
        assertNull(reg.pendingReg2Idx())

        // REG3
        val reg3 = byteArrayOf(0x92.toByte(), 0x02.toByte())
        reg.processRegistrationPacket(0, reg3)
        assertTrue(reg.hasConnected)

        conns[0].connected = true
        reg.updateActiveConnections(conns)
        assertEquals(1, reg.activeConnections)
    }
}

// ── test_multiple_reg3_connections ────────────────────────────────────────────
suite("MultipleReg3Connections") {
    test("3 connections all send REG3") {
        val reg = makeMgr()
        val reg3 = byteArrayOf(0x92.toByte(), 0x02.toByte())
        val conns = listOf(makeConn(), makeConn(), makeConn())
        for (i in 0 until 3) {
            val e = reg.processRegistrationPacket(i, reg3)
            assertNotNull(e)
        }
        assertTrue(reg.hasConnected)
        for (c in conns) c.connected = true
        reg.updateActiveConnections(conns)
        assertEquals(3, reg.activeConnections)
    }
}

// ── Probing tests (src/registration/probing.rs) ───────────────────────────────
suite("Probing") {
    test("check_probing_complete selects lowest RTT") {
        val reg = makeMgr()
        reg.setProbeStateWaiting()
        reg.simulateProbeResult(0, 150L)
        reg.simulateProbeResult(1, 50L)
        reg.simulateProbeResult(2, 200L)
        val completed = reg.checkProbingComplete()
        assertTrue(completed)
        assertEquals(1, reg.reg1TargetIdx)
        assertFalse(reg.isProbing())
    }

    test("probing timeout uses best available") {
        val reg = makeMgr()
        reg.setProbeStateWaiting()
        reg.simulateProbeResult(0, 100L)
        Thread.sleep(2100L)
        val completed = reg.checkProbingComplete()
        assertTrue(completed)
        assertEquals(0, reg.reg1TargetIdx)
    }

    test("no probe responses: fallback to 0") {
        val reg = makeMgr()
        reg.setProbeStateWaiting()
        Thread.sleep(2100L)
        val completed = reg.checkProbingComplete()
        assertTrue(completed)
        assertEquals(0, reg.reg1TargetIdx)
    }

    test("REG_NGP during probing is handled as probe response") {
        val reg = makeMgr()
        reg.setProbeStateWaiting()
        reg.simulateProbeResult(0, 0L)
        val ngp = byteArrayOf(0x92.toByte(), 0x11.toByte(), 0x00, 0x00)
        Thread.sleep(50L)
        reg.processRegistrationPacket(0, ngp)
        assertTrue(reg.isProbing())
    }

    test("probe results count") {
        val reg = makeMgr()
        reg.setProbeStateWaiting()
        reg.simulateProbeResult(0, 100L)
        reg.simulateProbeResult(1, 200L)
        assertEquals(2, reg.probeResultsCount())
    }

    test("startProbing skipped when active connections > 0") {
        val reg = makeMgr()
        val conns = listOf(makeConn())
        conns[0].connected = true
        conns[0].lastReceivedMs = System.currentTimeMillis()
        reg.updateActiveConnections(conns)
        val initialTarget = reg.reg1TargetIdx
        reg.startProbing(conns)
        assertEquals(initialTarget, reg.reg1TargetIdx)
        assertFalse(reg.isProbing())
    }
}

} // registerRegistrationTests
