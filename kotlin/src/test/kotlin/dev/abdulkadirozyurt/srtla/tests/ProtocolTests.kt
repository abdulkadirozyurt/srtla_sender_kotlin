// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/tests/protocol_tests.rs + src/protocol/mod.rs (inline tests)
//
// Every test function from the Rust source is represented here with the same
// scenario and assertions.  Additional roundtrip tests cover builder→parser
// paths not present in the Rust test file.
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.protocol.*
import dev.abdulkadirozyurt.srtla.testkit.*

fun registerProtocolTests() {

// ── Rust protocol_tests.rs::test_get_packet_type ─────────────────────────────
suite("getPacketType") {
    test("valid KEEPALIVE type") {
        val buf = byteArrayOf(0x90.toByte(), 0x00.toByte(), 0x01, 0x02)
        assertEquals(SRTLA_TYPE_KEEPALIVE, getPacketType(buf))
    }
    test("valid SRT_ACK type") {
        val buf = byteArrayOf(0x80.toByte(), 0x02, 0x01, 0x02)
        assertEquals(SRT_TYPE_ACK, getPacketType(buf))
    }
    test("empty buffer → null") {
        assertNull(getPacketType(byteArrayOf()))
    }
    test("single byte → null") {
        assertNull(getPacketType(byteArrayOf(0x90.toByte())))
    }
}

// ── Rust protocol_tests.rs::test_get_srt_sequence_number ─────────────────────
suite("getSrtSequenceNumber") {
    test("valid sequence number (control bit clear)") {
        val buf = byteArrayOf(0x00, 0x00, 0x10, 0x00)
        assertEquals(0x1000L, getSrtSequenceNumber(buf))
    }
    test("control bit set → null") {
        val buf = byteArrayOf(0x80.toByte(), 0x00, 0x10, 0x00)
        assertNull(getSrtSequenceNumber(buf))
    }
    test("sequence 0 is valid") {
        val buf = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        assertEquals(0L, getSrtSequenceNumber(buf))
    }
    test("2-byte buffer → null") {
        assertNull(getSrtSequenceNumber(byteArrayOf(0x00, 0x00)))
    }
    test("empty buffer → null") {
        assertNull(getSrtSequenceNumber(byteArrayOf()))
    }
}

// ── Rust protocol_tests.rs::test_create_reg1_packet ──────────────────────────
suite("createReg1Packet") {
    test("length, type, and ID bytes") {
        val id = ByteArray(SRTLA_ID_LEN) { 0x42 }
        val pkt = createReg1Packet(id)
        assertEquals(SRTLA_TYPE_REG1_LEN, pkt.size)
        assertEquals(SRTLA_TYPE_REG1, getPacketType(pkt))
        assertTrue(pkt.drop(2).all { it == 0x42.toByte() })
        assertTrue(isSrtlaReg1(pkt))
    }
}

// ── Rust protocol_tests.rs::test_create_reg2_packet ──────────────────────────
suite("createReg2Packet") {
    test("length, type, and ID bytes") {
        val id = ByteArray(SRTLA_ID_LEN) { 0x24 }
        val pkt = createReg2Packet(id)
        assertEquals(SRTLA_TYPE_REG2_LEN, pkt.size)
        assertEquals(SRTLA_TYPE_REG2, getPacketType(pkt))
        assertTrue(pkt.drop(2).all { it == 0x24.toByte() })
        assertTrue(isSrtlaReg2(pkt))
    }
}

// ── Rust protocol_tests.rs::test_create_keepalive_packet ─────────────────────
suite("createKeepalivePacket") {
    test("length, type, is keepalive, timestamp > 0") {
        val pkt = createKeepalivePacket()
        assertEquals(10, pkt.size)
        assertEquals(SRTLA_TYPE_KEEPALIVE, getPacketType(pkt))
        assertTrue(isSrtlaKeepalive(pkt))
        val ts = extractKeepaliveTimestamp(pkt)
        assertNotNull(ts)
        assertTrue(ts!! > 0)
    }
}

// ── Rust protocol_tests.rs::test_extract_keepalive_timestamp ─────────────────
suite("extractKeepaliveTimestamp") {
    test("known timestamp round-trips") {
        val testTs = 0x0102030405060708L
        val pkt = ByteArray(10)
        writeU16BE(pkt, 0, SRTLA_TYPE_KEEPALIVE)
        for (i in 0 until 8) {
            pkt[2 + i] = ((testTs ushr (56 - i * 8)) and 0xFF).toByte()
        }
        assertEquals(testTs, extractKeepaliveTimestamp(pkt))
    }
    test("wrong packet type → null") {
        val pkt = ByteArray(10)
        writeU16BE(pkt, 0, SRT_TYPE_ACK)
        assertNull(extractKeepaliveTimestamp(pkt))
    }
    test("5-byte buffer → null") {
        assertNull(extractKeepaliveTimestamp(ByteArray(5)))
    }
}

// ── Rust protocol_tests.rs::test_create_ack_packet ───────────────────────────
suite("createAckPacket") {
    test("length and type") {
        val acks = listOf(100L, 200L, 300L)
        val pkt = createAckPacket(acks)
        assertEquals(4 + 4 * acks.size, pkt.size)
        assertEquals(SRTLA_TYPE_ACK, getPacketType(pkt))
    }
}

// ── Rust protocol_tests.rs::test_create_ack_packet_with_4byte_header ─────────
suite("parseSrtlaAck with 4-byte header") {
    test("parse round-trips 3 ACKs") {
        val acks = listOf(100L, 200L, 300L)
        val pkt = ByteArray(4 + 4 * acks.size)
        writeU16BE(pkt, 0, SRTLA_TYPE_ACK)
        pkt[2] = 0x00; pkt[3] = 0x00
        for ((i, ack) in acks.withIndex()) writeU32BE(pkt, 4 + i * 4, ack)
        assertEquals(SRTLA_TYPE_ACK, getPacketType(pkt))
        assertEquals(listOf(100L, 200L, 300L), parseSrtlaAck(pkt))
    }
}

// ── Rust protocol_tests.rs::test_parse_srt_ack ───────────────────────────────
suite("parseSrtAck") {
    test("valid ACK → sequence 12345") {
        val buf = ByteArray(20)
        writeU16BE(buf, 0, SRT_TYPE_ACK)
        writeU32BE(buf, 16, 12345L)
        assertEquals(12345L, parseSrtAck(buf))
    }
    test("wrong type → null") {
        val buf = ByteArray(20)
        buf[0] = 0x90.toByte()
        assertNull(parseSrtAck(buf))
    }
    test("19-byte buffer → null") {
        assertNull(parseSrtAck(ByteArray(19)))
    }
}

// ── Rust protocol_tests.rs::test_parse_srt_nak_single ────────────────────────
suite("parseSrtNak — single") {
    test("single sequence 500") {
        val buf = ByteArray(8)
        writeU16BE(buf, 0, SRT_TYPE_NAK)
        writeU32BE(buf, 4, 500L)
        assertEquals(listOf(500L), parseSrtNak(buf))
    }
}

// ── Rust protocol_tests.rs::test_parse_srt_nak_range ─────────────────────────
suite("parseSrtNak — range") {
    test("range 100..103 expands to 4 entries") {
        val buf = ByteArray(12)
        writeU16BE(buf, 0, SRT_TYPE_NAK)
        // Range start: MSB set + 100
        writeU32BE(buf, 4, (100L or 0x8000_0000L))
        writeU32BE(buf, 8, 103L) // range end
        assertEquals(listOf(100L, 101L, 102L, 103L), parseSrtNak(buf))
    }
}

// ── Rust protocol_tests.rs::test_parse_srt_nak_mixed ─────────────────────────
suite("parseSrtNak — mixed single + range") {
    test("50 then range 100..102") {
        val buf = ByteArray(16)
        writeU16BE(buf, 0, SRT_TYPE_NAK)
        writeU32BE(buf, 4, 50L)                       // single
        writeU32BE(buf, 8, (100L or 0x8000_0000L))    // range start
        writeU32BE(buf, 12, 102L)                      // range end
        assertEquals(listOf(50L, 100L, 101L, 102L), parseSrtNak(buf))
    }
}

// ── Rust protocol_tests.rs::test_parse_srt_nak_invalid ───────────────────────
suite("parseSrtNak — invalid") {
    test("wrong type → empty") {
        val buf = ByteArray(8)
        writeU16BE(buf, 0, SRT_TYPE_ACK)
        assertTrue(parseSrtNak(buf).isEmpty())
    }
    test("3-byte buffer → empty") {
        assertTrue(parseSrtNak(byteArrayOf(0x80.toByte(), 0x03, 0x00)).isEmpty())
    }
}

// ── Rust protocol_tests.rs::test_parse_srtla_ack ─────────────────────────────
suite("parseSrtlaAck") {
    test("3 ACK values") {
        val acks = listOf(1000L, 2000L, 3000L)
        val pkt = ByteArray(4 + 4 * acks.size)
        writeU16BE(pkt, 0, SRTLA_TYPE_ACK); pkt[2] = 0; pkt[3] = 0
        for ((i, ack) in acks.withIndex()) writeU32BE(pkt, 4 + i * 4, ack)
        assertEquals(listOf(1000L, 2000L, 3000L), parseSrtlaAck(pkt))
    }
    test("empty ACK packet (4-byte header) → empty list") {
        val pkt = byteArrayOf(0x91.toByte(), 0x00, 0x00, 0x00)
        assertEquals(emptyList<Long>(), parseSrtlaAck(pkt))
    }
    test("wrong type → empty") {
        val pkt = ByteArray(16)
        pkt[0] = 0x80.toByte()
        assertTrue(parseSrtlaAck(pkt).isEmpty())
    }
    test("3-byte buffer → empty") {
        assertTrue(parseSrtlaAck(byteArrayOf(0x91.toByte(), 0x00, 0x00)).isEmpty())
    }
}

// ── Rust protocol_tests.rs::test_packet_type_validators ──────────────────────
suite("packet type validators") {
    test("REG1 is only REG1") {
        val pkt = createReg1Packet(ByteArray(SRTLA_ID_LEN) { 0x11 })
        assertTrue(isSrtlaReg1(pkt))
        assertFalse(isSrtlaReg2(pkt))
        assertFalse(isSrtlaReg3(pkt))
    }
    test("REG2 is only REG2") {
        val pkt = createReg2Packet(ByteArray(SRTLA_ID_LEN) { 0x22 })
        assertFalse(isSrtlaReg1(pkt))
        assertTrue(isSrtlaReg2(pkt))
        assertFalse(isSrtlaReg3(pkt))
    }
    test("REG3 is only REG3") {
        val pkt = byteArrayOf(
            ((SRTLA_TYPE_REG3 ushr 8) and 0xFF).toByte(),
            (SRTLA_TYPE_REG3 and 0xFF).toByte()
        )
        assertFalse(isSrtlaReg1(pkt))
        assertFalse(isSrtlaReg2(pkt))
        assertTrue(isSrtlaReg3(pkt))
    }
    test("keepalive is keepalive") {
        assertTrue(isSrtlaKeepalive(createKeepalivePacket()))
    }
    test("SRT ACK is SRT ACK") {
        val pkt = ByteArray(20)
        writeU16BE(pkt, 0, SRT_TYPE_ACK)
        assertTrue(isSrtAck(pkt))
    }
}

// ── Rust protocol_tests.rs::test_constants ───────────────────────────────────
suite("constants parity") {
    test("ID and REG lengths") {
        assertEquals(256, SRTLA_ID_LEN)
        assertEquals(2 + SRTLA_ID_LEN, SRTLA_TYPE_REG1_LEN)
        assertEquals(2 + SRTLA_ID_LEN, SRTLA_TYPE_REG2_LEN)
        assertEquals(2, SRTLA_TYPE_REG3_LEN)
    }
    test("window constant ordering") {
        assertTrue(WINDOW_MIN < WINDOW_DEF)
        assertTrue(WINDOW_DEF < WINDOW_MAX)
        assertTrue(WINDOW_INCR > 0)
        assertTrue(WINDOW_DECR > 0)
        assertTrue(WINDOW_MULT > 0)
    }
    test("SRTLA type values") {
        assertEquals(0x9000, SRTLA_TYPE_KEEPALIVE)
        assertEquals(0x9100, SRTLA_TYPE_ACK)
        assertEquals(0x9200, SRTLA_TYPE_REG1)
        assertEquals(0x9201, SRTLA_TYPE_REG2)
        assertEquals(0x9202, SRTLA_TYPE_REG3)
        assertEquals(0x9210, SRTLA_TYPE_REG_ERR)
        assertEquals(0x9211, SRTLA_TYPE_REG_NGP)
        assertEquals(0x9212, SRTLA_TYPE_REG_NAK)
    }
    test("SRT type values") {
        assertEquals(0x8000, SRT_TYPE_HANDSHAKE)
        assertEquals(0x8002, SRT_TYPE_ACK)
        assertEquals(0x8003, SRT_TYPE_NAK)
        assertEquals(0x8005, SRT_TYPE_SHUTDOWN)
        assertEquals(0x0000, SRT_TYPE_DATA)
    }
    test("keepalive ext constants") {
        assertEquals(0xc01f, SRTLA_KEEPALIVE_MAGIC)
        assertEquals(38, SRTLA_KEEPALIVE_EXT_LEN)
        assertEquals(0x0001, SRTLA_KEEPALIVE_EXT_VERSION)
    }
    test("misc constants") {
        assertEquals(1500, MTU)
        assertEquals(256, PKT_LOG_SIZE)
        assertEquals(1, WINDOW_MIN)
        assertEquals(20, WINDOW_DEF)
        assertEquals(60, WINDOW_MAX)
        assertEquals(1000, WINDOW_MULT)
        assertEquals(100, WINDOW_DECR)
        assertEquals(30, WINDOW_INCR)
    }
}

// ── Rust protocol/mod.rs inline tests (extended keepalive) ───────────────────
suite("extendedKeepalive") {
    test("roundtrip: build then parse") {
        val info = ConnectionInfo(
            connId = 42L,
            window = 25000,
            inFlight = 8,
            rttMs = 120L,
            nakCount = 5L,
            bitrateBytesSec = 2_500_000L,
        )
        val pkt = createKeepalivePacketExt(info)
        assertEquals(SRTLA_KEEPALIVE_EXT_LEN, pkt.size)
        assertEquals(SRTLA_TYPE_KEEPALIVE, getPacketType(pkt))
        assertNotNull(extractKeepaliveTimestamp(pkt)) // backwards compat
        val extracted = extractKeepaliveConnInfo(pkt)
        assertNotNull(extracted)
        assertEquals(info, extracted!!)
    }
    test("standard keepalive has no conn info") {
        val pkt = createKeepalivePacket()
        assertEquals(10, pkt.size)
        assertNotNull(extractKeepaliveTimestamp(pkt))
        assertNull(extractKeepaliveConnInfo(pkt))
    }
    test("ext keepalive backwards compat — timestamps within 1s") {
        val info = ConnectionInfo(1L, 20000, 5, 100L, 2L, 1_000_000L)
        val extPkt = createKeepalivePacketExt(info)
        val stdPkt = createKeepalivePacket()
        val tsExt = extractKeepaliveTimestamp(extPkt)!!
        val tsStd = extractKeepaliveTimestamp(stdPkt)!!
        assertTrue(kotlin.math.abs(tsExt - tsStd) < 1000L)
    }
    test("wrong magic → null") {
        val pkt = ByteArray(SRTLA_KEEPALIVE_EXT_LEN)
        writeU16BE(pkt, 0, SRTLA_TYPE_KEEPALIVE)
        writeU16BE(pkt, 10, 0xdead) // wrong magic
        assertNull(extractKeepaliveConnInfo(pkt))
    }
    test("wrong version → null") {
        val pkt = ByteArray(SRTLA_KEEPALIVE_EXT_LEN)
        writeU16BE(pkt, 0, SRTLA_TYPE_KEEPALIVE)
        writeU16BE(pkt, 10, SRTLA_KEEPALIVE_MAGIC)
        writeU16BE(pkt, 12, 0x9999) // wrong version
        assertNull(extractKeepaliveConnInfo(pkt))
    }
}

// ── Additional roundtrip tests (not in Rust sources, extra coverage) ──────────
suite("roundtrip — build→parse") {
    test("REG3 builder produces correct type") {
        val pkt = createReg3Packet()
        assertEquals(SRTLA_TYPE_REG3_LEN, pkt.size)
        assertEquals(SRTLA_TYPE_REG3, getPacketType(pkt))
        assertTrue(isSrtlaReg3(pkt))
    }
    test("ACK builder → parse roundtrip") {
        val seq = listOf(1L, 2L, 0xFFFFFFFFL)
        val pkt = createAckPacket(seq)
        assertEquals(seq, parseSrtlaAck(pkt))
    }
    test("NAK single max sequence") {
        val buf = ByteArray(8)
        writeU16BE(buf, 0, SRT_TYPE_NAK)
        writeU32BE(buf, 4, 0x7FFF_FFFFL) // max unsigned 31-bit
        assertEquals(listOf(0x7FFF_FFFFL), parseSrtNak(buf))
    }
    test("NAK range incomplete → truncates gracefully") {
        // Range start with no following end word
        val buf = ByteArray(8)
        writeU16BE(buf, 0, SRT_TYPE_NAK)
        writeU32BE(buf, 4, (100L or 0x8000_0000L)) // range start, but no end
        // i + 3 >= buf.len check fires → break, result empty
        assertEquals(emptyList<Long>(), parseSrtNak(buf))
    }
    test("parseSrtAck — max u32 value") {
        val buf = ByteArray(20)
        writeU16BE(buf, 0, SRT_TYPE_ACK)
        writeU32BE(buf, 16, 0xFFFFFFFFL)
        assertEquals(0xFFFFFFFFL, parseSrtAck(buf))
    }
    test("getSrtSequenceNumber — max valid (0x7FFF_FFFF)") {
        val buf = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(0x7FFF_FFFFL, getSrtSequenceNumber(buf))
    }
    test("extKeepalive conn_id large u32 survives roundtrip") {
        val info = ConnectionInfo(
            connId = 0xFFFFFFFFL,
            window = -1,        // i32 negative
            inFlight = Int.MIN_VALUE,
            rttMs = 0xFFFFFFFFL,
            nakCount = 0L,
            bitrateBytesSec = 0xFFFFFFFFL,
        )
        val pkt = createKeepalivePacketExt(info)
        val parsed = extractKeepaliveConnInfo(pkt)!!
        assertEquals(info, parsed)
    }
}

} // registerProtocolTests
