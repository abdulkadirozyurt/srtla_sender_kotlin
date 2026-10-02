// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/client_dedup.rs, src/tests/client_forward_dedup_tests.rs,
//         src/tests/recovery_limbo_tests.rs, src/sender/sequence.rs,
//         src/sender/reload.rs, src/sender/connections.rs, src/sender/packet_handler.rs
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.core.satAdd
import dev.abdulkadirozyurt.srtla.sender.*
import dev.abdulkadirozyurt.srtla.testkit.*
import dev.abdulkadirozyurt.srtla.protocol.SRT_TYPE_ACK
import dev.abdulkadirozyurt.srtla.protocol.SRT_TYPE_NAK
import dev.abdulkadirozyurt.srtla.protocol.SRT_CONTROL_HEADER_LEN
import dev.abdulkadirozyurt.srtla.protocol.createKeepalivePacket
import dev.abdulkadirozyurt.srtla.registration.RegistrationManager
import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.net.UplinkSocket
import dev.abdulkadirozyurt.srtla.net.SourceIpBinder
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.Inet6Address
import java.nio.channels.DatagramChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

// ─ Client forward dedup test utilities ─────────────────────────────────────

/**
 * A full SRT ACK: ACK number in the header, last acknowledged sequence as
 * the first CIF word.
 */
private fun fullAck(ackNumber: Int, seq: Int): ByteArray {
    val pkt = ByteArray(44)
    pkt[0] = (SRT_TYPE_ACK shr 8).toByte()
    pkt[1] = SRT_TYPE_ACK.toByte()
    pkt[4] = (ackNumber shr 24).toByte()
    pkt[5] = (ackNumber shr 16).toByte()
    pkt[6] = (ackNumber shr 8).toByte()
    pkt[7] = ackNumber.toByte()
    pkt[16] = (seq shr 24).toByte()
    pkt[17] = (seq shr 16).toByte()
    pkt[18] = (seq shr 8).toByte()
    pkt[19] = seq.toByte()
    return pkt
}

private fun nak(lost: Int): ByteArray {
    val pkt = ByteArray(SRT_CONTROL_HEADER_LEN + 4)
    pkt[0] = (SRT_TYPE_NAK shr 8).toByte()
    pkt[1] = SRT_TYPE_NAK.toByte()
    pkt[SRT_CONTROL_HEADER_LEN] = (lost shr 24).toByte()
    pkt[SRT_CONTROL_HEADER_LEN + 1] = (lost shr 16).toByte()
    pkt[SRT_CONTROL_HEADER_LEN + 2] = (lost shr 8).toByte()
    pkt[SRT_CONTROL_HEADER_LEN + 3] = lost.toByte()
    return pkt
}

private class Bond(val conns: MutableList<SrtlaConnection>, val now: Long = nowMs()) {
    val reg: RegistrationManager = RegistrationManager()
    val clientSink: RecordingClientSink = RecordingClientSink()
    val dedup: ClientDedup = ClientDedup()
    val seqTracker: SequenceTracker = SequenceTracker()

    fun receiveOn(linkIdx: Int, data: ByteArray) {
        val conn = conns[linkIdx]
        val incoming = processUplinkPacket(
            conn, linkIdx, reg, clientSink, TEST_CLIENT_ADDR, dedup, data, data.size, now
        )
        // Also process connection events to handle NAK forwarding
        processConnectionEvents(
            linkIdx, conns, TEST_CLIENT_ADDR, clientSink, seqTracker, false, incoming, now
        )
    }

    fun delivered(): List<ByteArray> = clientSink.sent.map { it.first }

    fun assertDelivered(vararg expected: ByteArray, message: String? = null) {
        val actual = delivered()
        assertEquals(expected.size, actual.size, "${message ?: ""} - wrong number of packets: expected ${expected.size}, got ${actual.size}")
        for (i in expected.indices) {
            assertContentEquals(expected[i], actual[i], "${message ?: ""} - packet $i mismatch")
        }
    }
}

// ─ Reload test utilities ─────────────────────────────────────────────────────

private fun parseApply(text: String): Triple<List<InetAddress>, List<Int>, Int?> {
    return when (val r = analyzeIpReloadText(text)) {
        is IpReload.Apply -> Triple(r.ips, r.weights, r.firstInvalidLine)
        is IpReload.Refuse -> throw AssertionError("expected Apply, got ${r.reason}")
    }
}

private fun ipv4(a: Int, b: Int, c: Int, d: Int): InetAddress {
    return InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
}

private fun parseIp(s: String): InetAddress = InetAddress.getByName(s)

// ─ Connections test utilities ────────────────────────────────────────────────

private fun addr(last: Int): InetSocketAddress {
    val ip = byteArrayOf(203.toByte(), 0.toByte(), 113.toByte(), last.toByte())
    return InetSocketAddress(InetAddress.getByAddress(ip), 5000)
}

// ─ Register all test suites ──────────────────────────────────────────────────

fun registerShellTests() {
    // Client forward dedup tests (from Rust client_forward_dedup_tests.rs)
    suite("ClientForwardDedup") {
        test("one_ack_copy_reaches_the_client_once") {
            val now = nowMs()
            val bond = Bond(createTestConnections(1, now))
            val ack = fullAck(1, 100)
            bond.receiveOn(0, ack)
            bond.assertDelivered(ack, message = "one ack copy should reach client once")
        }

        test("an_ack_on_three_links_reaches_the_client_once") {
            val now = nowMs()
            val bond = Bond(createTestConnections(3, now))
            val ack = fullAck(1, 100)
            for (link in 0..2) {
                bond.receiveOn(link, ack)
            }
            bond.assertDelivered(ack, message = "ack on 3 links should reach client once")
        }

        test("a_new_ack_is_forwarded") {
            val now = nowMs()
            val bond = Bond(createTestConnections(3, now))
            val first = fullAck(1, 100)
            val second = fullAck(2, 100)
            for (link in 0..2) {
                bond.receiveOn(link, first)
            }
            for (link in 0..2) {
                bond.receiveOn(link, second)
            }
            bond.assertDelivered(first, second, message = "both acks should be forwarded")
        }

        test("a_nak_on_three_links_reaches_the_client_once") {
            val now = nowMs()
            val bond = Bond(createTestConnections(3, now))
            val nakPkt = nak(500)
            for (link in 0..2) {
                bond.receiveOn(link, nakPkt)
            }
            bond.assertDelivered(nakPkt, message = "nak on 3 links should reach client once")
        }

        test("every_copy_still_reaches_link_accounting") {
            val now = nowMs()
            val conns = createTestConnections(3, now)
            val reg = RegistrationManager()
            val clientSink = RecordingClientSink()
            val dedup = ClientDedup()
            val ack = fullAck(1, 100)
            val nakPkt = nak(500)

            for ((link, conn) in conns.withIndex()) {
                conn.lastReceived = null
                for (pkt in listOf(ack, nakPkt)) {
                    processUplinkPacket(conn, link, reg, clientSink, TEST_CLIENT_ADDR, dedup, pkt, pkt.size, now)
                    assertNotNull(conn.lastReceived, "link $link: a deduplicated copy still proves the link is alive")
                }
            }
        }
    }

    // Recovery limbo tests (from Rust recovery_limbo_tests.rs)
    suite("RecoveryLimbo") {
        test("receiver_traffic_does_not_rescue_a_recovering_link") {
            val now = nowMs()
            val conns = createTestConnections(1, now)
            val conn = conns[0]
            conn.connected = true
            conn.reconnection.connectionEstablishedMs = 1
            conn.lastReceived = now
            conn.markForRecovery()

            assertTrue(!conn.connected, "Recovery must clear connected")
            assertTrue(conn.isTimedOut(now), "Recovery must time the link out")

            val ack = fullAck(1, 100)
            val reg = RegistrationManager()
            val clientSink = RecordingClientSink()
            val dedup = ClientDedup()

            processUplinkPacket(conn, 0, reg, clientSink, TEST_CLIENT_ADDR, dedup, ack, ack.size, now)

            assertTrue(conn.isTimedOut(now), "an SRT ACK on a link awaiting REG3 must not take it off the reconnect path")
        }

        test("keepalive_reply_does_not_rescue_a_recovering_link") {
            val now = nowMs()
            val conns = createTestConnections(1, now)
            val conn = conns[0]
            conn.connected = true
            conn.reconnection.connectionEstablishedMs = 1
            conn.lastReceived = now
            conn.markForRecovery()

            assertTrue(!conn.connected, "Recovery must clear connected")
            assertTrue(conn.isTimedOut(now), "Recovery must time the link out")

            val keepalive = createKeepalivePacket(now)
            val reg = RegistrationManager()
            val clientSink = RecordingClientSink()
            val dedup = ClientDedup()

            processUplinkPacket(conn, 0, reg, clientSink, TEST_CLIENT_ADDR, dedup, keepalive, keepalive.size, now)

            assertTrue(conn.isTimedOut(now), "keepalive reply must not rescue a recovering link")
        }

        test("traffic_still_refreshes_a_connected_link") {
            val now = nowMs()
            val conns = createTestConnections(1, now)
            val conn = conns[0]
            conn.connected = true
            conn.reconnection.connectionEstablishedMs = 1
            conn.lastReceived = now.satSub(1500)

            val ack = fullAck(1, 100)
            val reg = RegistrationManager()
            val clientSink = RecordingClientSink()
            val dedup = ClientDedup()

            processUplinkPacket(conn, 0, reg, clientSink, TEST_CLIENT_ADDR, dedup, ack, ack.size, now)

            assertNotNull(conn.lastReceived, "lastReceived should be set")
            assertTrue(conn.lastReceived!! >= now.satSub(10), "traffic should refresh a connected link")
        }
    }

    // SequenceTracker tests
    suite("SequenceTracker") {
        test("test_insert_and_get") {
            val tracker = SequenceTracker()
            val now = 1_000_000L

            tracker.insert(12345, 1, now)
            tracker.insert(12346, 2, now)

            assertEquals(1L, tracker.get(12345, now))
            assertEquals(2L, tracker.get(12346, now))
            assertNull(tracker.get(12347, now), "Not inserted")
        }

        test("test_expiration") {
            val tracker = SequenceTracker()
            val now = 1_000_000L

            tracker.insert(12345, 1, now)

            assertEquals(
                1L,
                tracker.get(12345, now + SEQUENCE_TRACKING_MAX_AGE_MS),
                "Still valid"
            )
            assertNull(
                tracker.get(12345, now + SEQUENCE_TRACKING_MAX_AGE_MS + 1),
                "Expired"
            )
        }

        test("test_collision_handling") {
            val tracker = SequenceTracker()
            val now = 1_000_000L

            val seq1 = 100
            val seq2 = seq1 + SEQ_TRACKING_SIZE

            tracker.insert(seq1, 1, now)
            assertEquals(1L, tracker.get(seq1, now))

            tracker.insert(seq2, 2, now)
            assertEquals(2L, tracker.get(seq2, now))
            assertNull(tracker.get(seq1, now), "Collision, seq doesn't match")
        }

        test("test_remove_connection") {
            val tracker = SequenceTracker()
            val now = 1_000_000L

            tracker.insert(100, 1, now)
            tracker.insert(101, 2, now)
            tracker.insert(102, 1, now)

            tracker.removeConnection(1)

            assertNull(tracker.get(100, now))
            assertEquals(2L, tracker.get(101, now))
            assertNull(tracker.get(102, now))
        }

        test("test_size_is_power_of_two") {
            assertTrue(
                SEQ_TRACKING_SIZE and (SEQ_TRACKING_SIZE - 1) == 0,
                "SEQ_TRACKING_SIZE must be power of two"
            )
        }
    }

    // Reload tests
    suite("Reload") {
        test("all_valid_applies_without_invalid_line") {
            val (ips, weights, bad) = parseApply("10.0.0.1\n10.0.0.2\n")
            assertEquals(listOf(parseIp("10.0.0.1"), parseIp("10.0.0.2")), ips)
            assertNull(bad)
        }

        test("blank_lines_are_skipped_not_counted_as_invalid") {
            val (ips, weights, bad) = parseApply("\n10.0.0.1\n   \n10.0.0.2\n\n")
            assertEquals(listOf(parseIp("10.0.0.1"), parseIp("10.0.0.2")), ips)
            assertNull(bad)
        }

        test("mixed_valid_and_invalid_applies_and_reports_first_invalid_line") {
            val (ips, weights, bad) = parseApply("10.0.0.1\nnot-an-ip\n10.0.0.2\nalso-bad\n")
            assertEquals(listOf(parseIp("10.0.0.1"), parseIp("10.0.0.2")), ips)
            assertEquals(2, bad)
        }

        test("all_garbage_refuses_with_first_invalid_line") {
            val result = analyzeIpReloadText("garbage\nstill-not-an-ip\n")
            assertTrue(result is IpReload.Refuse)
            val refusal = (result as IpReload.Refuse).reason
            assertTrue(refusal is ReloadRefusal.NoValidIps)
            assertEquals(1, (refusal as ReloadRefusal.NoValidIps).firstInvalidLine)
        }

        test("garbage_after_blanks_reports_correct_line_number") {
            val result = analyzeIpReloadText("\n\n###garbage###\n")
            assertTrue(result is IpReload.Refuse)
            val refusal = (result as IpReload.Refuse).reason
            assertTrue(refusal is ReloadRefusal.NoValidIps)
            assertEquals(3, (refusal as ReloadRefusal.NoValidIps).firstInvalidLine)
        }

        test("empty_file_refuses_as_empty") {
            val result = analyzeIpReloadText("")
            assertTrue(result is IpReload.Refuse)
            assertEquals(ReloadRefusal.Empty, (result as IpReload.Refuse).reason)
        }

        test("only_blank_lines_refuses_as_empty") {
            val result = analyzeIpReloadText("\n   \n\t\n")
            assertTrue(result is IpReload.Refuse)
            assertEquals(ReloadRefusal.Empty, (result as IpReload.Refuse).reason)
        }

        test("missing_file_refuses_as_not_found") {
            val result = analyzeIpReload("/nonexistent/srtla-reload-guard-test.txt")
            assertTrue(result is IpReload.Refuse)
            assertEquals(ReloadRefusal.NotFound, (result as IpReload.Refuse).reason)
        }

        test("reads_and_parses_a_real_file") {
            val tempFile = Files.createTempFile("srtla-test", ".txt")
            try {
                Files.write(tempFile, "127.0.0.1\n127.0.0.2\n".toByteArray())
                val result = analyzeIpReload(tempFile.toString())
                assertTrue(result is IpReload.Apply)
                val apply = result as IpReload.Apply
                assertEquals(listOf(parseIp("127.0.0.1"), parseIp("127.0.0.2")), apply.ips)
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }

        test("unweighted_file_gets_weight_one_everywhere") {
            val (ips, weights, bad) = parseApply("10.0.0.1\n10.0.0.2\n")
            assertEquals(listOf(parseIp("10.0.0.1"), parseIp("10.0.0.2")), ips)
            assertEquals(listOf(1, 1), weights)
            assertNull(bad)
        }

        test("weight_column_is_parsed_and_missing_is_one") {
            val (ips, weights, bad) = parseApply("192.168.0.15 10\n192.168.0.2\n")
            assertEquals(listOf(parseIp("192.168.0.15"), parseIp("192.168.0.2")), ips)
            assertEquals(listOf(10, 1), weights)
            assertNull(bad)
        }

        test("weights_are_normalised_so_the_lowest_is_one") {
            val (_, weights1, _) = parseApply("10.0.0.1 10\n10.0.0.2 5\n10.0.0.3 7\n")
            assertEquals(listOf(6, 1, 3), weights1)
            val (_, weights2, _) = parseApply("10.0.0.1 5\n10.0.0.2 5\n")
            assertEquals(listOf(1, 1), weights2)
            val (_, weights3, _) = parseApply("10.0.0.1 9\n")
            assertEquals(listOf(1), weights3)
        }

        test("out_of_range_and_bad_weights_keep_the_ip") {
            val (ips, weights, bad) = parseApply("10.0.0.1 42\n10.0.0.2 0\n10.0.0.3 -3\n10.0.0.4 x\n")
            assertEquals(4, ips.size)
            assertEquals(listOf(10, 1, 1, 1), weights)
            assertNull(bad)
        }

        test("reload_guard_counts_weighted_lines_as_valid") {
            val (ips, weights, _) = parseApply("10.0.0.1 0\n")
            assertEquals(listOf(parseIp("10.0.0.1")), ips)
            assertEquals(listOf(1), weights)
            val result = analyzeIpReloadText("not-an-ip 10\n10.0.0.1 10 extra\n")
            assertTrue(result is IpReload.Refuse)
            val refusal = (result as IpReload.Refuse).reason
            assertTrue(refusal is ReloadRefusal.NoValidIps)
        }

        test("reads_weights_from_a_real_file") {
            val tempFile = Files.createTempFile("srtla-test-weights", ".txt")
            try {
                Files.write(tempFile, "127.0.0.1 10\n127.0.0.2\n".toByteArray())
                val result = analyzeIpReload(tempFile.toString())
                assertTrue(result is IpReload.Apply)
                val apply = result as IpReload.Apply
                assertEquals(2, apply.ips.size)
                assertEquals(listOf(10, 1), apply.weights)
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }
    }

    // Connections tests
    suite("Connections") {
        test("dns_drift_needs_a_fresh_answer_that_excludes_the_cached_address") {
            assertFalse(
                dnsDriftDetected(addr(1), listOf(addr(1))),
                "The receiver still answers with the address we are pinned to"
            )
            assertFalse(
                dnsDriftDetected(addr(1), listOf(addr(2), addr(1))),
                "Multi-A record: ours is still one of them"
            )
            assertTrue(
                dnsDriftDetected(addr(1), listOf(addr(2))),
                "Our address is gone from the answer — that is drift"
            )
            assertFalse(
                dnsDriftDetected(addr(1), emptyList()),
                "An empty answer told us nothing; it must not be read as drift"
            )
        }

        test("dns_drift_warning_is_rate_limited_across_the_process") {
            val base = lastDnsDriftWarnMsForTest().satAdd(60_000L * 10)

            assertTrue(claimDnsDriftWarning(base), "first drift must warn")
            assertFalse(
                claimDnsDriftWarning(base + 60_000L - 1L),
                "a second uplink reconnecting inside the window must stay quiet"
            )
            assertTrue(
                claimDnsDriftWarning(base + 60_000L),
                "the warning is due again once the window has passed"
            )
        }
    }

    // PacketHandler tests
    suite("PacketHandler") {
        test("periodic_flush_failure_recovers_the_link") {
            // An IPv4 channel aimed at an IPv6 peer: every send fails immediately,
            // the JVM stand-in for upstream's unsendable_conn_io().
            val ch = DatagramChannel.open(StandardProtocolFamily.INET)
            ch.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            ch.configureBlocking(false)
            val remote = InetSocketAddress(Inet6Address.getByName("::1"), 9)
            val conn = createTestConnection()
            val state = SenderState(
                connections = mutableListOf(conn),
                connIo = ConnIoMap().apply { put(conn.connId, ConnIo(UplinkSocket(ch, remote), SourceIpBinder, remote)) },
            )

            val now = nowMs()
            conn.queueDataPacket(ByteArray(1316), 4242, now)
            state.seqTracker.insert(4242, conn.connId, now)
            assertTrue(conn.hasQueuedPackets())
            assertTrue(conn.connected)

            flushAllBatches(state, now)

            assertEquals(0, conn.inFlightPackets, "a failed flush must not leave the batch registered as in-flight")
            assertFalse(conn.connected, "a failed flush must put the link into recovery")
            assertNull(conn.lastSent, "nothing reached the socket, so the link must not claim a send")
            assertNull(state.seqTracker.get(4242, now), "recovery must drop the sequence ownership of the recovered link")
            ch.close()
        }

        test("successful_flush_registers_the_batch_and_stamps_last_sent") {
            val conn = createTestConnection()
            val connections = mutableListOf(conn)
            val state = SenderState(connections = connections, connIo = createTestConnIoMap(connections))

            val now = nowMs()
            conn.queueDataPacket(ByteArray(1316), 7, now)
            state.seqTracker.insert(7, conn.connId, now)

            flushAllBatches(state, now)

            assertEquals(1, conn.inFlightPackets, "a confirmed flush registers the batch as in-flight")
            assertTrue(conn.connected, "a confirmed flush leaves the link alone")
            assertNotNull(conn.lastSent, "a confirmed flush stamps last_sent")
            assertEquals(conn.connId, state.seqTracker.get(7, now))
            for (io in state.connIo.values) io.socket.channel.close()
        }
    }
}
