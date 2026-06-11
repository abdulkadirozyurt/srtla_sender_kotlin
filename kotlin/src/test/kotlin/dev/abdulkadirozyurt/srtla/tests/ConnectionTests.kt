// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/tests/connection_tests.rs
//
// Unit tests for SrtlaConnection, CongestionControl, RttTracker, BitrateTracker.
// No network required — connection objects created with a loopback UDP socket pair.
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.connection.*
import dev.abdulkadirozyurt.srtla.connection.congestion.*
import dev.abdulkadirozyurt.srtla.protocol.*
import dev.abdulkadirozyurt.srtla.testkit.*
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

// ── Test helper: create an in-memory SrtlaConnection with loopback socket ────

private fun makeTestConnection(): SrtlaConnection {
    // Bind a DatagramChannel to loopback; connect to itself for simplicity
    val ch = DatagramChannel.open()
    ch.configureBlocking(false)
    ch.socket().bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    val localPort = ch.socket().localPort
    ch.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), localPort))
    val socket = UplinkSocket(ch)
    val connId = System.nanoTime()
    val conn = SrtlaConnection(
        connId    = connId,
        socket    = socket,
        remoteAddr = InetSocketAddress(InetAddress.getLoopbackAddress(), localPort),
        localIp   = InetAddress.getLoopbackAddress(),
        label     = "test-conn",
    )
    conn.connected = true
    conn.lastReceivedMs = System.currentTimeMillis()
    conn.reconnection.connectionEstablishedMs = System.currentTimeMillis()
    return conn
}

fun registerConnectionTests() {

// ── test_connection_score ─────────────────────────────────────────────────────
suite("ConnectionScore") {
    test("initial score = WINDOW_DEF * WINDOW_MULT") {
        val conn = makeTestConnection()
        assertEquals(WINDOW_DEF * WINDOW_MULT, conn.getScore())
    }

    test("score with in-flight packets") {
        val conn = makeTestConnection()
        conn.inFlightPackets = 5
        val expected = (WINDOW_DEF * WINDOW_MULT) / (5 + 1)
        assertEquals(expected, conn.getScore())
    }

    test("disconnected connection returns -1") {
        val conn = makeTestConnection()
        conn.connected = false
        assertEquals(-1, conn.getScore())
    }
}

// ── test_packet_tracking ──────────────────────────────────────────────────────
suite("PacketTracking") {
    test("register packet increases in-flight count") {
        val conn = makeTestConnection()
        val initial = conn.inFlightPackets
        val now = System.currentTimeMillis()
        conn.registerPacket(100L, now)
        assertEquals(initial + 1, conn.inFlightPackets)
        assertTrue(conn.packetLog.containsKey(100L))
    }

    test("multiple packet registrations") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        for (i in 1..5) conn.registerPacket((100 + i).toLong(), now)
        assertEquals(5, conn.inFlightPackets)
    }
}

// ── test_srt_ack_handling ─────────────────────────────────────────────────────
suite("SrtAckHandling") {
    test("ACK clears packets up to ack sequence") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        for (i in 1..5) conn.registerPacket((i * 10).toLong(), now)
        assertEquals(5, conn.inFlightPackets)
        conn.handleSrtAck(30L)
        // Packets 10, 20, 30 should be removed; 40 and 50 remain
        assertTrue(conn.inFlightPackets < 5)
    }

    test("duplicate ACK does not change state") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        conn.registerPacket(100L, now)
        conn.handleSrtAck(100L)
        val inflight = conn.inFlightPackets
        conn.handleSrtAck(100L) // duplicate
        assertEquals(inflight, conn.inFlightPackets)
    }
}

// ── test_nak_handling ─────────────────────────────────────────────────────────
suite("NakHandling") {
    test("NAK reduces window") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        val initialWindow = conn.window
        conn.registerPacket(100L, now)
        conn.handleNak(100L)
        assertEquals(1, conn.congestion.nakCount)
        assertTrue(conn.window < initialWindow)
    }

    test("NAK burst detection") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        for (i in 100..109) conn.registerPacket(i.toLong(), now)
        conn.handleNak(100L)
        assertEquals(0, conn.congestion.nakBurstCount) // first NAK, no burst yet
        // second NAK quickly after → burst
        conn.congestion.lastNakTimeMs = System.currentTimeMillis()
        conn.handleNak(101L)
        assertEquals(2, conn.congestion.nakBurstCount)
        conn.handleNak(102L)
        assertEquals(3, conn.congestion.nakBurstCount)
    }

    test("NAK not found does not affect stats") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        conn.registerPacket(100L, now)
        val initialNak = conn.congestion.nakCount
        val initialWindow = conn.window
        val found = conn.handleNak(999L) // not in log
        assertFalse(found)
        assertEquals(initialNak, conn.congestion.nakCount)
        assertEquals(initialWindow, conn.window)
    }

    test("NAK with logged packet affects stats") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        conn.registerPacket(100L, now)
        val initialWindow = conn.window
        conn.handleNak(100L)
        assertTrue(conn.window < initialWindow)
        assertEquals(1, conn.congestion.nakCount)
    }

    test("fast recovery mode activates at low window") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        conn.window = 1500
        conn.registerPacket(100L, now)
        conn.handleNak(100L)
        assertTrue(conn.congestion.fastRecoveryMode)
    }

    test("NAK burst resets after 1s gap") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        for (i in 100..109) conn.registerPacket(i.toLong(), now)
        conn.handleNak(100L)
        conn.handleNak(101L)
        conn.handleNak(102L)
        assertEquals(3, conn.congestion.nakBurstCount)
        // simulate 1.1s later
        conn.congestion.lastNakTimeMs = System.currentTimeMillis() - 1100L
        conn.handleNak(103L)
        assertEquals(0, conn.congestion.nakBurstCount)
    }

    test("NAK burst reset on reconnect") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        for (i in 100..104) conn.registerPacket(i.toLong(), now)
        conn.handleNak(100L)
        conn.handleNak(101L)
        conn.handleNak(102L)
        assertEquals(3, conn.congestion.nakBurstCount)
        conn.congestion.reset()
        assertEquals(0, conn.congestion.nakBurstCount)
        assertEquals(0L, conn.congestion.nakBurstStartTimeMs)
        assertEquals(0, conn.congestion.nakCount)
    }
}

// ── test_srtla_ack_handling ───────────────────────────────────────────────────
suite("SrtlaAckHandling") {
    test("specific SRTLA ACK removes packet and may increase window") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        for (i in 1..3) conn.registerPacket((i * 100).toLong(), now)
        assertEquals(3, conn.inFlightPackets)

        val found = conn.handleSrtlaAckSpecific(200L, true)
        assertTrue(found)
        assertEquals(2, conn.inFlightPackets)

        val notFound = conn.handleSrtlaAckSpecific(999L, true)
        assertFalse(notFound)
        assertEquals(2, conn.inFlightPackets)
    }

    test("global SRTLA ACK +1 window for connected with lastReceived") {
        val conn = makeTestConnection()
        conn.lastReceivedMs = System.currentTimeMillis()
        val initial = conn.window
        conn.handleSrtlaAckGlobal()
        assertEquals(initial + 1, conn.window)
    }

    test("global SRTLA ACK no-op when not connected") {
        val conn = makeTestConnection()
        conn.connected = false
        val initial = conn.window
        conn.handleSrtlaAckGlobal()
        assertEquals(initial, conn.window)
    }

    test("classic mode: window increases only when in_flight*MULT > window") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        conn.window = 1500
        conn.registerPacket(100L, now)
        conn.registerPacket(200L, now)
        conn.registerPacket(300L, now) // 3 in-flight, 3*1000=3000 > 1500
        val before = conn.window
        conn.handleSrtlaAckSpecific(100L, true) // classic
        // after ACK: in_flight=2, check 2*1000=2000 > before (1500): true → increase
        assertTrue(conn.window >= before)
    }

    test("classic mode: no increase when in_flight*MULT <= window") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        conn.window = 5000
        conn.registerPacket(100L, now)
        conn.registerPacket(200L, now)
        conn.registerPacket(300L, now)
        // 3 in-flight: after ACK → 2, check 2*1000=2000 > 5000? No → no increase
        val before = conn.window
        conn.handleSrtlaAckSpecific(100L, true)
        assertEquals(before, conn.window) // no increase
    }
}

// ── test_keepalive_needs ──────────────────────────────────────────────────────
suite("KeepaliveNeeds") {
    test("needs keepalive initially (no previous sent)") {
        val conn = makeTestConnection()
        assertTrue(conn.needsKeepalive())
    }

    test("no keepalive needed immediately after sending") {
        val conn = makeTestConnection()
        conn.lastKeepaliveSentMs = System.currentTimeMillis()
        assertFalse(conn.needsKeepalive())
    }

    test("needs keepalive after IDLE_TIME seconds") {
        val conn = makeTestConnection()
        conn.lastKeepaliveSentMs = System.currentTimeMillis() - (IDLE_TIME + 1L) * 1000L
        assertTrue(conn.needsKeepalive())
    }
}

// ── test_window_recovery ──────────────────────────────────────────────────────
suite("WindowRecovery") {
    test("time-based recovery increases window after NAKs") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        conn.registerPacket(100L, now)
        repeat(5) { conn.handleNak(100L) }
        val reduced = conn.window
        // simulate 3s since last NAK, 2.5s since last increase
        conn.congestion.lastNakTimeMs = System.currentTimeMillis() - 3000L
        conn.congestion.lastWindowIncreaseMs = System.currentTimeMillis() - 2500L
        conn.performWindowRecovery()
        assertTrue(conn.window > reduced)
    }

    test("progressive recovery rates") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        conn.registerPacket(100L, now)
        conn.handleNak(100L)
        val reduced = conn.window

        // 3s since last NAK → 25% rate (WINDOW_INCR / 4)
        conn.congestion.lastNakTimeMs = System.currentTimeMillis() - 3000L
        conn.congestion.lastWindowIncreaseMs = System.currentTimeMillis() - 2500L
        val before1 = conn.window
        conn.performWindowRecovery()
        val delta1 = conn.window - before1
        assertEquals(WINDOW_INCR / 4, delta1)

        // 6s since last NAK → 50% rate
        conn.window = reduced
        conn.congestion.lastNakTimeMs = System.currentTimeMillis() - 6000L
        conn.congestion.lastWindowIncreaseMs = System.currentTimeMillis() - 2500L
        val before2 = conn.window
        conn.performWindowRecovery()
        val delta2 = conn.window - before2
        assertEquals(WINDOW_INCR / 2, delta2)

        // 8s since last NAK → 100% rate
        conn.window = reduced
        conn.congestion.lastNakTimeMs = System.currentTimeMillis() - 8000L
        conn.congestion.lastWindowIncreaseMs = System.currentTimeMillis() - 2500L
        val before3 = conn.window
        conn.performWindowRecovery()
        val delta3 = conn.window - before3
        assertEquals(WINDOW_INCR, delta3)

        // 11s since last NAK → 200% rate
        conn.window = reduced
        conn.congestion.lastNakTimeMs = System.currentTimeMillis() - 11000L
        conn.congestion.lastWindowIncreaseMs = System.currentTimeMillis() - 2500L
        val before4 = conn.window
        conn.performWindowRecovery()
        val delta4 = conn.window - before4
        assertEquals(WINDOW_INCR * 2, delta4)

        assertTrue(delta1 < delta2)
        assertTrue(delta2 < delta3)
        assertTrue(delta3 < delta4)
    }

    test("recovery with no NAK history (treated as perfect)") {
        val conn = makeTestConnection()
        conn.window = 5000
        conn.congestion.lastNakTimeMs = 0L // never had NAK
        conn.congestion.lastWindowIncreaseMs = 0L
        val before = conn.window
        conn.performWindowRecovery()
        assertTrue(conn.window > before)
        assertEquals(5000 + WINDOW_INCR * 2, conn.window)
    }

    test("recovery respects timing constraint") {
        val conn = makeTestConnection()
        conn.window = 5000
        conn.congestion.lastNakTimeMs = System.currentTimeMillis() - 8000L
        conn.congestion.lastWindowIncreaseMs = System.currentTimeMillis() // just increased
        val before = conn.window
        conn.performWindowRecovery()
        assertEquals(before, conn.window) // no change — timing not met
    }

    test("fast recovery mode: 25% rate doubles") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        conn.window = 1500
        conn.registerPacket(100L, now)
        conn.handleNak(100L)
        assertTrue(conn.congestion.fastRecoveryMode)
        val reduced = conn.window
        conn.congestion.lastNakTimeMs = System.currentTimeMillis() - 3000L
        conn.congestion.lastWindowIncreaseMs = System.currentTimeMillis() - 600L
        val before = conn.window
        conn.performWindowRecovery()
        val delta = conn.window - before
        assertEquals((WINDOW_INCR * 2) / 4, delta)
    }
}

// ── test_reconnect_logic ──────────────────────────────────────────────────────
suite("ReconnectLogic") {
    test("should allow first reconnect attempt") {
        val conn = makeTestConnection()
        assertTrue(conn.shouldAttemptReconnect())
    }

    test("record attempt increments failure count") {
        val conn = makeTestConnection()
        conn.recordReconnectAttempt()
        assertEquals(1, conn.reconnection.reconnectFailureCount)
    }

    test("no immediate retry after attempt") {
        val conn = makeTestConnection()
        conn.recordReconnectAttempt()
        assertFalse(conn.shouldAttemptReconnect())
    }

    test("mark success resets backoff") {
        val conn = makeTestConnection()
        conn.recordReconnectAttempt()
        conn.markReconnectSuccess()
        assertEquals(0, conn.reconnection.reconnectFailureCount)
    }
}

// ── test_timeout_detection ────────────────────────────────────────────────────
suite("TimeoutDetection") {
    test("fresh connection is not timed out") {
        val conn = makeTestConnection()
        assertFalse(conn.isTimedOut())
    }

    test("old lastReceivedMs triggers timeout") {
        val conn = makeTestConnection()
        conn.lastReceivedMs = System.currentTimeMillis() - (CONN_TIMEOUT + 1L) * 1000L
        assertTrue(conn.isTimedOut())
    }

    test("disconnected connection with no lastReceived is timed out") {
        val conn = makeTestConnection()
        conn.connected = false
        conn.lastReceivedMs = 0L
        assertTrue(conn.isTimedOut())
    }
}

// ── test_connection_state_management ─────────────────────────────────────────
suite("ConnectionStateManagement") {
    test("mark_for_recovery resets state") {
        val conn = makeTestConnection()
        conn.inFlightPackets = 5
        conn.markForRecovery()
        assertFalse(conn.connected)
        assertEquals(WINDOW_DEF * WINDOW_MULT, conn.window)
        assertEquals(0, conn.inFlightPackets)
        assertEquals(-1, conn.getScore())
        assertTrue(conn.isTimedOut())
    }

    test("clear_pre_registration_state clears log and congestion") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        conn.registerPacket(100L, now)
        conn.registerPacket(200L, now)
        conn.congestion.nakCount = 3
        conn.clearPreRegistrationState()
        assertEquals(0, conn.packetLog.size)
        assertEquals(0, conn.inFlightPackets)
        assertEquals(0, conn.congestion.nakCount)
    }
}

// ── test_nak_statistics ───────────────────────────────────────────────────────
suite("NakStatistics") {
    test("initial state has no NAKs") {
        val conn = makeTestConnection()
        assertEquals(0, conn.congestion.nakCount)
        assertEquals(0, conn.congestion.nakBurstCount)
        assertNull(conn.timeSinceLastNakMs())
    }

    test("after NAK: count=1, time is recent") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        conn.registerPacket(100L, now)
        conn.handleNak(100L)
        assertEquals(1, conn.congestion.nakCount)
        val t = conn.timeSinceLastNakMs()
        assertNotNull(t)
        assertTrue(t!! < 1000L)
    }
}

// ── test_packet_log_capacity ──────────────────────────────────────────────────
suite("PacketLogCapacity") {
    test("HashMap stores all packets beyond PKT_LOG_SIZE") {
        val conn = makeTestConnection()
        val now = System.currentTimeMillis()
        for (i in 0 until (PKT_LOG_SIZE + 10)) conn.registerPacket(i.toLong(), now)
        assertEquals(PKT_LOG_SIZE + 10, conn.packetLog.size)
        assertEquals(PKT_LOG_SIZE + 10, conn.inFlightPackets)
        conn.handleSrtAck((PKT_LOG_SIZE + 5).toLong())
        assertTrue(conn.inFlightPackets < PKT_LOG_SIZE + 10)
    }
}

} // registerConnectionTests
