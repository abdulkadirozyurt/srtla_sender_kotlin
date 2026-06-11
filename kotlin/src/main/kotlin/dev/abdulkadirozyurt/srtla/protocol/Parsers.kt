// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/protocol/parsers.rs
//
// All multi-byte fields are big-endian (network byte order), matching the SRT/SRTLA wire format.
// Unsigned-32 Rust values are returned as Long (masked with 0xFFFFFFFFL).
// Sequence numbers are Long; packet types are Int (fits unsigned-16 safely).
package dev.abdulkadirozyurt.srtla.protocol

// ── Keepalive parsers ─────────────────────────────────────────────────────────

/**
 * Extract the 8-byte big-endian timestamp (milliseconds) from a KEEPALIVE packet.
 * Returns null if [buf] is shorter than 10 bytes or not a KEEPALIVE packet.
 * Mirrors Rust `extract_keepalive_timestamp` in src/protocol/parsers.rs.
 *
 * The timestamp is encoded at bytes 2..9 (inclusive).
 * Result is a Long holding the full unsigned-64 value; timestamps in the
 * near future fit comfortably in a positive Long (won't overflow until year ~2554).
 */
fun extractKeepaliveTimestamp(buf: ByteArray): Long? {
    if (buf.size < 10) return null
    if (getPacketType(buf) != SRTLA_TYPE_KEEPALIVE) return null
    var ts = 0L
    for (i in 0 until 8) {
        ts = (ts shl 8) or (buf[2 + i].toLong() and 0xFF)
    }
    return ts
}

/**
 * Extract [ConnectionInfo] from an extended KEEPALIVE packet (38 bytes).
 * Returns null if:
 *   - Packet is shorter than [SRTLA_KEEPALIVE_EXT_LEN] (38)
 *   - Not a KEEPALIVE packet type
 *   - Magic at bytes 10-11 ≠ [SRTLA_KEEPALIVE_MAGIC] (0xC01F)
 *   - Version at bytes 12-13 ≠ [SRTLA_KEEPALIVE_EXT_VERSION] (0x0001)
 * Mirrors Rust `extract_keepalive_conn_info` in src/protocol/parsers.rs.
 */
fun extractKeepaliveConnInfo(buf: ByteArray): ConnectionInfo? {
    if (buf.size < SRTLA_KEEPALIVE_EXT_LEN) return null
    if (getPacketType(buf) != SRTLA_TYPE_KEEPALIVE) return null

    val magic = ((buf[10].toInt() and 0xFF) shl 8) or (buf[11].toInt() and 0xFF)
    if (magic != SRTLA_KEEPALIVE_MAGIC) return null

    val version = ((buf[12].toInt() and 0xFF) shl 8) or (buf[13].toInt() and 0xFF)
    if (version != SRTLA_KEEPALIVE_EXT_VERSION) return null

    // u32 fields → Long (mask ensures no sign-extension artefacts)
    val connId          = readU32BE(buf, 14)
    val window          = readI32BE(buf, 18)
    val inFlight        = readI32BE(buf, 22)
    val rttMs           = readU32BE(buf, 26)
    val nakCount        = readU32BE(buf, 30)
    val bitrateBytesSec = readU32BE(buf, 34)

    return ConnectionInfo(
        connId          = connId,
        window          = window,
        inFlight        = inFlight,
        rttMs           = rttMs,
        nakCount        = nakCount,
        bitrateBytesSec = bitrateBytesSec,
    )
}

// ── SRT control parsers ───────────────────────────────────────────────────────

/**
 * Parse the acknowledged sequence number from an SRT ACK control packet.
 * The ACK sequence number is at bytes 16..19 (big-endian u32).
 * Returns null if [buf] is shorter than 20 bytes or not an SRT ACK packet.
 * Mirrors Rust `parse_srt_ack` in src/protocol/parsers.rs.
 *
 * Result is Long (unsigned u32 safe range 0..0xFFFF_FFFF).
 */
fun parseSrtAck(buf: ByteArray): Long? {
    if (buf.size < 20) return null
    if (getPacketType(buf) != SRT_TYPE_ACK) return null
    return readU32BE(buf, 16)
}

/**
 * Parse lost sequence numbers from an SRT NAK (loss report) control packet.
 * Returns an empty list on invalid input.
 * Mirrors Rust `parse_srt_nak` in src/protocol/parsers.rs.
 *
 * NAK payload starts at byte 4 and contains 32-bit big-endian words:
 *   - If MSB = 0 → single sequence number
 *   - If MSB = 1 → range start (mask off MSB); next word is range end (inclusive)
 * Output is capped at 1000 entries (mirrors Rust implementation).
 * Sequence numbers are Long (31-bit unsigned → fits in positive Long).
 */
fun parseSrtNak(buf: ByteArray): List<Long> {
    if (buf.size < 8) return emptyList()
    if (getPacketType(buf) != SRT_TYPE_NAK) return emptyList()

    val out = mutableListOf<Long>()
    var i = 4
    while (i + 3 < buf.size) {
        val raw = readU32BE(buf, i).toInt() // safe: we only inspect bits, not magnitude
        i += 4
        if ((raw and -0x80000000) != 0) {
            // Range: strip MSB to get start, read next word for end
            val start = (raw and 0x7FFF_FFFF).toLong()
            if (i + 3 >= buf.size) break
            val end = readU32BE(buf, i).toInt().toLong() and 0x7FFF_FFFFL
            i += 4
            var seq = start
            while (seq <= end && out.size < 1000) {
                out += seq
                seq++
            }
        } else {
            out += raw.toLong() and 0xFFFFFFFFL
        }
    }
    return out
}

/**
 * Parse acknowledged sequence numbers from an SRTLA ACK packet.
 * Returns an empty list on invalid input.
 * Mirrors Rust `parse_srtla_ack` in src/protocol/parsers.rs.
 *
 * The SRTLA ACK has a 4-byte header (type=0x9100, 2 padding bytes), followed
 * by zero or more big-endian u32 sequence numbers.
 * Minimum valid packet: 8 bytes (4 header + at least one 4-byte ACK value or
 * an empty ACK with just the header — but the while condition i+3 < size means
 * nothing is read for a 4-byte packet, matching Rust behaviour).
 */
fun parseSrtlaAck(buf: ByteArray): List<Long> {
    if (buf.size < 8) return emptyList()
    if (getPacketType(buf) != SRTLA_TYPE_ACK) return emptyList()

    val out = mutableListOf<Long>()
    // Skip first 4 bytes (type + 2 padding), matching Rust: `let mut i = 4usize`
    var i = 4
    while (i + 3 < buf.size) {
        out += readU32BE(buf, i)
        i += 4
    }
    return out
}

// ── Internal read helpers (package-private) ───────────────────────────────────

/** Read a big-endian unsigned 32-bit integer as Long from [buf] at [offset]. */
internal fun readU32BE(buf: ByteArray, offset: Int): Long =
    ((buf[offset    ].toLong() and 0xFF) shl 24) or
    ((buf[offset + 1].toLong() and 0xFF) shl 16) or
    ((buf[offset + 2].toLong() and 0xFF) shl  8) or
     (buf[offset + 3].toLong() and 0xFF)

/** Read a big-endian signed 32-bit integer (i32) from [buf] at [offset]. */
internal fun readI32BE(buf: ByteArray, offset: Int): Int =
    ((buf[offset    ].toInt() and 0xFF) shl 24) or
    ((buf[offset + 1].toInt() and 0xFF) shl 16) or
    ((buf[offset + 2].toInt() and 0xFF) shl  8) or
     (buf[offset + 3].toInt() and 0xFF)
