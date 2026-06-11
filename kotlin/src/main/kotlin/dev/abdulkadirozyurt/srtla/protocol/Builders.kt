// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/protocol/builders.rs
//
// All multi-byte fields are written big-endian.
// Timestamp: System.currentTimeMillis() — equivalent to Rust utils::now_ms().
// Kotlin lacks fixed-size arrays on return; we use ByteArray (heap-allocated).
// The Rust SmallVec<u8, 64> for create_ack_packet → ByteArray (no alloc hint needed).
package dev.abdulkadirozyurt.srtla.protocol

// ── Registration builders ─────────────────────────────────────────────────────

/**
 * Build a REG1 packet: 2-byte type (0x9200) + 256-byte registration ID.
 * Total length = [SRTLA_TYPE_REG1_LEN] = 258.
 * Mirrors Rust `create_reg1_packet` in src/protocol/builders.rs.
 *
 * @param id Must be exactly [SRTLA_ID_LEN] (256) bytes.
 */
fun createReg1Packet(id: ByteArray): ByteArray {
    require(id.size == SRTLA_ID_LEN) { "id must be $SRTLA_ID_LEN bytes, got ${id.size}" }
    val pkt = ByteArray(SRTLA_TYPE_REG1_LEN)
    writeU16BE(pkt, 0, SRTLA_TYPE_REG1)
    id.copyInto(pkt, destinationOffset = 2)
    return pkt
}

/**
 * Build a REG2 packet: 2-byte type (0x9201) + 256-byte registration ID.
 * Total length = [SRTLA_TYPE_REG2_LEN] = 258.
 * Mirrors Rust `create_reg2_packet` in src/protocol/builders.rs.
 *
 * @param id Must be exactly [SRTLA_ID_LEN] (256) bytes.
 */
fun createReg2Packet(id: ByteArray): ByteArray {
    require(id.size == SRTLA_ID_LEN) { "id must be $SRTLA_ID_LEN bytes, got ${id.size}" }
    val pkt = ByteArray(SRTLA_TYPE_REG2_LEN)
    writeU16BE(pkt, 0, SRTLA_TYPE_REG2)
    id.copyInto(pkt, destinationOffset = 2)
    return pkt
}

/**
 * Build a REG3 packet: 2-byte type (0x9202) only.
 * Total length = [SRTLA_TYPE_REG3_LEN] = 2.
 * Rust source has no standalone create_reg3_packet but the packet format is
 * documented in constants.rs; we add it for completeness.
 */
fun createReg3Packet(): ByteArray {
    val pkt = ByteArray(SRTLA_TYPE_REG3_LEN)
    writeU16BE(pkt, 0, SRTLA_TYPE_REG3)
    return pkt
}

// ── Keepalive builders ────────────────────────────────────────────────────────

/**
 * Build a standard KEEPALIVE packet (10 bytes):
 *   Bytes 0-1:  Type (0x9000)
 *   Bytes 2-9:  Timestamp — current time in milliseconds (big-endian u64)
 * Mirrors Rust `create_keepalive_packet` in src/protocol/builders.rs.
 */
fun createKeepalivePacket(): ByteArray {
    val pkt = ByteArray(10)
    writeU16BE(pkt, 0, SRTLA_TYPE_KEEPALIVE)
    writeU64BE(pkt, 2, System.currentTimeMillis())
    return pkt
}

/**
 * Build an extended KEEPALIVE packet (38 bytes) embedding [ConnectionInfo].
 *
 * Layout (mirrors Rust `create_keepalive_packet_ext` in src/protocol/builders.rs):
 *   Bytes  0- 1: Type (0x9000)
 *   Bytes  2- 9: Timestamp ms (u64 big-endian)
 *   Bytes 10-11: Magic (0xC01F)
 *   Bytes 12-13: Version (0x0001)
 *   Bytes 14-17: connId (u32)
 *   Bytes 18-21: window (i32)
 *   Bytes 22-25: inFlight (i32)
 *   Bytes 26-29: rttMs (u32)
 *   Bytes 30-33: nakCount (u32)
 *   Bytes 34-37: bitrateBytesSec (u32)
 *
 * Backwards-compatible: receivers that only read the first 10 bytes still get
 * a valid standard keepalive timestamp.
 */
fun createKeepalivePacketExt(info: ConnectionInfo): ByteArray {
    val pkt = ByteArray(SRTLA_KEEPALIVE_EXT_LEN)

    // Standard header
    writeU16BE(pkt,  0, SRTLA_TYPE_KEEPALIVE)
    writeU64BE(pkt,  2, System.currentTimeMillis())

    // Extended section
    writeU16BE(pkt, 10, SRTLA_KEEPALIVE_MAGIC)
    writeU16BE(pkt, 12, SRTLA_KEEPALIVE_EXT_VERSION)
    writeU32BE(pkt, 14, info.connId)
    writeI32BE(pkt, 18, info.window)
    writeI32BE(pkt, 22, info.inFlight)
    writeU32BE(pkt, 26, info.rttMs)
    writeU32BE(pkt, 30, info.nakCount)
    writeU32BE(pkt, 34, info.bitrateBytesSec)

    return pkt
}

// ── ACK builder ───────────────────────────────────────────────────────────────

/**
 * Build an SRTLA ACK packet for [acks] sequence numbers.
 * Layout: 4-byte header (type=0x9100 + 2 zero-padding bytes) + 4 bytes per ACK.
 * Mirrors Rust `create_ack_packet` in src/protocol/builders.rs.
 *
 * Sequence numbers are passed as Long; only the lower 32 bits are written.
 */
fun createAckPacket(acks: List<Long>): ByteArray {
    val pkt = ByteArray(4 + 4 * acks.size)
    writeU16BE(pkt, 0, SRTLA_TYPE_ACK)
    pkt[2] = 0x00 // padding (matching receiver behaviour)
    pkt[3] = 0x00
    for ((i, ack) in acks.withIndex()) {
        writeU32BE(pkt, 4 + i * 4, ack)
    }
    return pkt
}

// ── Internal write helpers (package-private) ──────────────────────────────────

internal fun writeU16BE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset    ] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 1] = (value and 0xFF).toByte()
}

internal fun writeU32BE(buf: ByteArray, offset: Int, value: Long) {
    buf[offset    ] = ((value ushr 24) and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr  8) and 0xFF).toByte()
    buf[offset + 3] = (value and 0xFF).toByte()
}

internal fun writeI32BE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset    ] = ((value ushr 24) and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr  8) and 0xFF).toByte()
    buf[offset + 3] = (value and 0xFF).toByte()
}

internal fun writeU64BE(buf: ByteArray, offset: Int, value: Long) {
    buf[offset    ] = ((value ushr 56) and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 48) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 40) and 0xFF).toByte()
    buf[offset + 3] = ((value ushr 32) and 0xFF).toByte()
    buf[offset + 4] = ((value ushr 24) and 0xFF).toByte()
    buf[offset + 5] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 6] = ((value ushr  8) and 0xFF).toByte()
    buf[offset + 7] = (value and 0xFF).toByte()
}
