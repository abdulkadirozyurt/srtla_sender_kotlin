// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-protocol/src/types.rs
package dev.abdulkadirozyurt.srtla.protocol

/**
 * Connection info payload embedded in extended KEEPALIVE packets.
 * u32 fields are stored as Long; i32 fields stay Int.
 */
data class ConnectionInfo(
    val connId: Long,
    val window: Int,
    val inFlight: Int,
    val rttMs: Long,
    val nakCount: Long,
    val bitrateBytesSec: Long,
)

/**
 * TSBPD latency declared in an SRT handshake's HSREQ/HSRSP extension block.
 * Both halves are milliseconds, each null when the peer did not set the
 * matching TSBPD flag (the 16 bits are then meaningless, not zero).
 */
data class SrtHandshakeLatency(
    /** True for SRT_CMD_HSRSP (negotiated answer), false for HSREQ (proposal). */
    val isResponse: Boolean,
    /** Receive delay of the block's sender: the deadline routed packets must beat. */
    val rcvMs: Int?,
    /** Delay the block's sender expects its own peer to receive with. */
    val sndMs: Int?,
)

/** 2-byte big-endian packet type, or null for a buffer shorter than 2 bytes. */
fun getPacketType(buf: ByteArray, len: Int = buf.size): Int? {
    if (len < 2) return null
    return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
}

/**
 * SRT data-packet sequence number from the first 4 bytes, or null when the
 * buffer is too short or the MSB (control bit) is set. The result is the
 * non-negative 31-bit sequence.
 */
fun getSrtSequenceNumber(buf: ByteArray, len: Int = buf.size): Int? {
    if (len < 4) return null
    val sn = readI32BE(buf, 0)
    return if (sn and Int.MIN_VALUE == 0) sn else null
}

/**
 * Whether [buf] is an SRT data packet flagged as a retransmission.
 *
 * The second header word of a data packet is `PP(2)|O(1)|KK(2)|R(1)|msgno(26)`;
 * the R bit (0x04 in byte 4) marks a packet the SRT sender re-sends after a NAK.
 * Retransmits fill an existing hole in the receiver buffer, so one that rides a
 * slow path arrives too late to matter.
 */
fun isSrtDataRetransmit(buf: ByteArray, len: Int = buf.size): Boolean =
    len >= 8 && (buf[0].toInt() and 0x80) == 0 && (buf[4].toInt() and 0x04) != 0

/**
 * Set the R bit on an SRT data packet in place, marking it a retransmission.
 *
 * Used for the duplicate probes sent on links held out of the payload rotation.
 * The receiver dedups them by sequence either way, but a non-retransmit feeds an
 * SRTLA-patched receiver's reorder-hold estimator; a probe from a slow link would
 * pin that hold near its ceiling and slow loss recovery on the healthy links.
 * Safe on encrypted packets: the receiver excludes this bit from the AES-GCM tag.
 * No-op on anything that is not an SRT data packet.
 */
fun setSrtDataRetransmit(buf: ByteArray, len: Int = buf.size) {
    if (len >= 8 && (buf[0].toInt() and 0x80) == 0) {
        buf[4] = (buf[4].toInt() or 0x04).toByte()
    }
}

fun isSrtlaReg1(buf: ByteArray): Boolean =
    buf.size == SRTLA_TYPE_REG1_LEN && getPacketType(buf) == SRTLA_TYPE_REG1

fun isSrtlaReg2(buf: ByteArray): Boolean =
    buf.size == SRTLA_TYPE_REG2_LEN && getPacketType(buf) == SRTLA_TYPE_REG2

fun isSrtlaReg3(buf: ByteArray): Boolean =
    buf.size == SRTLA_TYPE_REG3_LEN && getPacketType(buf) == SRTLA_TYPE_REG3

fun isSrtlaKeepalive(buf: ByteArray): Boolean = getPacketType(buf) == SRTLA_TYPE_KEEPALIVE

fun isSrtAck(buf: ByteArray): Boolean = getPacketType(buf) == SRT_TYPE_ACK
