// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/protocol/types.rs
//
// Rust uses plain constants + helper functions rather than an enum for packet
// type dispatch (enum variants would require exhaustive matching and the
// protocol adds new types via receiver-side code). We mirror that flat
// constant approach: getPacketType() returns the raw Int value and callers
// compare against the SRTLA_TYPE_* / SRT_TYPE_* constants.
//
// The ConnectionInfo data class mirrors the Rust struct ConnectionInfo.
// Field types:
//   conn_id (u32)             -> Long  (mask 0xFFFFFFFFL on decode)
//   window  (i32)             -> Int
//   in_flight (i32)           -> Int
//   rtt_ms (u32)              -> Long  (mask 0xFFFFFFFFL on decode)
//   nak_count (u32)           -> Long
//   bitrate_bytes_per_sec(u32)-> Long
package dev.abdulkadirozyurt.srtla.protocol

/**
 * Connection info payload embedded in extended KEEPALIVE packets.
 * Mirrors Rust `struct ConnectionInfo` in src/protocol/types.rs.
 *
 * All originally-unsigned u32 fields are stored as Long to avoid signed
 * overflow; i32 fields remain Int (same width, same sign semantics).
 */
data class ConnectionInfo(
    /** u32 — connection identifier, stored as Long. */
    val connId: Long,
    /** i32 — current congestion window. */
    val window: Int,
    /** i32 — packets currently in-flight. */
    val inFlight: Int,
    /** u32 — round-trip time in milliseconds, stored as Long. */
    val rttMs: Long,
    /** u32 — cumulative NAK count, stored as Long. */
    val nakCount: Long,
    /** u32 — current bitrate in bytes per second, stored as Long. */
    val bitrateBytesSec: Long,
)

// ── Packet type extraction ────────────────────────────────────────────────────

/**
 * Read the 2-byte big-endian packet type from [buf].
 * Returns null if [buf] has fewer than 2 bytes.
 * Mirrors Rust `get_packet_type` in src/protocol/types.rs.
 *
 * The result is always in [0, 0xFFFF] — Int is safe as an unsigned-16 carrier.
 */
fun getPacketType(buf: ByteArray): Int? {
    if (buf.size < 2) return null
    return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
}

// ── Sequence number extraction ────────────────────────────────────────────────

/**
 * Read SRT data-packet sequence number from the first 4 bytes of [buf].
 * Returns null if the buffer is shorter than 4 bytes, or if the MSB (control
 * bit) is set (indicating a control packet, not a data packet).
 * Mirrors Rust `get_srt_sequence_number` in src/protocol/types.rs.
 *
 * The returned value is a Long holding the unsigned 31-bit sequence number
 * (0 .. 0x7FFF_FFFF).
 */
fun getSrtSequenceNumber(buf: ByteArray): Long? {
    if (buf.size < 4) return null
    val sn = ((buf[0].toInt() and 0xFF) shl 24) or
              ((buf[1].toInt() and 0xFF) shl 16) or
              ((buf[2].toInt() and 0xFF) shl 8)  or
               (buf[3].toInt() and 0xFF)
    // Bit 31 set → control packet → return null (mirrors Rust `& 0x8000_0000 == 0`)
    return if ((sn and -0x80000000) == 0) sn.toLong() else null
}

// ── Packet-type validator helpers ─────────────────────────────────────────────
// Mirror Rust helper functions in src/protocol/types.rs

/** Returns true iff [buf] is a valid SRTLA REG1 packet. */
fun isSrtlaReg1(buf: ByteArray): Boolean =
    buf.size == SRTLA_TYPE_REG1_LEN && getPacketType(buf) == SRTLA_TYPE_REG1

/** Returns true iff [buf] is a valid SRTLA REG2 packet. */
fun isSrtlaReg2(buf: ByteArray): Boolean =
    buf.size == SRTLA_TYPE_REG2_LEN && getPacketType(buf) == SRTLA_TYPE_REG2

/** Returns true iff [buf] is a valid SRTLA REG3 packet. */
fun isSrtlaReg3(buf: ByteArray): Boolean =
    buf.size == SRTLA_TYPE_REG3_LEN && getPacketType(buf) == SRTLA_TYPE_REG3

/** Returns true iff [buf] starts with the SRTLA KEEPALIVE type. */
fun isSrtlaKeepalive(buf: ByteArray): Boolean =
    getPacketType(buf) == SRTLA_TYPE_KEEPALIVE

/** Returns true iff [buf] starts with the SRT ACK type. */
fun isSrtAck(buf: ByteArray): Boolean =
    getPacketType(buf) == SRT_TYPE_ACK
