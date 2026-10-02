// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-protocol/src/parsers.rs
//
// All multi-byte fields are big-endian. Every parser is total over arbitrary
// input: these bytes come off the network.
package dev.abdulkadirozyurt.srtla.protocol

/** Big-endian 32-bit word at [off], or null when it does not fit in [len]. */
private fun be32(buf: ByteArray, off: Int, len: Int): Int? {
    if (off < 0 || off + 4 > len) return null
    return readI32BE(buf, off)
}

/**
 * Extract the TSBPD latency an SRT conclusion handshake declares.
 *
 * Every SRT handshake crosses this proxy in the clear, and an SRT_CMD_HSRSP
 * block from the far end says exactly how long it will hold a packet before
 * delivering it: the deadline any link has to beat.
 *
 * Layout (big-endian words): 16-byte control header, 48-byte handshake body,
 * then extension blocks. Each block is one spec word (command in the high 16
 * bits, body length in words in the low 16) followed by that body. HSREQ/HSRSP
 * bodies are `version, flags, latency`; latency packs the receive delay high and
 * the send delay low.
 *
 * Returns null for anything that is not an HSv5 conclusion handshake carrying
 * such a block, including induction packets, rejections, HSv4 and truncated or
 * self-inconsistent input.
 */
fun parseSrtHandshakeLatency(buf: ByteArray, len: Int = buf.size): SrtHandshakeLatency? {
    if (getPacketType(buf, len) != SRT_TYPE_HANDSHAKE) return null
    val body = SRT_CONTROL_HEADER_LEN
    if (len - body < SRT_HANDSHAKE_CIF_LEN) return null

    if (be32(buf, body, len) != SRT_HS_VERSION_5) return null
    // Word 5 is the request type; only the conclusion phase carries extensions.
    if (be32(buf, body + 20, len) != SRT_HS_REQTYPE_CONCLUSION) return null
    // Word 1 is `encryption field | extension field`; HSREQ lives in the low half.
    val ext = be32(buf, body + 4, len) ?: return null
    if ((ext and 0xffff) and SRT_HS_EXT_FLAG_HSREQ == 0) return null

    // Walk the blocks. Each step consumes at least the 4-byte spec word, so the
    // loop terminates on any input.
    var pos = body + SRT_HANDSHAKE_CIF_LEN
    while (len - pos >= 4) {
        val spec = be32(buf, pos, len) ?: return null
        val cmd = (spec ushr 16) and 0xffff
        val blockLen = (spec and 0xffff) * 4
        val blockStart = pos + 4
        // A length running past the packet is truncated or lying.
        if (blockStart + blockLen > len) return null

        if (cmd == SRT_HS_EXT_CMD_HSREQ || cmd == SRT_HS_EXT_CMD_HSRSP) {
            if (blockLen < SRT_HS_EXT_HSREQ_WORDS * 4) return null
            val flags = be32(buf, blockStart + 4, len) ?: return null
            val latency = be32(buf, blockStart + 8, len) ?: return null
            return SrtHandshakeLatency(
                isResponse = cmd == SRT_HS_EXT_CMD_HSRSP,
                rcvMs = if (flags and SRT_HS_OPT_TSBPDRCV != 0) (latency ushr 16) and 0xffff else null,
                sndMs = if (flags and SRT_HS_OPT_TSBPDSND != 0) latency and 0xffff else null,
            )
        }
        pos = blockStart + blockLen
    }
    return null
}

/** The 8-byte timestamp of a KEEPALIVE packet, or null. */
fun extractKeepaliveTimestamp(buf: ByteArray, len: Int = buf.size): Long? {
    if (len < 10) return null
    if (getPacketType(buf, len) != SRTLA_TYPE_KEEPALIVE) return null
    var ts = 0L
    for (i in 0 until 8) {
        ts = (ts shl 8) or (buf[2 + i].toLong() and 0xFF)
    }
    return ts
}

/**
 * [ConnectionInfo] from an extended keepalive, or null when the packet is too
 * short, not a keepalive, or carries the wrong magic or version.
 */
fun extractKeepaliveConnInfo(buf: ByteArray, len: Int = buf.size): ConnectionInfo? {
    if (len < SRTLA_KEEPALIVE_EXT_LEN) return null
    if (getPacketType(buf, len) != SRTLA_TYPE_KEEPALIVE) return null
    val magic = ((buf[10].toInt() and 0xFF) shl 8) or (buf[11].toInt() and 0xFF)
    if (magic != SRTLA_KEEPALIVE_MAGIC) return null
    val version = ((buf[12].toInt() and 0xFF) shl 8) or (buf[13].toInt() and 0xFF)
    if (version != SRTLA_KEEPALIVE_EXT_VERSION) return null
    return ConnectionInfo(
        connId = readU32BE(buf, 14),
        window = readI32BE(buf, 18),
        inFlight = readI32BE(buf, 22),
        rttMs = readU32BE(buf, 26),
        nakCount = readU32BE(buf, 30),
        bitrateBytesSec = readU32BE(buf, 34),
    )
}

/** The acknowledged sequence of an SRT ACK (bytes 16..19, raw word), or null. */
fun parseSrtAck(buf: ByteArray, len: Int = buf.size): Int? {
    if (len < 20) return null
    if (getPacketType(buf, len) != SRT_TYPE_ACK) return null
    return readI32BE(buf, 16)
}

/** Loss-list word flag that opens a range; must be clear on a bare sequence. */
private const val SRT_NAK_RANGE_FLAG: Int = Int.MIN_VALUE

/**
 * Widest loss list one NAK expands into. Every id becomes a retransmission
 * upstream, so an unbounded expansion is an amplification vector.
 */
const val SRT_NAK_MAX_LOSS_IDS: Int = 1000

/**
 * Parse the loss list of an SRT NAK into individual sequence numbers.
 *
 * The loss list is the control packet's CIF, so it starts after the full 16-byte
 * control header, not after the type word. Reading from offset 4 turns the
 * header's timestamp and socket id into phantom loss reports.
 *
 * A word with the MSB set opens an inclusive range ending at the next word. The
 * end word must have its MSB clear and must not sort below the masked start;
 * otherwise the range yields nothing and parsing resumes after it. Expansion
 * saturates silently at [SRT_NAK_MAX_LOSS_IDS].
 */
fun parseSrtNak(buf: ByteArray, len: Int = buf.size): IntArray {
    if (len < SRT_CONTROL_HEADER_LEN + 4) return IntArray(0)
    if (getPacketType(buf, len) != SRT_TYPE_NAK) return IntArray(0)
    val out = IntList()
    var i = SRT_CONTROL_HEADER_LEN
    while (i + 3 < len) {
        val id = readI32BE(buf, i)
        i += 4
        if (id and SRT_NAK_RANGE_FLAG == 0) {
            out.add(id)
            continue
        }
        val start = id and SRT_NAK_RANGE_FLAG.inv()
        if (i + 3 >= len) break
        val end = readI32BE(buf, i)
        i += 4
        // The end is a plain sequence: MSB set or end < start is corrupt/hostile.
        // Both values are then non-negative, so the walk is wrap-safe.
        if (end and SRT_NAK_RANGE_FLAG != 0 || end < start) continue
        var seq = start
        while (true) {
            if (out.size >= SRT_NAK_MAX_LOSS_IDS) break
            out.add(seq)
            if (seq == end) break
            seq++
        }
    }
    return out.toArray()
}

/**
 * Sequences of an SRTLA ACK: a 4-byte header (type + 2 padding bytes) followed
 * by big-endian u32 words. Matches the C receiver, which skips acks[0].
 */
fun parseSrtlaAck(buf: ByteArray, len: Int = buf.size): IntArray {
    if (len < 8) return IntArray(0)
    if (getPacketType(buf, len) != SRTLA_TYPE_ACK) return IntArray(0)
    val n = (len - 4) / 4
    val out = IntArray(n)
    for (k in 0 until n) out[k] = readI32BE(buf, 4 + k * 4)
    return out
}

// ── Read helpers ──────────────────────────────────────────────────────────────

fun readU32BE(buf: ByteArray, offset: Int): Long =
    ((buf[offset].toLong() and 0xFF) shl 24) or
        ((buf[offset + 1].toLong() and 0xFF) shl 16) or
        ((buf[offset + 2].toLong() and 0xFF) shl 8) or
        (buf[offset + 3].toLong() and 0xFF)

fun readI32BE(buf: ByteArray, offset: Int): Int =
    ((buf[offset].toInt() and 0xFF) shl 24) or
        ((buf[offset + 1].toInt() and 0xFF) shl 16) or
        ((buf[offset + 2].toInt() and 0xFF) shl 8) or
        (buf[offset + 3].toInt() and 0xFF)

/** Growable primitive int list; avoids boxing on the receive hot path. */
internal class IntList(capacity: Int = 4) {
    private var data = IntArray(capacity)
    var size: Int = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): IntArray = data.copyOf(size)
}
