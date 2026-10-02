// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-protocol/src/lib.rs (mod tests), crates/srtla-protocol/src/parsers.rs (mod nak_tests), src/tests/protocol_tests.rs
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.protocol.*
import dev.abdulkadirozyurt.srtla.testkit.*

// ── NAK test helpers ──────────────────────────────────────────────────────────

private fun protoPrintNak(words: IntArray): ByteArray {
    val buf = ByteArray(SRT_CONTROL_HEADER_LEN + words.size * 4)
    writeU16BE(buf, 0, SRT_TYPE_NAK)
    for ((i, word) in words.withIndex()) {
        writeI32BE(buf, SRT_CONTROL_HEADER_LEN + i * 4, word)
    }
    return buf
}

// ── Handshake test helpers ────────────────────────────────────────────────────

private fun protoHandshake(
    version: Int,
    reqType: Int,
    extField: Int,
    blocks: List<Pair<Int, IntArray>>,
): ByteArray {
    val buf = ByteArray(SRT_CONTROL_HEADER_LEN + SRT_HANDSHAKE_CIF_LEN + 100)
    var offset = 0

    // Control header
    writeU16BE(buf, offset, SRT_TYPE_HANDSHAKE)
    offset += 2
    for (i in 2 until SRT_CONTROL_HEADER_LEN) buf[i] = 0
    offset = SRT_CONTROL_HEADER_LEN

    // Handshake CIF
    writeI32BE(buf, offset, version)
    offset += 4
    writeI32BE(buf, offset, extField)
    offset += 4
    for (i in 8 until 20) buf[offset + i - 8] = 0
    offset += 12
    writeI32BE(buf, offset, reqType)
    offset += 4
    for (i in 24 until SRT_CONTROL_HEADER_LEN + SRT_HANDSHAKE_CIF_LEN) {
        if (i >= offset) buf[i] = 0
    }
    offset = SRT_CONTROL_HEADER_LEN + SRT_HANDSHAKE_CIF_LEN

    // Extension blocks
    for ((cmd, body) in blocks) {
        val spec = ((cmd and 0xffff) shl 16) or (body.size and 0xffff)
        writeI32BE(buf, offset, spec)
        offset += 4
        for (word in body) {
            writeI32BE(buf, offset, word)
            offset += 4
        }
    }

    return buf.copyOf(offset)
}

private fun protoHsBody(flags: Int, rcvMs: Int, sndMs: Int): IntArray {
    return intArrayOf(
        0x0001_0500.toInt(), // SRT 1.5.0
        flags,
        ((rcvMs and 0xffff) shl 16) or (sndMs and 0xffff),
    )
}

private fun protoConclusion(blocks: List<Pair<Int, IntArray>>): ByteArray {
    return protoHandshake(SRT_HS_VERSION_5, SRT_HS_REQTYPE_CONCLUSION, SRT_HS_EXT_FLAG_HSREQ, blocks)
}

fun registerProtocolTests() {
    // ── Extended keepalive tests (lib.rs) ──────────────────────────────────

    suite("Extended keepalive") {
        test("roundtrip with full connection info") {
            val info = ConnectionInfo(
                connId = 42L,
                window = 25000,
                inFlight = 8,
                rttMs = 120L,
                nakCount = 5L,
                bitrateBytesSec = 2_500_000L,
            )

            val pkt = createKeepalivePacketExt(info, 123_456)

            // Verify packet length
            assertEquals(SRTLA_KEEPALIVE_EXT_LEN, pkt.size)

            // Verify packet type
            assertEquals(SRTLA_TYPE_KEEPALIVE, getPacketType(pkt))

            // Verify timestamp extraction works (backwards compatible)
            assertNotNull(extractKeepaliveTimestamp(pkt))

            // Verify connection info extraction
            val extracted = extractKeepaliveConnInfo(pkt)
            assertNotNull(extracted)
            assertEquals(info, extracted)
        }

        test("standard keepalive has no connection info") {
            val pkt = createKeepalivePacket(123_456)

            assertEquals(10, pkt.size)
            assertNotNull(extractKeepaliveTimestamp(pkt))
            assertNull(extractKeepaliveConnInfo(pkt))
        }

        test("backwards compat: extended and standard keepalive have same timestamp") {
            val info = ConnectionInfo(
                connId = 1L,
                window = 20000,
                inFlight = 5,
                rttMs = 100L,
                nakCount = 2L,
                bitrateBytesSec = 1_000_000L,
            )

            val extPkt = createKeepalivePacketExt(info, 123_456)
            val tsFromExt = extractKeepaliveTimestamp(extPkt)
            assertNotNull(tsFromExt)

            val stdPkt = createKeepalivePacket(123_456)
            val tsFromStd = extractKeepaliveTimestamp(stdPkt)
            assertNotNull(tsFromStd)

            // Both should be the same timestamp
            assertEquals(tsFromExt, tsFromStd)
        }

        test("wrong magic returns null") {
            val pkt = ByteArray(SRTLA_KEEPALIVE_EXT_LEN)
            writeU16BE(pkt, 0, SRTLA_TYPE_KEEPALIVE)
            writeU16BE(pkt, 10, 0xdead) // Wrong magic
            assertNull(extractKeepaliveConnInfo(pkt))
        }

        test("wrong version returns null") {
            val pkt = ByteArray(SRTLA_KEEPALIVE_EXT_LEN)
            writeU16BE(pkt, 0, SRTLA_TYPE_KEEPALIVE)
            writeU16BE(pkt, 10, SRTLA_KEEPALIVE_MAGIC)
            writeU16BE(pkt, 12, 0x9999) // Wrong version
            assertNull(extractKeepaliveConnInfo(pkt))
        }
    }

    // ── Retransmit flag tests (lib.rs) ────────────────────────────────────

    suite("Retransmit flag") {
        test("detection on SRT data packet") {
            // SRT data packet: MSB clear; second word is PP(2) O(1) KK(2) R(1)...
            // R is bit 2 in byte 4, i.e., 0x04
            val pkt = ByteArray(16)
            writeI32BE(pkt, 0, 100)
            assertFalse(isSrtDataRetransmit(pkt), "original send has R clear")

            pkt[4] = (pkt[4].toInt() or 0x04).toByte()
            assertTrue(isSrtDataRetransmit(pkt), "R bit marks a retransmission")
        }

        test("PP/O/KK bits alone don't read as retransmit") {
            val pkt = ByteArray(16)
            writeI32BE(pkt, 0, 100)
            pkt[4] = 0xf8.toByte() // PP=11 O=1 KK=11, R=0
            assertFalse(isSrtDataRetransmit(pkt))
        }

        test("setting bit is idempotent and touches nothing else") {
            val probe = ByteArray(16)
            writeI32BE(probe, 0, 0x1234_5678)
            writeI32BE(probe, 4, 0x0abc_defa.toInt())
            val before = probe.copyOf()

            setSrtDataRetransmit(probe)
            assertTrue(isSrtDataRetransmit(probe), "R bit must be set")
            assertContentEquals(before.copyOfRange(0, 4), probe.copyOfRange(0, 4), "sequence must not move")
            assertEquals(before[4].toInt() or 0x04, probe[4].toInt(), "only R bit set")
            assertContentEquals(before.copyOfRange(5, 16), probe.copyOfRange(5, 16), "msg number must not move")

            // Idempotent: already-retransmitted stays valid
            setSrtDataRetransmit(probe)
            assertTrue(isSrtDataRetransmit(probe))
        }

        test("control packets left alone") {
            val ctrl = ByteArray(16)
            ctrl[0] = 0x80.toByte()
            val ctrlBefore = ctrl.copyOf()
            setSrtDataRetransmit(ctrl)
            assertContentEquals(ctrlBefore, ctrl, "control packets untouched")
        }

        test("runt buffers left alone") {
            val runt = ByteArray(4)
            setSrtDataRetransmit(runt)
            assertContentEquals(ByteArray(4), runt, "runt untouched")
        }

        test("control packets never read as retransmits") {
            val ctrl = ByteArray(16)
            ctrl[0] = 0x80.toByte()
            ctrl[4] = 0x04.toByte()
            assertFalse(isSrtDataRetransmit(ctrl))
        }

        test("truncated buffers rejected") {
            val pkt = ByteArray(7)
            assertFalse(isSrtDataRetransmit(pkt))
        }
    }

    // ── SRT handshake latency tests (lib.rs) ───────────────────────────────

    val BOTH_TSBPD = SRT_HS_OPT_TSBPDSND or SRT_HS_OPT_TSBPDRCV

    suite("SRT handshake latency") {
        test("HSRSP yields the far end's receive latency") {
            val pkt = protoConclusion(
                listOf(SRT_HS_EXT_CMD_HSRSP to protoHsBody(BOTH_TSBPD, 4000, 120))
            )
            val hs = parseSrtHandshakeLatency(pkt)!!
            assertTrue(hs.isResponse)
            assertEquals(4000, hs.rcvMs)
            assertEquals(120, hs.sndMs)
        }

        test("HSREQ is marked as a proposal not a response") {
            val pkt = protoConclusion(
                listOf(SRT_HS_EXT_CMD_HSREQ to protoHsBody(BOTH_TSBPD, 4000, 120))
            )
            val hs = parseSrtHandshakeLatency(pkt)!!
            assertFalse(hs.isResponse, "HSREQ is not the negotiated answer")
            assertEquals(4000, hs.rcvMs)
        }

        test("each latency half gated on its own TSBPD flag") {
            val none = protoConclusion(
                listOf(SRT_HS_EXT_CMD_HSRSP to protoHsBody(0, 4000, 120))
            )
            val hs = parseSrtHandshakeLatency(none)!!
            assertNull(hs.rcvMs)
            assertNull(hs.sndMs)

            val rcvOnly = protoConclusion(
                listOf(SRT_HS_EXT_CMD_HSRSP to protoHsBody(SRT_HS_OPT_TSBPDRCV, 4000, 120))
            )
            val hs2 = parseSrtHandshakeLatency(rcvOnly)!!
            assertEquals(4000, hs2.rcvMs)
            assertNull(hs2.sndMs)
        }

        test("HSRSP found behind earlier blocks") {
            val SRT_HS_EXT_CMD_SID = 5
            val pkt = protoConclusion(
                listOf(
                    SRT_HS_EXT_CMD_SID to intArrayOf(0x7465_7374.toInt(), 0x0000_0000),
                    99 to intArrayOf(),
                    SRT_HS_EXT_CMD_HSRSP to protoHsBody(BOTH_TSBPD, 2500, 80),
                )
            )
            val hs = parseSrtHandshakeLatency(pkt)!!
            assertEquals(2500, hs.rcvMs)
        }

        test("only HSv5 conclusion is read") {
            val body = listOf(SRT_HS_EXT_CMD_HSRSP to protoHsBody(BOTH_TSBPD, 4000, 120))

            // Induction: word 1 holds a magic, not extension flags
            val induction = protoHandshake(SRT_HS_VERSION_5, 1, SRT_HS_EXT_FLAG_HSREQ, body)
            assertNull(parseSrtHandshakeLatency(induction))

            // HSv4: separate control packet
            val v4 = protoHandshake(4, SRT_HS_REQTYPE_CONCLUSION, SRT_HS_EXT_FLAG_HSREQ, body)
            assertNull(parseSrtHandshakeLatency(v4))

            // Rejection codes >= 1000
            val rejected = protoHandshake(SRT_HS_VERSION_5, 1002, SRT_HS_EXT_FLAG_HSREQ, body)
            assertNull(parseSrtHandshakeLatency(rejected))

            // No HSREQ block
            val noExt = protoHandshake(SRT_HS_VERSION_5, SRT_HS_REQTYPE_CONCLUSION, 0, body)
            assertNull(parseSrtHandshakeLatency(noExt))

            // Not a handshake
            val ack = ByteArray(64)
            writeU16BE(ack, 0, SRT_TYPE_ACK)
            assertNull(parseSrtHandshakeLatency(ack))
        }

        test("block length running past packet is rejected") {
            val mut = protoConclusion(
                listOf(SRT_HS_EXT_CMD_HSRSP to protoHsBody(BOTH_TSBPD, 4000, 120))
            )
            val spec = SRT_CONTROL_HEADER_LEN + SRT_HANDSHAKE_CIF_LEN
            writeU16BE(mut, spec + 2, 0xffff)
            assertNull(parseSrtHandshakeLatency(mut))
        }

        test("short HSRSP block is rejected") {
            val pkt = protoConclusion(
                listOf(SRT_HS_EXT_CMD_HSRSP to intArrayOf(0x0001_0500.toInt(), BOTH_TSBPD))
            )
            assertNull(parseSrtHandshakeLatency(pkt))
        }

        test("no prefix of handshake panics") {
            val pkt = protoConclusion(
                listOf(
                    5 to intArrayOf(0x7465_7374.toInt()),
                    SRT_HS_EXT_CMD_HSRSP to protoHsBody(BOTH_TSBPD, 4000, 120),
                )
            )
            for (len in 0..pkt.size) {
                parseSrtHandshakeLatency(pkt.copyOf(len))
            }
            val hs = parseSrtHandshakeLatency(pkt)!!
            assertEquals(4000, hs.rcvMs, "intact packet must still parse")
        }

        test("garbage extension area terminates") {
            val pkt = protoConclusion(emptyList()).toMutableList()
            for (i in 0 until 64) {
                val word = (0xdead_0000L.toInt()).toInt() + i
                for (j in 0 until 4) {
                    pkt.add(((word shr (24 - 8 * j)) and 0xff).toByte())
                }
            }
            parseSrtHandshakeLatency(pkt.toByteArray())
        }
    }

    // ── NAK parsing tests (parsers.rs mod nak_tests) ──────────────────────

    suite("SRT NAK parsing") {
        test("single entries unchanged") {
            assertContentEquals(intArrayOf(7), parseSrtNak(protoPrintNak(intArrayOf(7))))
            assertContentEquals(
                intArrayOf(500, 501, 9),
                parseSrtNak(protoPrintNak(intArrayOf(500, 501, 9)))
            )
        }

        test("ranges expanded") {
            val rangeFlag = Int.MIN_VALUE
            assertContentEquals(
                intArrayOf(100, 101, 102, 103),
                parseSrtNak(protoPrintNak(intArrayOf(100 or rangeFlag, 103)))
            )
            // Single-element range
            assertContentEquals(
                intArrayOf(42),
                parseSrtNak(protoPrintNak(intArrayOf(42 or rangeFlag, 42)))
            )
            // Mixed
            assertContentEquals(
                intArrayOf(50, 100, 101, 102, 60),
                parseSrtNak(protoPrintNak(intArrayOf(50, 100 or rangeFlag, 102, 60)))
            )
        }

        test("end word with MSB set emits nothing") {
            assertTrue(
                parseSrtNak(protoPrintNak(intArrayOf(10 or Int.MIN_VALUE, 0xffff_ffff.toInt()))).isEmpty()
            )
            assertTrue(
                parseSrtNak(protoPrintNak(intArrayOf(10 or Int.MIN_VALUE, 20 or Int.MIN_VALUE))).isEmpty()
            )
        }

        test("invalid range doesn't desync rest of list") {
            assertContentEquals(
                intArrayOf(1, 2),
                parseSrtNak(protoPrintNak(intArrayOf(1, 10 or Int.MIN_VALUE, 0xffff_ffff.toInt(), 2)))
            )
        }

        test("end below start yields nothing") {
            assertTrue(parseSrtNak(protoPrintNak(intArrayOf(100 or Int.MIN_VALUE, 99))).isEmpty())
            assertContentEquals(
                intArrayOf(7),
                parseSrtNak(protoPrintNak(intArrayOf(100 or Int.MIN_VALUE, 99, 7)))
            )
        }

        test("range ending at max sequence terminates") {
            val max = 0x7fff_ffff
            val out = parseSrtNak(protoPrintNak(intArrayOf((max - 3) or Int.MIN_VALUE, max)))
            assertContentEquals(intArrayOf(max - 3, max - 2, max - 1, max), out)

            val out2 = parseSrtNak(protoPrintNak(intArrayOf(max or Int.MIN_VALUE, max)))
            assertContentEquals(intArrayOf(max), out2)
        }

        test("oversized range saturates at cap") {
            val out = parseSrtNak(protoPrintNak(intArrayOf(1 or Int.MIN_VALUE, 100_000)))
            assertEquals(SRT_NAK_MAX_LOSS_IDS, out.size)
            assertEquals(1, out[0])
            assertEquals(SRT_NAK_MAX_LOSS_IDS, out[SRT_NAK_MAX_LOSS_IDS - 1])

            val out2 = parseSrtNak(protoPrintNak(intArrayOf(Int.MIN_VALUE, 0x7fff_ffff)))
            assertEquals(SRT_NAK_MAX_LOSS_IDS, out2.size)
        }

        test("range start word without end word is dropped") {
            assertContentEquals(
                intArrayOf(7),
                parseSrtNak(protoPrintNak(intArrayOf(7, 100 or Int.MIN_VALUE)))
            )
        }
    }

    // ── Protocol tests (src/tests/protocol_tests.rs) ───────────────────────

    suite("Protocol packet types") {
        test("get_packet_type valid types") {
            val buf1 = byteArrayOf(0x90.toByte(), 0x00.toByte(), 0x01, 0x02)
            assertEquals(SRTLA_TYPE_KEEPALIVE, getPacketType(buf1))

            val buf2 = byteArrayOf(0x80.toByte(), 0x02, 0x01, 0x02)
            assertEquals(SRT_TYPE_ACK, getPacketType(buf2))

            assertNull(getPacketType(byteArrayOf()))
            assertNull(getPacketType(byteArrayOf(0x90.toByte())))
        }

        test("get_srt_sequence_number") {
            val buf1 = byteArrayOf(0x00, 0x00, 0x10.toByte(), 0x00)
            assertEquals(0x1000, getSrtSequenceNumber(buf1))

            val buf2 = byteArrayOf(0x80.toByte(), 0x00, 0x10.toByte(), 0x00)
            assertNull(getSrtSequenceNumber(buf2), "control bit set")

            val buf3 = byteArrayOf(0x00, 0x00, 0x00, 0x00)
            assertEquals(0, getSrtSequenceNumber(buf3))

            assertNull(getSrtSequenceNumber(byteArrayOf(0x00, 0x00)))
            assertNull(getSrtSequenceNumber(byteArrayOf()))
        }

        test("create_reg1_packet") {
            val id = ByteArray(SRTLA_ID_LEN) { 0x42.toByte() }
            val pkt = createReg1Packet(id)
            assertEquals(SRTLA_TYPE_REG1_LEN, pkt.size)
            assertEquals(SRTLA_TYPE_REG1, getPacketType(pkt))
            assertTrue(pkt.drop(2).all { it == 0x42.toByte() })
        }

        test("create_reg2_packet") {
            val id = ByteArray(SRTLA_ID_LEN) { 0x24.toByte() }
            val pkt = createReg2Packet(id)
            assertEquals(SRTLA_TYPE_REG2_LEN, pkt.size)
            assertEquals(SRTLA_TYPE_REG2, getPacketType(pkt))
            assertTrue(pkt.drop(2).all { it == 0x24.toByte() })
        }

        test("create_keepalive_packet") {
            val pkt = createKeepalivePacket(123_456)
            assertEquals(10, pkt.size)
            assertEquals(SRTLA_TYPE_KEEPALIVE, getPacketType(pkt))
            assertTrue(isSrtlaKeepalive(pkt))

            val ts = extractKeepaliveTimestamp(pkt)
            assertNotNull(ts)
            assertEquals(123_456L, ts)
        }

        test("extract_keepalive_timestamp") {
            val pkt = ByteArray(10)
            writeU16BE(pkt, 0, SRTLA_TYPE_KEEPALIVE)
            val testTs = 0x0102030405060708L
            for (i in 0 until 8) {
                pkt[2 + i] = ((testTs shr (56 - i * 8)) and 0xff).toByte()
            }

            assertEquals(testTs, extractKeepaliveTimestamp(pkt))

            pkt[0] = SRT_TYPE_ACK.toByte()
            pkt[1] = (SRT_TYPE_ACK shr 8).toByte()
            assertNull(extractKeepaliveTimestamp(pkt))

            assertNull(extractKeepaliveTimestamp(pkt.copyOf(5)))
        }

        test("create_ack_packet") {
            val acks = intArrayOf(100, 200, 300)
            val pkt = createAckPacket(acks)
            assertEquals(4 + 4 * acks.size, pkt.size)
            assertEquals(SRTLA_TYPE_ACK, getPacketType(pkt))
        }

        test("create_ack_packet_with_4byte_header") {
            val acks = intArrayOf(100, 200, 300)
            val pkt = ByteArray(4 + 4 * acks.size)
            writeU16BE(pkt, 0, SRTLA_TYPE_ACK)
            pkt[2] = 0x00
            pkt[3] = 0x00
            for ((i, ack) in acks.withIndex()) {
                writeI32BE(pkt, 4 + i * 4, ack)
            }

            assertEquals(SRTLA_TYPE_ACK, getPacketType(pkt))
            assertContentEquals(acks, parseSrtlaAck(pkt))
        }

        test("parse_srt_ack") {
            val buf = ByteArray(20)
            writeU16BE(buf, 0, SRT_TYPE_ACK)
            writeI32BE(buf, 16, 12345)
            assertEquals(12345, parseSrtAck(buf))

            buf[0] = 0x90.toByte()
            assertNull(parseSrtAck(buf))

            assertNull(parseSrtAck(buf.copyOf(19)))
        }

        test("parse_srt_nak_single") {
            val buf = ByteArray(SRT_CONTROL_HEADER_LEN + 4)
            writeU16BE(buf, 0, SRT_TYPE_NAK)
            writeI32BE(buf, SRT_CONTROL_HEADER_LEN, 500)
            assertContentEquals(intArrayOf(500), parseSrtNak(buf))
        }

        test("parse_srt_nak_range") {
            val buf = ByteArray(SRT_CONTROL_HEADER_LEN + 8)
            writeU16BE(buf, 0, SRT_TYPE_NAK)
            writeI32BE(buf, SRT_CONTROL_HEADER_LEN, 100 or Int.MIN_VALUE)
            writeI32BE(buf, SRT_CONTROL_HEADER_LEN + 4, 103)
            assertContentEquals(intArrayOf(100, 101, 102, 103), parseSrtNak(buf))
        }

        test("parse_srt_nak_mixed") {
            val buf = ByteArray(SRT_CONTROL_HEADER_LEN + 12)
            writeU16BE(buf, 0, SRT_TYPE_NAK)
            writeI32BE(buf, SRT_CONTROL_HEADER_LEN, 50)
            writeI32BE(buf, SRT_CONTROL_HEADER_LEN + 4, 100 or Int.MIN_VALUE)
            writeI32BE(buf, SRT_CONTROL_HEADER_LEN + 8, 102)
            assertContentEquals(intArrayOf(50, 100, 101, 102), parseSrtNak(buf))
        }

        test("parse_srt_nak_ignores_control_header") {
            val buf = ByteArray(SRT_CONTROL_HEADER_LEN + 4)
            writeU16BE(buf, 0, SRT_TYPE_NAK)
            writeI32BE(buf, SRT_CONTROL_HEADER_LEN, 7)
            assertContentEquals(intArrayOf(7), parseSrtNak(buf))

            val headerOnly = ByteArray(SRT_CONTROL_HEADER_LEN)
            writeU16BE(headerOnly, 0, SRT_TYPE_NAK)
            assertTrue(parseSrtNak(headerOnly).isEmpty())
        }

        test("parse_srt_nak_invalid") {
            val buf = ByteArray(SRT_CONTROL_HEADER_LEN + 4)
            writeU16BE(buf, 0, SRT_TYPE_ACK)
            writeI32BE(buf, SRT_CONTROL_HEADER_LEN, 500)
            assertTrue(parseSrtNak(buf).isEmpty())

            assertTrue(parseSrtNak(byteArrayOf(0x80.toByte(), 0x03, 0x00)).isEmpty())
        }

        test("parse_srtla_ack") {
            val acks = intArrayOf(1000, 2000, 3000)
            val pkt = ByteArray(4 + 4 * acks.size)
            writeU16BE(pkt, 0, SRTLA_TYPE_ACK)
            pkt[2] = 0x00
            pkt[3] = 0x00
            for ((i, ack) in acks.withIndex()) {
                writeI32BE(pkt, 4 + i * 4, ack)
            }

            assertContentEquals(acks, parseSrtlaAck(pkt))

            // Empty ACK
            val emptyPkt = byteArrayOf(0x91.toByte(), 0x00, 0x00, 0x00)
            assertTrue(parseSrtlaAck(emptyPkt).isEmpty())

            // Invalid type
            val invalid = pkt.copyOf()
            invalid[0] = 0x80.toByte()
            assertTrue(parseSrtlaAck(invalid).isEmpty())

            // Too short
            assertTrue(parseSrtlaAck(byteArrayOf(0x91.toByte(), 0x00, 0x00)).isEmpty())
        }

        test("packet_type_validators") {
            val reg1Id = ByteArray(SRTLA_ID_LEN) { 0x11.toByte() }
            val reg1Pkt = createReg1Packet(reg1Id)
            assertTrue(isSrtlaReg1(reg1Pkt))
            assertFalse(isSrtlaReg2(reg1Pkt))
            assertFalse(isSrtlaReg3(reg1Pkt))

            val reg2Id = ByteArray(SRTLA_ID_LEN) { 0x22.toByte() }
            val reg2Pkt = createReg2Packet(reg2Id)
            assertFalse(isSrtlaReg1(reg2Pkt))
            assertTrue(isSrtlaReg2(reg2Pkt))
            assertFalse(isSrtlaReg3(reg2Pkt))

            val reg3Pkt = byteArrayOf(
                (SRTLA_TYPE_REG3 shr 8).toByte(),
                (SRTLA_TYPE_REG3 and 0xff).toByte()
            )
            assertFalse(isSrtlaReg1(reg3Pkt))
            assertFalse(isSrtlaReg2(reg3Pkt))
            assertTrue(isSrtlaReg3(reg3Pkt))

            val keepalive = createKeepalivePacket(System.currentTimeMillis())
            assertTrue(isSrtlaKeepalive(keepalive))

            val ackPkt = ByteArray(20)
            writeU16BE(ackPkt, 0, SRT_TYPE_ACK)
            assertTrue(isSrtAck(ackPkt))
        }

        test("constants") {
            assertEquals(256, SRTLA_ID_LEN)
            assertEquals(2 + SRTLA_ID_LEN, SRTLA_TYPE_REG1_LEN)
            assertEquals(2 + SRTLA_ID_LEN, SRTLA_TYPE_REG2_LEN)
            assertEquals(2, SRTLA_TYPE_REG3_LEN)

            assertTrue(WINDOW_MIN < WINDOW_DEF)
            assertTrue(WINDOW_DEF < WINDOW_MAX)
            assertTrue(WINDOW_INCR > 0)
            assertTrue(WINDOW_DECR > 0)
            assertTrue(WINDOW_MULT > 0)
        }
    }

    // ── Encode tests (src/tests/protocol_tests.rs encode module) ──────────

    suite("Protocol encode") {
        test("reg1_first_two_bytes_and_total_len") {
            val id = ByteArray(SRTLA_ID_LEN) { 0xab.toByte() }
            val buf = createReg1Packet(id)
            assertContentEquals(byteArrayOf(0x92.toByte(), 0x00), buf.copyOf(2))
            assertEquals(258, buf.size)
        }

        test("reg2_first_two_bytes_and_total_len") {
            val id = ByteArray(SRTLA_ID_LEN) { 0xcd.toByte() }
            val buf = createReg2Packet(id)
            assertContentEquals(byteArrayOf(0x92.toByte(), 0x01), buf.copyOf(2))
            assertEquals(258, buf.size)
        }

        test("reg3_type_and_len") {
            val buf = ByteArray(2)
            writeU16BE(buf, 0, SRTLA_TYPE_REG3)
            assertContentEquals(byteArrayOf(0x92.toByte(), 0x02), buf)
            assertEquals(2, buf.size)
        }

        test("keepalive_is_bare_2_bytes") {
            val buf = ByteArray(2)
            writeU16BE(buf, 0, SRTLA_TYPE_KEEPALIVE)
            assertContentEquals(byteArrayOf(0x90.toByte(), 0x00), buf)
            assertEquals(2, buf.size)
            assertFalse(
                buf.toList().windowed(2).any { it.toByteArray().contentEquals(ByteArray(2) { _ ->
                    (SRTLA_KEEPALIVE_MAGIC ushr 8).toByte()
                }) }
            )
        }
    }

    // ── Decode tests (src/tests/protocol_tests.rs decode module) ──────────

    suite("Protocol decode") {
        test("decode_reg2_valid") {
            val id = ByteArray(SRTLA_ID_LEN) { 0x5a.toByte() }
            val pkt = createReg2Packet(id)
            assertEquals(SRTLA_TYPE_REG2, getPacketType(pkt))
            assertTrue(isSrtlaReg2(pkt))
            assertFalse(isSrtlaReg1(pkt))
            assertFalse(isSrtlaReg3(pkt))
            assertContentEquals(id, pkt.copyOfRange(2, pkt.size))
        }

        test("decode_reg3_valid") {
            val pkt = ByteArray(2)
            writeU16BE(pkt, 0, SRTLA_TYPE_REG3)
            assertEquals(SRTLA_TYPE_REG3, getPacketType(pkt))
            assertTrue(isSrtlaReg3(pkt))
            assertFalse(isSrtlaReg1(pkt))
            assertFalse(isSrtlaReg2(pkt))
        }

        test("decode_reg_err_valid") {
            val pkt = ByteArray(2)
            writeU16BE(pkt, 0, SRTLA_TYPE_REG_ERR)
            assertEquals(SRTLA_TYPE_REG_ERR, getPacketType(pkt))
            assertFalse(isSrtlaReg1(pkt))
            assertFalse(isSrtlaReg2(pkt))
            assertFalse(isSrtlaReg3(pkt))
        }

        test("decode_reg_ngp_valid") {
            val pkt = ByteArray(2)
            writeU16BE(pkt, 0, SRTLA_TYPE_REG_NGP)
            assertEquals(SRTLA_TYPE_REG_NGP, getPacketType(pkt))
            assertFalse(isSrtlaReg1(pkt))
            assertFalse(isSrtlaReg2(pkt))
            assertFalse(isSrtlaReg3(pkt))
        }

        test("decode_ack_valid") {
            val acks = intArrayOf(1234, 5678, 9012)
            val pkt = createAckPacket(acks)
            assertEquals(SRTLA_TYPE_ACK, getPacketType(pkt))
            assertContentEquals(acks, parseSrtlaAck(pkt))
        }

        test("decode_srt_ack_nak") {
            val ack = ByteArray(20)
            writeU16BE(ack, 0, SRT_TYPE_ACK)
            writeI32BE(ack, 16, 424_242)
            assertEquals(SRT_TYPE_ACK, getPacketType(ack))
            assertTrue(isSrtAck(ack))
            assertEquals(424_242, parseSrtAck(ack))

            val nak = ByteArray(20)
            writeU16BE(nak, 0, SRT_TYPE_NAK)
            writeI32BE(nak, SRT_CONTROL_HEADER_LEN, 777)
            assertEquals(SRT_TYPE_NAK, getPacketType(nak))
            assertContentEquals(intArrayOf(777), parseSrtNak(nak))
        }

        test("decode_keepalive_valid") {
            val pkt = createKeepalivePacket(System.currentTimeMillis())
            assertEquals(SRTLA_TYPE_KEEPALIVE, getPacketType(pkt))
            assertTrue(isSrtlaKeepalive(pkt))
            assertNotNull(extractKeepaliveTimestamp(pkt))
        }
    }

    // ── Malformed input tests (src/tests/protocol_tests.rs malformed module)

    suite("Protocol malformed") {
        test("zero_length_returns_none_or_err") {
            val empty = byteArrayOf()
            assertNull(getPacketType(empty))
            assertNull(getSrtSequenceNumber(empty))
            assertNull(parseSrtAck(empty))
            assertNull(extractKeepaliveTimestamp(empty))
            assertNull(extractKeepaliveConnInfo(empty))
            assertTrue(parseSrtNak(empty).isEmpty())
            assertTrue(parseSrtlaAck(empty).isEmpty())
        }

        test("truncated_id_returns_none_or_err") {
            val buf = ByteArray(2 + SRTLA_ID_LEN / 2)
            writeU16BE(buf, 0, SRTLA_TYPE_REG2)
            assertFalse(isSrtlaReg2(buf))
            assertFalse(isSrtlaReg1(buf))
            assertEquals(SRTLA_TYPE_REG2, getPacketType(buf))
        }

        test("unknown_type_returns_none_or_err") {
            val buf = ByteArray(20)
            writeU16BE(buf, 0, 0x9999)
            assertNull(parseSrtAck(buf))
            assertNull(extractKeepaliveTimestamp(buf))
            assertNull(extractKeepaliveConnInfo(buf))
            assertTrue(parseSrtNak(buf).isEmpty())
            assertTrue(parseSrtlaAck(buf).isEmpty())
            assertFalse(isSrtlaReg1(buf))
            assertFalse(isSrtlaReg2(buf))
            assertFalse(isSrtlaReg3(buf))
            assertFalse(isSrtlaKeepalive(buf))
            assertFalse(isSrtAck(buf))
        }

        test("short_frame_returns_none_or_err") {
            val one = byteArrayOf(0x91.toByte())
            assertNull(getPacketType(one))
            assertNull(getSrtSequenceNumber(one))
            assertNull(parseSrtAck(one))
            assertNull(extractKeepaliveTimestamp(one))
            assertNull(extractKeepaliveConnInfo(one))
            assertTrue(parseSrtNak(one).isEmpty())
            assertTrue(parseSrtlaAck(one).isEmpty())
        }
    }

    // ── Integration tests (src/tests/integration_tests.rs) ────────────────

    suite("Protocol integration") {
        test("protocol_packet_roundtrip") {
            val id = ByteArray(SRTLA_ID_LEN) { 0x42.toByte() }
            val reg1Pkt = createReg1Packet(id)
            assertTrue(isSrtlaReg1(reg1Pkt))
            assertEquals(SRTLA_TYPE_REG1, getPacketType(reg1Pkt))

            val reg2Pkt = createReg2Packet(id)
            assertTrue(isSrtlaReg2(reg2Pkt))
            assertEquals(SRTLA_TYPE_REG2, getPacketType(reg2Pkt))

            val keepalivePkt = createKeepalivePacket(System.currentTimeMillis())
            assertTrue(isSrtlaKeepalive(keepalivePkt))
            assertEquals(SRTLA_TYPE_KEEPALIVE, getPacketType(keepalivePkt))

            val ts = extractKeepaliveTimestamp(keepalivePkt)!!
            assertTrue(ts > 0)
        }

        test("srt_ack_nak_parsing") {
            val ackPkt = ByteArray(20)
            writeU16BE(ackPkt, 0, SRT_TYPE_ACK)
            writeI32BE(ackPkt, 16, 54321)
            assertEquals(54321, parseSrtAck(ackPkt))
            assertTrue(isSrtAck(ackPkt))

            val nakPkt = ByteArray(20)
            writeU16BE(nakPkt, 0, SRT_TYPE_NAK)
            writeI32BE(nakPkt, SRT_CONTROL_HEADER_LEN, 12345)
            assertContentEquals(intArrayOf(12345), parseSrtNak(nakPkt))

            val rangeNakPkt = ByteArray(24)
            writeU16BE(rangeNakPkt, 0, SRT_TYPE_NAK)
            writeI32BE(rangeNakPkt, SRT_CONTROL_HEADER_LEN, 1000 or Int.MIN_VALUE)
            writeI32BE(rangeNakPkt, SRT_CONTROL_HEADER_LEN + 4, 1003)
            assertContentEquals(intArrayOf(1000, 1001, 1002, 1003), parseSrtNak(rangeNakPkt))
        }

        test("srtla_ack_roundtrip") {
            val originalAcks = intArrayOf(100, 200, 300, 400)
            val ackPkt = createAckPacket(originalAcks)
            assertEquals(SRTLA_TYPE_ACK, getPacketType(ackPkt))
            assertContentEquals(originalAcks, parseSrtlaAck(ackPkt))
        }

        test("sequence_number_parsing") {
            val dataPkt = byteArrayOf(0x00, 0x00, 0x12.toByte(), 0x34.toByte())
            assertEquals(0x1234, getSrtSequenceNumber(dataPkt))

            val ctrlPkt = byteArrayOf(0x80.toByte(), 0x00, 0x12.toByte(), 0x34.toByte())
            assertNull(getSrtSequenceNumber(ctrlPkt))

            val maxSeqPkt = byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
            assertEquals(0x7fffffff, getSrtSequenceNumber(maxSeqPkt))
        }

        test("packet_type_constants") {
            assertTrue((SRTLA_TYPE_KEEPALIVE and 0xf000) == 0x9000)
            assertTrue((SRTLA_TYPE_ACK and 0xf000) == 0x9000)
            assertTrue((SRTLA_TYPE_REG1 and 0xf000) == 0x9000)
            assertTrue((SRTLA_TYPE_REG2 and 0xf000) == 0x9000)
            assertTrue((SRTLA_TYPE_REG3 and 0xf000) == 0x9000)

            assertTrue((SRT_TYPE_HANDSHAKE and 0xf000) == 0x8000)
            assertTrue((SRT_TYPE_ACK and 0xf000) == 0x8000)
            assertTrue((SRT_TYPE_NAK and 0xf000) == 0x8000)
            assertTrue((SRT_TYPE_SHUTDOWN and 0xf000) == 0x8000)

            assertEquals(0x0000, SRT_TYPE_DATA)
        }

        test("protocol_constants_consistency") {
            assertEquals(2 + SRTLA_ID_LEN, SRTLA_TYPE_REG1_LEN)
            assertEquals(2 + SRTLA_ID_LEN, SRTLA_TYPE_REG2_LEN)
            assertEquals(2, SRTLA_TYPE_REG3_LEN)

            assertTrue(WINDOW_MIN > 0)
            assertTrue(WINDOW_DEF > WINDOW_MIN)
            assertTrue(WINDOW_MAX > WINDOW_DEF)
            assertTrue(WINDOW_MULT > 0)
            assertTrue(WINDOW_INCR > 0)
            assertTrue(WINDOW_DECR > 0)

            assertTrue(CONN_TIMEOUT > 0)
            assertTrue(REG2_TIMEOUT > 0)
            assertTrue(REG3_TIMEOUT > 0)
            assertTrue(IDLE_TIME > 0)
        }

        test("large_nak_range_limit") {
            val largePkt = ByteArray(24)
            writeU16BE(largePkt, 0, SRT_TYPE_NAK)
            writeI32BE(largePkt, SRT_CONTROL_HEADER_LEN, 1 or Int.MIN_VALUE)
            writeI32BE(largePkt, SRT_CONTROL_HEADER_LEN + 4, 2000)
            val naks = parseSrtNak(largePkt)
            assertTrue(naks.size <= SRT_NAK_MAX_LOSS_IDS, "NAK range limited")
        }

        test("malformed_packet_handling") {
            assertNull(getPacketType(byteArrayOf()))
            assertNull(getPacketType(byteArrayOf(0x90.toByte())))

            assertNull(getSrtSequenceNumber(byteArrayOf()))
            assertNull(getSrtSequenceNumber(byteArrayOf(0x00, 0x00)))

            val shortNak = byteArrayOf(0x80.toByte(), 0x03, 0x00, 0x00)
            assertTrue(parseSrtNak(shortNak).isEmpty())

            val wrongType = byteArrayOf(0x80.toByte(), 0x02, 0x00, 0x00, 0x00, 0x00, 0x12.toByte(), 0x34.toByte())
            assertTrue(parseSrtNak(wrongType).isEmpty())
        }

        test("keepalive_timestamp_edge_cases") {
            val minKa = ByteArray(10)
            writeU16BE(minKa, 0, SRTLA_TYPE_KEEPALIVE)
            assertTrue(isSrtlaKeepalive(minKa))
            assertEquals(0L, extractKeepaliveTimestamp(minKa))

            val maxKa = ByteArray(10)
            writeU16BE(maxKa, 0, SRTLA_TYPE_KEEPALIVE)
            for (i in 2 until 10) maxKa[i] = 0xff.toByte()
            assertEquals((-1L), extractKeepaliveTimestamp(maxKa))

            val wrongType = ByteArray(10)
            writeU16BE(wrongType, 0, SRT_TYPE_ACK)
            assertNull(extractKeepaliveTimestamp(wrongType))
        }

        test("empty_ack_packet") {
            val emptyAcks = intArrayOf()
            val pkt = createAckPacket(emptyAcks)
            assertEquals(4, pkt.size)
            assertEquals(SRTLA_TYPE_ACK, getPacketType(pkt))
            assertTrue(parseSrtlaAck(pkt).isEmpty())
        }

        test("packet_validators_comprehensive") {
            val reg1Id = ByteArray(SRTLA_ID_LEN) { 0x11.toByte() }
            val reg1Pkt = createReg1Packet(reg1Id)

            val reg2Id = ByteArray(SRTLA_ID_LEN) { 0x22.toByte() }
            val reg2Pkt = createReg2Packet(reg2Id)

            val reg3Pkt = byteArrayOf((SRTLA_TYPE_REG3 shr 8).toByte(), (SRTLA_TYPE_REG3 and 0xff).toByte())
            val keepalivePkt = createKeepalivePacket(System.currentTimeMillis())

            val ackPkt = ByteArray(20)
            writeU16BE(ackPkt, 0, SRT_TYPE_ACK)

            assertTrue(isSrtlaReg1(reg1Pkt))
            assertFalse(isSrtlaReg1(reg2Pkt))
            assertFalse(isSrtlaReg1(reg3Pkt))
            assertFalse(isSrtlaReg1(keepalivePkt))
            assertFalse(isSrtlaReg1(ackPkt))

            assertFalse(isSrtlaReg2(reg1Pkt))
            assertTrue(isSrtlaReg2(reg2Pkt))
            assertFalse(isSrtlaReg2(reg3Pkt))
            assertFalse(isSrtlaReg2(keepalivePkt))
            assertFalse(isSrtlaReg2(ackPkt))

            assertFalse(isSrtlaReg3(reg1Pkt))
            assertFalse(isSrtlaReg3(reg2Pkt))
            assertTrue(isSrtlaReg3(reg3Pkt))
            assertFalse(isSrtlaReg3(keepalivePkt))
            assertFalse(isSrtlaReg3(ackPkt))

            assertFalse(isSrtlaKeepalive(reg1Pkt))
            assertFalse(isSrtlaKeepalive(reg2Pkt))
            assertFalse(isSrtlaKeepalive(reg3Pkt))
            assertTrue(isSrtlaKeepalive(keepalivePkt))
            assertFalse(isSrtlaKeepalive(ackPkt))

            assertFalse(isSrtAck(reg1Pkt))
            assertFalse(isSrtAck(reg2Pkt))
            assertFalse(isSrtAck(reg3Pkt))
            assertFalse(isSrtAck(keepalivePkt))
            assertTrue(isSrtAck(ackPkt))
        }

        test("id_length_constant") {
            assertTrue(SRTLA_ID_LEN >= 32)
            assertTrue(SRTLA_ID_LEN <= 1024)
            assertEquals(256, SRTLA_ID_LEN)
        }
    }
}
