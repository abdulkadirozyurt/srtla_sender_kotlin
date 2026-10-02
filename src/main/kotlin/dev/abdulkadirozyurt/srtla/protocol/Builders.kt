// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-protocol/src/builders.rs
//
// Pure builders: the keepalive timestamp is injected by the caller, this
// package reads no clock.
package dev.abdulkadirozyurt.srtla.protocol

/** REG1: 2-byte type (0x9200) + 256-byte id. */
fun createReg1Packet(id: ByteArray): ByteArray {
    require(id.size == SRTLA_ID_LEN) { "id must be $SRTLA_ID_LEN bytes, got ${id.size}" }
    val pkt = ByteArray(SRTLA_TYPE_REG1_LEN)
    writeU16BE(pkt, 0, SRTLA_TYPE_REG1)
    id.copyInto(pkt, destinationOffset = 2)
    return pkt
}

/** REG2: 2-byte type (0x9201) + 256-byte id. */
fun createReg2Packet(id: ByteArray): ByteArray {
    require(id.size == SRTLA_ID_LEN) { "id must be $SRTLA_ID_LEN bytes, got ${id.size}" }
    val pkt = ByteArray(SRTLA_TYPE_REG2_LEN)
    writeU16BE(pkt, 0, SRTLA_TYPE_REG2)
    id.copyInto(pkt, destinationOffset = 2)
    return pkt
}

/** REG3: 2-byte type only. Used by test receivers. */
fun createReg3Packet(): ByteArray {
    val pkt = ByteArray(SRTLA_TYPE_REG3_LEN)
    writeU16BE(pkt, 0, SRTLA_TYPE_REG3)
    return pkt
}

/**
 * Standard 10-byte KEEPALIVE. [now] is the monotonic-ms timestamp the receiver
 * echoes back so the sender can measure RTT.
 */
fun createKeepalivePacket(now: Long): ByteArray {
    val pkt = ByteArray(10)
    writeU16BE(pkt, 0, SRTLA_TYPE_KEEPALIVE)
    writeU64BE(pkt, 2, now)
    return pkt
}

/**
 * Extended 38-byte KEEPALIVE carrying [ConnectionInfo]:
 * type(2) ts(8) magic(2) version(2) connId(4) window(4) inFlight(4) rttMs(4)
 * nakCount(4) bitrateBytesSec(4). Receivers that read only the first 10 bytes
 * still get a valid standard keepalive.
 */
fun createKeepalivePacketExt(info: ConnectionInfo, now: Long): ByteArray {
    val pkt = ByteArray(SRTLA_KEEPALIVE_EXT_LEN)
    writeU16BE(pkt, 0, SRTLA_TYPE_KEEPALIVE)
    writeU64BE(pkt, 2, now)
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

/** SRTLA ACK: 4-byte header (type + 2 zero bytes) + 4 bytes per sequence. */
fun createAckPacket(acks: IntArray): ByteArray {
    val pkt = ByteArray(4 + 4 * acks.size)
    writeU16BE(pkt, 0, SRTLA_TYPE_ACK)
    for ((i, ack) in acks.withIndex()) writeI32BE(pkt, 4 + i * 4, ack)
    return pkt
}

// ── Write helpers ─────────────────────────────────────────────────────────────

fun writeU16BE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 1] = (value and 0xFF).toByte()
}

fun writeU32BE(buf: ByteArray, offset: Int, value: Long) {
    buf[offset] = ((value ushr 24) and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 3] = (value and 0xFF).toByte()
}

fun writeI32BE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = ((value ushr 24) and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 3] = (value and 0xFF).toByte()
}

fun writeU64BE(buf: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) {
        buf[offset + i] = ((value ushr (56 - 8 * i)) and 0xFF).toByte()
    }
}
