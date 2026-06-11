// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/sequence.rs
//
// Zero-allocation sequence tracking using a fixed-size ring buffer.
// SEQ_TRACKING_SIZE = 16384 (power of 2, covers ~5s at 3000 pkt/s).
// Replaces the Rust SEQ_TRACKING_SIZE approach; PKT_LOG_SIZE (256) is the
// per-connection in-flight log size — different concern.
//
// JVM note: Rust uses a Box<[Entry; 16384]> on heap; we use a plain array.
package dev.abdulkadirozyurt.srtla.sender

// src/sender/sequence.rs
const val SEQ_TRACKING_SIZE: Int = 16384  // power of 2
private const val SEQ_TRACKING_MASK: Int = SEQ_TRACKING_SIZE - 1
const val SEQUENCE_TRACKING_MAX_AGE_MS: Long = 5_000L

/**
 * Single entry in the sequence tracking ring buffer.
 * connId = 0 means empty/invalid.
 */
private data class SequenceEntry(
    val connId: Long,
    val timestampMs: Long,
    val seq: Long,
)

private val EMPTY_ENTRY = SequenceEntry(0L, 0L, 0L)

/**
 * Zero-allocation sequence tracker using a fixed-size ring buffer.
 * Mirrors Rust `struct SequenceTracker` in src/sender/sequence.rs.
 *
 * Index = seq & SEQ_TRACKING_MASK (O(1) insert and lookup).
 * Collisions handled by storing actual seq and checking on lookup.
 * Stale entries detected by timestamp.
 */
class SequenceTracker {
    private val entries: Array<SequenceEntry> = Array(SEQ_TRACKING_SIZE) { EMPTY_ENTRY }

    /** Insert a sequence number → connection-ID mapping. O(1). */
    fun insert(seq: Long, connId: Long, timestampMs: Long) {
        val idx = (seq.toInt()) and SEQ_TRACKING_MASK
        entries[idx] = SequenceEntry(connId, timestampMs, seq)
    }

    /**
     * Look up a sequence number. Returns connId or null if empty/expired/collision.
     * O(1).
     */
    fun get(seq: Long, currentTimeMs: Long): Long? {
        val idx = (seq.toInt()) and SEQ_TRACKING_MASK
        val e = entries[idx]
        if (e.connId == 0L) return null
        if (e.seq != seq) return null
        if ((currentTimeMs - e.timestampMs) > SEQUENCE_TRACKING_MAX_AGE_MS) return null
        return e.connId
    }

    /**
     * Remove all entries for a specific connection (O(n); only called on connection removal).
     */
    fun removeConnection(connId: Long) {
        for (i in entries.indices) {
            if (entries[i].connId == connId) entries[i] = EMPTY_ENTRY
        }
    }
}
