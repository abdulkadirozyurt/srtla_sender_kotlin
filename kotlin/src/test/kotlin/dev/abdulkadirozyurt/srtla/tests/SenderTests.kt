// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/tests/sender_tests.rs
//
// Tests for SequenceTracker, ClassicSelection, NAK attribution, connection changes.
// Tests requiring real network (create_connections_from_ips) are stubbed with inline loopback.
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.connection.*
import dev.abdulkadirozyurt.srtla.protocol.*
import dev.abdulkadirozyurt.srtla.sender.*
import dev.abdulkadirozyurt.srtla.sender.selection.*
import dev.abdulkadirozyurt.srtla.testkit.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

// ── Helper ────────────────────────────────────────────────────────────────────

private fun makeConn(inFlight: Int = 0, connected: Boolean = true): SrtlaConnection {
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
        label      = "test-${System.nanoTime()}",
    ).also {
        it.connected = connected
        it.lastReceivedMs = if (connected) System.currentTimeMillis() else 0L
        it.inFlightPackets = inFlight
    }
}

fun registerSenderTests() {

// ── test_select_connection_idx_classic ───────────────────────────────────────
suite("ClassicSelection") {
    test("picks highest score connection") {
        val conns = listOf(
            makeConn(inFlight = 5),  // lower score
            makeConn(inFlight = 0),  // highest score
            makeConn(inFlight = 10), // lowest score
        )
        val selected = ClassicSelection.select(conns, null, 0L, System.currentTimeMillis())
        assertEquals(1, selected)
    }

    test("all disconnected → null") {
        val conns = listOf(makeConn(connected = false), makeConn(connected = false))
        val selected = ClassicSelection.select(conns, null, 0L, System.currentTimeMillis())
        assertNull(selected)
    }

    test("classic mode ignores time dampening — always picks best") {
        val conns = listOf(
            makeConn(inFlight = 5),  // index 0: lower score
            makeConn(inFlight = 0),  // index 1: best score
        )
        val lastSwitchMs = System.currentTimeMillis()
        val currentMs = lastSwitchMs + 200L // well within any cooldown
        // Classic: must always pick 1 regardless of cooldown
        val selected = selectConnectionIdx(conns, Some = 0, lastSwitchMs, currentMs, classicMode = true)
        assertEquals(1, selected)
    }

    test("selectConnectionIdx classic: no last selected") {
        val conns = listOf(makeConn(inFlight = 3), makeConn(inFlight = 0))
        val sel = selectConnectionIdx(conns, null, 0L, System.currentTimeMillis(), classicMode = true)
        assertEquals(1, sel)
    }
}

// ── test_nak_attribution_to_correct_connection ────────────────────────────────
suite("NakAttribution") {
    test("NAK attributed to sending connection via packet_log") {
        val c0 = makeConn()
        val c1 = makeConn()
        val c2 = makeConn()
        val conns = listOf(c0, c1, c2)
        val now = System.currentTimeMillis()

        c0.registerPacket(100L, now)
        c1.registerPacket(200L, now)
        c2.registerPacket(300L, now)

        val init = intArrayOf(c0.congestion.nakCount, c1.congestion.nakCount, c2.congestion.nakCount)

        assertTrue(c0.handleNak(100L))
        assertEquals(init[0] + 1, c0.congestion.nakCount)
        assertEquals(init[1], c1.congestion.nakCount)
        assertEquals(init[2], c2.congestion.nakCount)

        assertTrue(c1.handleNak(200L))
        assertEquals(init[0] + 1, c0.congestion.nakCount)
        assertEquals(init[1] + 1, c1.congestion.nakCount)

        // Unknown sequence — not found in any log
        assertFalse(c0.handleNak(999L))
        assertFalse(c1.handleNak(999L))
        assertFalse(c2.handleNak(999L))
    }
}

// ── SequenceTracker tests (src/sender/sequence.rs::tests) ────────────────────
suite("SequenceTracker") {
    test("insert and get") {
        val tracker = SequenceTracker()
        val now = 1_000_000L
        tracker.insert(12345L, 1L, now)
        tracker.insert(12346L, 2L, now)
        assertEquals(1L, tracker.get(12345L, now))
        assertEquals(2L, tracker.get(12346L, now))
        assertNull(tracker.get(12347L, now))
    }

    test("expiration") {
        val tracker = SequenceTracker()
        val now = 1_000_000L
        tracker.insert(12345L, 1L, now)
        assertEquals(1L, tracker.get(12345L, now + SEQUENCE_TRACKING_MAX_AGE_MS))
        assertNull(tracker.get(12345L, now + SEQUENCE_TRACKING_MAX_AGE_MS + 1L))
    }

    test("collision handling") {
        val tracker = SequenceTracker()
        val now = 1_000_000L
        val seq1 = 100L
        val seq2 = seq1 + SEQ_TRACKING_SIZE.toLong()
        tracker.insert(seq1, 1L, now)
        assertEquals(1L, tracker.get(seq1, now))
        tracker.insert(seq2, 2L, now)
        assertEquals(2L, tracker.get(seq2, now))
        assertNull(tracker.get(seq1, now)) // overwritten
    }

    test("remove connection") {
        val tracker = SequenceTracker()
        val now = 1_000_000L
        tracker.insert(100L, 1L, now)
        tracker.insert(101L, 2L, now)
        tracker.insert(102L, 1L, now)
        tracker.removeConnection(1L)
        assertNull(tracker.get(100L, now))
        assertEquals(2L, tracker.get(101L, now))
        assertNull(tracker.get(102L, now))
    }

    test("size is power of two") {
        assertTrue((SEQ_TRACKING_SIZE and (SEQ_TRACKING_SIZE - 1)) == 0)
        assertTrue(SEQ_TRACKING_SIZE >= 1000)
    }

    test("ring buffer: old entries overwritten") {
        val tracker = SequenceTracker()
        val now = System.currentTimeMillis()
        for (i in 0 until (SEQ_TRACKING_SIZE + 100)) {
            tracker.insert(i.toLong(), 1L, now)
        }
        val recentSeq = (SEQ_TRACKING_SIZE + 50).toLong()
        assertNotNull(tracker.get(recentSeq, now))
        // seq 50 was overwritten by seq (SEQ_TRACKING_SIZE + 50)
        assertNull(tracker.get(50L, now))
    }
}

// ── test_constants ────────────────────────────────────────────────────────────
suite("SenderConstants") {
    test("SEQ_TRACKING_SIZE is valid") {
        assertTrue(SEQ_TRACKING_SIZE > 0)
        assertTrue(SEQ_TRACKING_SIZE >= 1000)
    }
    test("GLOBAL_TIMEOUT_MS is valid") {
        assertTrue(GLOBAL_TIMEOUT_MS > 0)
        assertTrue(GLOBAL_TIMEOUT_MS >= 5000L)
    }
}

// ── test_connection_selection_with_all_disconnected ───────────────────────────
suite("AllDisconnectedSelection") {
    test("returns null when all connections score -1") {
        val conns = listOf(makeConn(connected = false), makeConn(connected = false))
        val sel = selectConnectionIdx(conns, null, 0L, System.currentTimeMillis(), classicMode = true)
        assertNull(sel)
    }
}

// ── Classic mode always picks best regardless of timing ───────────────────────
suite("ClassicModeNoDampening") {
    test("classic always picks highest score even if currently selected has lower score") {
        val conns = listOf(makeConn(inFlight = 5), makeConn(inFlight = 0))
        val lastSwitchMs = System.currentTimeMillis()
        val currentMs = lastSwitchMs + 5L // would be inside any cooldown
        val sel = selectConnectionIdx(conns, Some = 0, lastSwitchMs, currentMs, classicMode = true)
        assertEquals(1, sel)
    }
}

} // registerSenderTests

// Kotlin doesn't have named arguments in the same position as Rust, but
// we use a small helper to keep call-sites readable:
private fun selectConnectionIdx(
    conns: List<SrtlaConnection>,
    Some: Int?,
    lastSwitchMs: Long,
    currentMs: Long,
    classicMode: Boolean,
) = dev.abdulkadirozyurt.srtla.sender.selection.selectConnectionIdx(
    conns, Some, lastSwitchMs, currentMs, classicMode
)
