// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/client_dedup.rs
//
// One forward to the local SRT client per distinct SRT ACK or NAK.
//
// SRTLA receivers send SRT ACKs (and some also NAKs) down every uplink, so one
// control packet can arrive once per uplink. libsrt answers every full ACK with
// an ACKACK and retransmits for every NAK it reads, so relaying each copy spends
// uplink capacity on extra ACKACKs and repeat retransmits.
//
// Packets are keyed on their full bytes, SRT header timestamp included: copies
// are byte-identical, a fresh packet carries a new timestamp. Only the relay is
// deduplicated; link accounting still runs on every copy.
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.core.satSub

/** How long a forwarded ACK suppresses byte-identical copies (downlink skew). */
const val ACK_DEDUP_WINDOW_MS: Long = 1000L

/** Distinct ACKs remembered; overflow evicts the oldest (fails open). */
internal const val ACK_RING_SIZE: Int = 512

/**
 * How long a forwarded NAK suppresses byte-identical copies. Kept well under the
 * 100 ms period at which Moblin and srtla_rec re-send an identical NAK, which
 * must still reach the client.
 */
const val NAK_DEDUP_WINDOW_MS: Long = 50L

/** Distinct NAKs remembered, apart from the ACK ring so ACKs cannot evict them. */
internal const val NAK_RING_SIZE: Int = 64

private class Ring(size: Int, private val windowMs: Long) {
    private val hashes = LongArray(size)
    private val at = LongArray(size)
    private val used = BooleanArray(size)
    private var next = 0

    fun shouldForward(packet: ByteArray, len: Int, nowMs: Long): Boolean {
        val hash = hash64(packet, len)
        for (i in hashes.indices) {
            if (used[i] && hashes[i] == hash && nowMs.satSub(at[i]) < windowMs) return false
        }
        hashes[next] = hash
        at[next] = nowMs
        used[next] = true
        next = (next + 1) % hashes.size
        return true
    }
}

/** 64-bit FNV-1a over the bytes, mixed with the length. */
private fun hash64(data: ByteArray, len: Int): Long {
    var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (i in 0 until len) {
        h = h xor (data[i].toLong() and 0xFF)
        h *= 0x100000001b3L
    }
    return h xor len.toLong()
}

/** Recently forwarded ACK and NAK packets, shared by every uplink of the bond. */
class ClientDedup {
    private val acks = Ring(ACK_RING_SIZE, ACK_DEDUP_WINDOW_MS)
    private val naks = Ring(NAK_RING_SIZE, NAK_DEDUP_WINDOW_MS)

    /** Whether this ACK should be forwarded; records it when true. */
    fun shouldForwardAck(packet: ByteArray, len: Int, nowMs: Long): Boolean = acks.shouldForward(packet, len, nowMs)

    fun shouldForwardAck(packet: ByteArray, nowMs: Long): Boolean = shouldForwardAck(packet, packet.size, nowMs)

    /** Whether this NAK should be forwarded; records it when true. */
    fun shouldForwardNak(packet: ByteArray, len: Int, nowMs: Long): Boolean = naks.shouldForward(packet, len, nowMs)

    fun shouldForwardNak(packet: ByteArray, nowMs: Long): Boolean = shouldForwardNak(packet, packet.size, nowMs)
}
