// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/tests/handshake_latency_tests.rs, src/tests/keepalive_interop_tests.rs,
//         tests/srtla_wire_conformance.rs, tests/parser_proptest.rs
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.connection.RttTracker
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.protocol.ConnectionInfo
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_ID_LEN
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_KEEPALIVE_EXT_LEN
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_ACK
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_KEEPALIVE
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG1
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG1_LEN
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG2
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG2_LEN
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG3
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG3_LEN
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG_ERR
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG_NAK
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG_NGP
import dev.abdulkadirozyurt.srtla.protocol.SRT_CONTROL_HEADER_LEN
import dev.abdulkadirozyurt.srtla.protocol.SRT_HANDSHAKE_CIF_LEN
import dev.abdulkadirozyurt.srtla.protocol.SRT_HS_EXT_CMD_HSREQ
import dev.abdulkadirozyurt.srtla.protocol.SRT_HS_EXT_CMD_HSRSP
import dev.abdulkadirozyurt.srtla.protocol.SRT_HS_EXT_FLAG_HSREQ
import dev.abdulkadirozyurt.srtla.protocol.SRT_HS_EXT_HSREQ_WORDS
import dev.abdulkadirozyurt.srtla.protocol.SRT_HS_OPT_TSBPDRCV
import dev.abdulkadirozyurt.srtla.protocol.SRT_HS_OPT_TSBPDSND
import dev.abdulkadirozyurt.srtla.protocol.SRT_HS_REQTYPE_CONCLUSION
import dev.abdulkadirozyurt.srtla.protocol.SRT_HS_VERSION_5
import dev.abdulkadirozyurt.srtla.protocol.SRT_TYPE_ACK
import dev.abdulkadirozyurt.srtla.protocol.SRT_TYPE_HANDSHAKE
import dev.abdulkadirozyurt.srtla.protocol.SRT_TYPE_NAK
import dev.abdulkadirozyurt.srtla.protocol.createAckPacket
import dev.abdulkadirozyurt.srtla.protocol.createKeepalivePacket
import dev.abdulkadirozyurt.srtla.protocol.createKeepalivePacketExt
import dev.abdulkadirozyurt.srtla.protocol.createReg1Packet
import dev.abdulkadirozyurt.srtla.protocol.createReg2Packet
import dev.abdulkadirozyurt.srtla.protocol.extractKeepaliveConnInfo
import dev.abdulkadirozyurt.srtla.protocol.extractKeepaliveTimestamp
import dev.abdulkadirozyurt.srtla.protocol.getPacketType
import dev.abdulkadirozyurt.srtla.protocol.parseSrtAck
import dev.abdulkadirozyurt.srtla.protocol.parseSrtHandshakeLatency
import dev.abdulkadirozyurt.srtla.protocol.parseSrtNak
import dev.abdulkadirozyurt.srtla.protocol.parseSrtlaAck
import dev.abdulkadirozyurt.srtla.protocol.readI32BE
import dev.abdulkadirozyurt.srtla.protocol.readU32BE
import dev.abdulkadirozyurt.srtla.protocol.writeI32BE
import dev.abdulkadirozyurt.srtla.protocol.writeU16BE
import dev.abdulkadirozyurt.srtla.protocol.writeU32BE
import dev.abdulkadirozyurt.srtla.protocol.writeU64BE
import dev.abdulkadirozyurt.srtla.sender.ClientDedup
import dev.abdulkadirozyurt.srtla.sender.processUplinkPacket
import dev.abdulkadirozyurt.srtla.testkit.assertEquals
import dev.abdulkadirozyurt.srtla.testkit.assertContentEquals
import dev.abdulkadirozyurt.srtla.testkit.assertFalse
import dev.abdulkadirozyurt.srtla.testkit.assertTrue
import dev.abdulkadirozyurt.srtla.testkit.suite
import java.util.Random

// Fixed virtual clock for keepalive tests.
private const val T0: Long = 1_000_000L

private const val BOTH_TSBPD: Int = SRT_HS_OPT_TSBPDSND or SRT_HS_OPT_TSBPDRCV
private const val RECV_ACK_INT: Int = 10
private const val ACK_PKT_LEN: Int = 4 + 4 * RECV_ACK_INT
private const val MAX_INPUT: Int = 256

/**
 * An HSv5 conclusion handshake carrying one HSREQ or HSRSP block.
 */
private fun conclusionWith(cmd: Int, rcvMs: Int, sndMs: Int): ByteArray {
    val pkt = ByteArray(SRT_CONTROL_HEADER_LEN + SRT_HANDSHAKE_CIF_LEN + 16)
    writeU16BE(pkt, 0, SRT_TYPE_HANDSHAKE)

    val cif = IntArray(SRT_HANDSHAKE_CIF_LEN / 4)
    cif[0] = SRT_HS_VERSION_5
    cif[1] = SRT_HS_EXT_FLAG_HSREQ
    cif[5] = SRT_HS_REQTYPE_CONCLUSION
    for ((i, word) in cif.withIndex()) {
        writeI32BE(pkt, 16 + i * 4, word)
    }

    val bodyLen = 3
    writeU32BE(pkt, 16 + SRT_HANDSHAKE_CIF_LEN, ((cmd.toLong() shl 16) or bodyLen.toLong()))
    writeU32BE(pkt, 16 + SRT_HANDSHAKE_CIF_LEN + 4, 0x0001_0500L)
    writeU32BE(pkt, 16 + SRT_HANDSHAKE_CIF_LEN + 8, BOTH_TSBPD.toLong())
    writeU32BE(pkt, 16 + SRT_HANDSHAKE_CIF_LEN + 12, (((rcvMs.toLong()) shl 16) or (sndMs.toLong() and 0xFFFFL)))

    return pkt
}

/**
 * Drive one datagram through the real uplink receive path and report the
 * latency it surfaced plus how many packets it relayed downstream.
 */
private fun receive(data: ByteArray): Pair<Int?, Int> {
    val conns = createTestConnections(1)
    val reg = dev.abdulkadirozyurt.srtla.registration.RegistrationManager()
    val sink = RecordingClientSink()

    val incoming = processUplinkPacket(
        conns[0],
        0,
        reg,
        sink,
        TEST_CLIENT_ADDR,
        ClientDedup(),
        data,
        data.size,
        nowMs()
    )

    return incoming.negotiatedLatencyMs to incoming.forwardToClient.size
}

/**
 * Random data generator for proptest-style fuzz tests.
 */
private fun randomBytes(size: Int, seed: Long): ByteArray {
    val rng = Random(seed)
    val buf = ByteArray(size)
    rng.nextBytes(buf)
    return buf
}

/**
 * Generate random ConnectionInfo for roundtrip tests.
 */
private fun randomConnInfo(seed: Long): ConnectionInfo {
    val rng = Random(seed)
    return ConnectionInfo(
        connId = rng.nextLong() and 0xFFFFFFFFL,
        window = rng.nextInt(),
        inFlight = rng.nextInt(),
        rttMs = rng.nextLong() and 0xFFFFFFFFL,
        nakCount = rng.nextLong() and 0xFFFFFFFFL,
        bitrateBytesSec = rng.nextLong() and 0xFFFFFFFFL,
    )
}

/**
 * SRT NAK header for fuzz tests.
 */
private fun nakHeader(): ByteArray {
    val buf = ByteArray(SRT_CONTROL_HEADER_LEN)
    writeU16BE(buf, 0, SRT_TYPE_NAK)
    return buf
}

fun registerWireConformanceTests() {
    suite("handshake_latency_tests") {
        test("an_hsrsp_surfaces_the_peers_receive_buffer") {
            val (latency, forwarded) = receive(conclusionWith(SRT_HS_EXT_CMD_HSRSP, 4000, 120))
            assertEquals(latency, 4000)
            assertEquals(forwarded, 1, "sniffing must not consume the handshake — it is still the client's")
        }

        test("an_hsreq_is_ignored_but_still_relayed") {
            // An HSREQ arriving from upstream is a proposal, not the negotiated
            // result: only the responder has resolved both sides to max(own,
            // proposed). Adopting it would let the far end's *ask* set our budget.
            val (latency, forwarded) = receive(conclusionWith(SRT_HS_EXT_CMD_HSREQ, 4000, 120))
            assertEquals(latency, null)
            assertEquals(forwarded, 1)
        }

        test("a_malformed_handshake_is_relayed_untouched") {
            // Truncated mid-block. The client's SRT stack is the authority on
            // whether this is usable; our sniff must neither panic nor drop it.
            val full = conclusionWith(SRT_HS_EXT_CMD_HSRSP, 4000, 120)
            val (latency, forwarded) = receive(full.copyOf(full.size - 6))
            assertEquals(latency, null)
            assertEquals(forwarded, 1)
        }

        test("the_budget_is_stored_once_and_reported_only_on_change") {
            // The handshake crosses once per session, but a re-handshake on
            // different terms must move the budget rather than being ignored.
            val config = DynamicConfig()
            assertEquals(config.snapshot().negotiatedLatencyMs, 0, "unknown at start")

            assertTrue(config.setNegotiatedLatencyMs(4000))
            assertEquals(config.snapshot().negotiatedLatencyMs, 4000)

            assertFalse(config.setNegotiatedLatencyMs(4000), "a repeat of the same value is not a change")

            assertTrue(config.setNegotiatedLatencyMs(2000), "a re-handshake moves it")
            assertEquals(config.snapshot().negotiatedLatencyMs, 2000)
        }
    }

    suite("keepalive_interop_tests") {
        test("keepalive_extended_round_trip") {
            val info = ConnectionInfo(
                connId = 7L,
                window = 31_000,
                inFlight = 12,
                rttMs = 87L,
                nakCount = 4L,
                bitrateBytesSec = 3_125_000L,
            )

            val pkt = createKeepalivePacketExt(info, nowMs())
            assertEquals(pkt.size, SRTLA_KEEPALIVE_EXT_LEN)
            assertEquals(getPacketType(pkt), SRTLA_TYPE_KEEPALIVE)

            // Telemetry round-trip: every ConnectionInfo field preserved.
            val parsed = extractKeepaliveConnInfo(pkt)
            assertEquals(parsed, info, "ConnectionInfo must round-trip byte-for-byte")
            assertEquals(parsed?.rttMs, 87L, "rtt_ms field preserved across the wire")

            // RTT measurement round-trip: craft an extended keepalive whose
            // timestamp is a known interval in the past, echo it back through the
            // real receive path, and confirm a plausible RTT sample is recovered
            // from bytes 2-9 despite the extended trailer.
            val tracker = RttTracker()
            tracker.recordKeepaliveSent(T0)
            assertTrue(tracker.waitingForKeepaliveResponse)

            val sentTs = T0 - 50
            val echo = createKeepalivePacketExt(info, nowMs()).copyOf(SRTLA_KEEPALIVE_EXT_LEN)
            writeU64BE(echo, 2, sentTs)

            val measured = tracker.handleKeepaliveResponse(echo, echo.size, "interop", T0)
            assertTrue(measured != null, "extended keepalive echo yields an RTT sample")
            val rtt = measured ?: 0L
            assertTrue(
                rtt in 40L..10_000L,
                "measured RTT ${rtt}ms should reflect the ~50ms backdated timestamp"
            )
            assertTrue(
                tracker.kalmanRtt.isInitialized,
                "a valid extended-keepalive RTT sample must seed the filter"
            )
            assertFalse(
                tracker.waitingForKeepaliveResponse,
                "the keepalive-wait flag must clear after a valid echo"
            )
        }

        test("keepalive_bare_2byte_accepted") {
            val bare = ByteArray(2)
            writeU16BE(bare, 0, SRTLA_TYPE_KEEPALIVE)

            // Recognised as a keepalive by the discriminator…
            assertEquals(getPacketType(bare), SRTLA_TYPE_KEEPALIVE)

            // …but too short to carry a timestamp or extended telemetry: both
            // return None, gracefully (no panic, no unwrap).
            assertEquals(extractKeepaliveTimestamp(bare), null)
            assertEquals(extractKeepaliveConnInfo(bare), null)

            // The real receive path tolerates the bare echo: no RTT sample, no
            // panic, and the waiting flag is cleared so the next keepalive cycle
            // is not wedged.
            val tracker = RttTracker()
            tracker.recordKeepaliveSent(T0)
            val measured = tracker.handleKeepaliveResponse(bare, bare.size, "interop-bare", T0)
            assertEquals(measured, null, "a bare 2-byte echo yields no RTT sample")
            assertFalse(
                tracker.kalmanRtt.isInitialized,
                "a bare echo must not seed the RTT filter"
            )
            assertFalse(
                tracker.waitingForKeepaliveResponse,
                "the keepalive-wait flag must clear after handling a bare echo"
            )
        }

        test("keepalive_truncated_graceful") {
            val tracker = RttTracker()

            for (len in 0..64) {
                val buf = ByteArray(len)
                if (len >= 2) {
                    writeU16BE(buf, 0, SRTLA_TYPE_KEEPALIVE)
                }

                // None of these may panic at any length.
                getPacketType(buf)
                extractKeepaliveTimestamp(buf)
                extractKeepaliveConnInfo(buf)

                // The receive path must never panic on a malformed echo. Re-arm
                // before each call so the guard branch is actually exercised.
                tracker.recordKeepaliveSent(T0)
                tracker.handleKeepaliveResponse(buf, buf.size, "interop-trunc", T0)

                // Length-specific contract: a timestamp needs >= 10 bytes; the
                // extended telemetry needs the full 38-byte frame (magic+version).
                if (len < 10) {
                    assertEquals(extractKeepaliveTimestamp(buf), null)
                }
                if (len < SRTLA_KEEPALIVE_EXT_LEN) {
                    assertEquals(extractKeepaliveConnInfo(buf), null)
                }
            }

            // Oversized frame (well beyond the 38-byte extended keepalive): the
            // trailing bytes are ignored, the standard timestamp still reads, and
            // nothing panics. With no 0xC01F magic at bytes 10-11 it is NOT parsed
            // as extended telemetry.
            val oversized = ByteArray(512)
            writeU16BE(oversized, 0, SRTLA_TYPE_KEEPALIVE)
            val ts = T0 - 20
            writeU64BE(oversized, 2, ts)
            assertEquals(getPacketType(oversized), SRTLA_TYPE_KEEPALIVE)
            assertTrue(extractKeepaliveTimestamp(oversized) != null)
            assertEquals(
                extractKeepaliveConnInfo(oversized), null,
                "oversized frame without the 0xC01F magic must not parse as extended"
            )
        }
    }

    suite("srtla_wire_conformance") {
        test("type_codes_match_common_h") {
            assertEquals(SRTLA_TYPE_KEEPALIVE, 0x9000, "KEEPALIVE type code drift")
            assertEquals(SRTLA_TYPE_ACK, 0x9100, "ACK type code drift")
            assertEquals(SRTLA_TYPE_REG1, 0x9200, "REG1 type code drift")
            assertEquals(SRTLA_TYPE_REG2, 0x9201, "REG2 type code drift")
            assertEquals(SRTLA_TYPE_REG3, 0x9202, "REG3 type code drift")
            assertEquals(SRTLA_TYPE_REG_ERR, 0x9210, "REG_ERR type code drift")
            assertEquals(SRTLA_TYPE_REG_NGP, 0x9211, "REG_NGP type code drift")
            assertEquals(SRTLA_TYPE_REG_NAK, 0x9212, "REG_NAK type code drift")
        }

        test("type_codes_serialize_big_endian_on_the_wire") {
            // The receiver sends headers via htobe16(type); our builders use
            // to_be_bytes(). Pin the resulting on-wire byte pairs so a host-endian
            // regression (little-endian leak) is caught.
            assertContentEquals(SRTLA_TYPE_KEEPALIVE.toShort().toBytes(), byteArrayOf(0x90.toByte(), 0x00.toByte()))
            assertContentEquals(SRTLA_TYPE_ACK.toShort().toBytes(), byteArrayOf(0x91.toByte(), 0x00.toByte()))
            assertContentEquals(SRTLA_TYPE_REG1.toShort().toBytes(), byteArrayOf(0x92.toByte(), 0x00.toByte()))
            assertContentEquals(SRTLA_TYPE_REG2.toShort().toBytes(), byteArrayOf(0x92.toByte(), 0x01.toByte()))
            assertContentEquals(SRTLA_TYPE_REG3.toShort().toBytes(), byteArrayOf(0x92.toByte(), 0x02.toByte()))
            assertContentEquals(SRTLA_TYPE_REG_ERR.toShort().toBytes(), byteArrayOf(0x92.toByte(), 0x10.toByte()))
            assertContentEquals(SRTLA_TYPE_REG_NGP.toShort().toBytes(), byteArrayOf(0x92.toByte(), 0x11.toByte()))
            assertContentEquals(SRTLA_TYPE_REG_NAK.toShort().toBytes(), byteArrayOf(0x92.toByte(), 0x12.toByte()))
        }

        test("srtla_id_len_is_256") {
            assertEquals(SRTLA_ID_LEN, 256, "SRTLA_ID_LEN drift from common.h")
        }

        test("reg1_reg2_frame_is_258_bytes") {
            // common.h: SRTLA_TYPE_REG1_LEN = (2 + SRTLA_ID_LEN) = 258.
            assertEquals(SRTLA_TYPE_REG1_LEN, 258, "REG1 frame length drift")
            assertEquals(SRTLA_TYPE_REG2_LEN, 258, "REG2 frame length drift")
            assertEquals(SRTLA_TYPE_REG1_LEN, 2 + SRTLA_ID_LEN)
            assertEquals(SRTLA_TYPE_REG2_LEN, 2 + SRTLA_ID_LEN)
        }

        test("reg3_frame_is_2_bytes") {
            // common.h: SRTLA_TYPE_REG3_LEN = 2 (bare type, no body).
            assertEquals(SRTLA_TYPE_REG3_LEN, 2, "REG3 frame length drift")
        }

        test("ack_layout_is_44_bytes_type_plus_ten_acks") {
            // srtla_rec.c: struct { uint32_t type; uint32_t acks[10]; }.
            assertEquals(RECV_ACK_INT, 10)
            assertEquals(
                ACK_PKT_LEN, 44,
                "ACK struct = 4 (type) + 40 (10x u32 acks) = 44"
            )
        }

        test("reg1_builder_matches_wire_layout") {
            // Distinct per-byte id so a misplaced copy is visible.
            val id = ByteArray(SRTLA_ID_LEN)
            for (i in id.indices) {
                id[i] = (i and 0xff).toByte()
            }
            val pkt = createReg1Packet(id)

            assertEquals(pkt.size, 258, "REG1 frame must be 258 bytes")
            // Header: htobe16(SRTLA_TYPE_REG1) at bytes 0-1.
            assertContentEquals(pkt.copyOfRange(0, 2), byteArrayOf(0x92.toByte(), 0x00.toByte()), "REG1 header bytes")
            // Body: full 256-byte id at bytes 2..258.
            assertContentEquals(pkt.copyOfRange(2, 258), id, "REG1 id body must be the id verbatim")
        }

        test("reg2_builder_matches_wire_layout") {
            val id = ByteArray(SRTLA_ID_LEN)
            for (i in id.indices) {
                id[i] = (255 - (i and 0xff)).toByte()
            }
            val pkt = createReg2Packet(id)

            assertEquals(pkt.size, 258, "REG2 frame must be 258 bytes")
            // Header: htobe16(SRTLA_TYPE_REG2) at bytes 0-1.
            assertContentEquals(pkt.copyOfRange(0, 2), byteArrayOf(0x92.toByte(), 0x01.toByte()), "REG2 header bytes")
            assertContentEquals(pkt.copyOfRange(2, 258), id, "REG2 id body must be the id verbatim")
        }

        test("ack_builder_matches_wire_layout") {
            // ack.type = htobe32(SRTLA_TYPE_ACK << 16) = 0x9100_0000
            // => on-wire bytes [0x91, 0x00, 0x00, 0x00]; then 10 big-endian acks.
            val acks = intArrayOf(
                0x0000_0001,
                0x0000_00ff.toInt(),
                0x0000_abcd.toInt(),
                0x1234_5678.toInt(),
                0x7fff_ffff.toInt(),
                0x0000_0000,
                0xdead_beef.toInt(),
                0x0010_0000,
                0x00ff_ff00.toInt(),
                0xcafe_babe.toInt(),
            )
            val pkt = createAckPacket(acks)

            assertEquals(
                pkt.size,
                ACK_PKT_LEN,
                "ACK frame must be exactly 44 bytes for 10 acks"
            )

            // Type field (4 bytes): high u16 = 0x9100, low u16 = 0x0000.
            assertContentEquals(
                pkt.copyOfRange(0, 4),
                byteArrayOf(0x91.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()),
                "ACK type word must be htobe32(0x9100 << 16)"
            )

            // Each ack at offset 4 + i*4, big-endian, in order.
            for ((i, ack) in acks.withIndex()) {
                val off = 4 + i * 4
                val expected = ByteArray(4)
                writeI32BE(expected, 0, ack)
                assertContentEquals(
                    pkt.copyOfRange(off, off + 4),
                    expected,
                    "ACK seq #$i must be big-endian at offset $off"
                )
            }
        }

        test("parser_reads_wire_shaped_ack") {
            // Construct the frame EXACTLY as srtla_rec.c emits it (independent of our
            // own builder), then assert our parser recovers all 10 sequence numbers.
            val seqs = intArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 0x7fff_ffff.toInt())

            val frame = ByteArray(ACK_PKT_LEN)
            // ack.type = htobe32(SRTLA_TYPE_ACK << 16)
            writeU32BE(frame, 0, (SRTLA_TYPE_ACK.toLong() shl 16))
            // ack.acks[i] = htobe32(sn)
            for ((i, sn) in seqs.withIndex()) {
                val off = 4 + i * 4
                writeI32BE(frame, off, sn)
            }

            val parsed = parseSrtlaAck(frame)
            assertContentEquals(parsed, seqs, "parser must recover all 10 ACK seqs in order")
        }

        test("ack_builder_parser_roundtrip") {
            // Our own builder -> our own parser must round-trip the full 10-ack vector,
            // confirming both ends agree on the 44-byte layout.
            val acks = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0xffff_fffe.toInt())
            val pkt = createAckPacket(acks)
            val parsed = parseSrtlaAck(pkt)
            assertContentEquals(parsed, acks, "ACK build->parse round-trip")
        }
    }

    suite("parser_proptest") {
        test("parse_srt_nak_never_panics_and_is_bounded") {
            // 2000 random test cases with arbitrary bytes
            for (seed in 0L until 2000L) {
                val buf = randomBytes(MAX_INPUT, seed)
                val out = parseSrtNak(buf)
                assertTrue(out.size <= buf.size / 4 + 1000)
            }
        }

        test("parse_srt_nak_typed_never_panics_and_is_bounded") {
            // 2000 random test cases biased toward real NAK frames
            for (seed in 0L until 2000L) {
                val payload = randomBytes(MAX_INPUT, seed)
                val buf = nakHeader()
                val fullBuf = buf + payload
                val out = parseSrtNak(fullBuf)
                assertTrue(out.size <= fullBuf.size / 4 + 1000)
            }
        }

        test("parse_srtla_ack_never_panics_and_is_bounded") {
            // 2000 random test cases
            for (seed in 0L until 2000L) {
                val buf = randomBytes(MAX_INPUT, seed)
                val out = parseSrtlaAck(buf)
                assertTrue(out.size <= buf.size / 4)
            }
        }

        test("type_detection_never_panics") {
            // 2000 random test cases
            for (seed in 0L until 2000L) {
                val buf = randomBytes(MAX_INPUT, seed)
                getPacketType(buf)
                parseSrtAck(buf)
                extractKeepaliveTimestamp(buf)
                extractKeepaliveConnInfo(buf)

                // getPacketType agrees with the leading 2 bytes whenever present.
                if (buf.size >= 2) {
                    val expected = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                    assertEquals(getPacketType(buf), expected)
                } else {
                    assertEquals(getPacketType(buf), null)
                }
            }
        }

        test("keepalive_ext_roundtrips") {
            // 2000 random ConnectionInfo roundtrip tests
            for (seed in 0L until 2000L) {
                val info = randomConnInfo(seed)
                val pkt = createKeepalivePacketExt(info, 1_000_000L)
                assertEquals(getPacketType(pkt), SRTLA_TYPE_KEEPALIVE)
                assertTrue(extractKeepaliveTimestamp(pkt) != null)
                assertEquals(extractKeepaliveConnInfo(pkt), info)
            }
        }

        test("srtla_ack_roundtrips") {
            // 2000 random ACK array roundtrip tests
            for (seed in 0L until 2000L) {
                val rng = Random(seed)
                val ackCount = rng.nextInt(65)
                val acks = IntArray(ackCount)
                for (i in 0 until ackCount) acks[i] = rng.nextInt()
                val pkt = createAckPacket(acks)
                val parsed = parseSrtlaAck(pkt)
                assertContentEquals(parsed, acks)
            }
        }

        test("srt_nak_singles_roundtrip") {
            // 2000 random single-loss list NAK tests
            for (seed in 0L until 2000L) {
                val rng = Random(seed)
                val seqCount = rng.nextInt(65)
                val seqs = IntArray(seqCount)
                for (i in 0 until seqCount) {
                    seqs[i] = (rng.nextInt(0x7fff_ffff.toInt())) and 0x7fff_ffff.toInt()
                }
                val buf = nakHeader()
                val fullBuf = buf + ByteArray(seqCount * 4)
                for ((i, s) in seqs.withIndex()) {
                    writeI32BE(fullBuf, SRT_CONTROL_HEADER_LEN + i * 4, s)
                }
                val parsed = parseSrtNak(fullBuf)
                assertContentEquals(parsed, seqs)
            }
        }

        test("srt_nak_range_roundtrips") {
            // 2000 random NAK range tests
            for (seed in 0L until 2000L) {
                val rng = Random(seed)
                val start = (rng.nextInt(0x7fff_0000.toInt())) and 0x7fff_0000.toInt()
                val delta = rng.nextInt(200)
                val end = start + delta

                val buf = nakHeader()
                val fullBuf = buf + ByteArray(8)
                writeI32BE(fullBuf, SRT_CONTROL_HEADER_LEN, (start or 0x8000_0000.toInt()).toInt())
                writeI32BE(fullBuf, SRT_CONTROL_HEADER_LEN + 4, end)

                val parsed = parseSrtNak(fullBuf)
                val expected = IntArray(end - start + 1)
                for (i in expected.indices) {
                    expected[i] = start + i
                }
                assertContentEquals(parsed, expected)
            }
        }

        test("srt_ack_roundtrips") {
            // 2000 random SRT ACK roundtrip tests
            for (seed in 0L until 2000L) {
                val rng = Random(seed)
                val ack = rng.nextInt()
                val buf = ByteArray(20)
                writeU16BE(buf, 0, SRT_TYPE_ACK)
                writeI32BE(buf, 16, ack)
                val parsed = parseSrtAck(buf)
                assertEquals(parsed, ack)
            }
        }

        test("reg1_reg2_roundtrip") {
            // 2000 random REG1/REG2 roundtrip tests
            for (seed in 0L until 2000L) {
                val rng = Random(seed)
                val id = ByteArray(SRTLA_ID_LEN)
                rng.nextBytes(id)

                val r1 = createReg1Packet(id)
                assertEquals(r1.size, SRTLA_TYPE_REG1_LEN)
                assertEquals(getPacketType(r1), SRTLA_TYPE_REG1)
                assertContentEquals(r1.copyOfRange(2, SRTLA_ID_LEN + 2), id)

                val r2 = createReg2Packet(id)
                assertEquals(r2.size, SRTLA_TYPE_REG2_LEN)
                assertEquals(getPacketType(r2), SRTLA_TYPE_REG2)
                assertContentEquals(r2.copyOfRange(2, SRTLA_ID_LEN + 2), id)
            }
        }

        test("standard_keepalive_has_timestamp_no_conn_info") {
            // 2000 random standard keepalive tests
            for (seed in 0L until 2000L) {
                val pkt = createKeepalivePacket(1_000_000L + seed)
                assertTrue(getPacketType(pkt) == SRTLA_TYPE_KEEPALIVE)
                assertTrue(extractKeepaliveTimestamp(pkt) != null)
                assertEquals(extractKeepaliveConnInfo(pkt), null)
            }
        }
    }
}

// Extension function for Int.toShort().toBytes() pattern
private fun Short.toBytes(): ByteArray {
    return byteArrayOf(
        ((this.toInt() ushr 8) and 0xFF).toByte(),
        (this.toInt() and 0xFF).toByte()
    )
}
