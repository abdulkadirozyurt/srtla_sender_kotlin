// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/sequence.rs
//
// Zero-allocation sequence → connection tracking in a fixed ring indexed by
// `seq & MASK`. Collisions are detected by storing the sequence; stale entries
// by timestamp. Old entries are simply overwritten, so no cleanup pass exists.
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.core.satSub

/** Ring size (power of 2): ~5 s at 3000 packets/s. */
const val SEQ_TRACKING_SIZE: Int = 16384
private const val SEQ_TRACKING_MASK: Int = SEQ_TRACKING_SIZE - 1

/** Maximum age of a valid entry. */
const val SEQUENCE_TRACKING_MAX_AGE_MS: Long = 5000L

class SequenceTracker {
    /** connId per slot; 0 = empty. */
    private val connIds = LongArray(SEQ_TRACKING_SIZE)
    private val timestamps = LongArray(SEQ_TRACKING_SIZE)
    private val seqs = IntArray(SEQ_TRACKING_SIZE)

    /** O(1) insert; never allocates. */
    fun insert(seq: Int, connId: Long, timestampMs: Long) {
        val idx = seq and SEQ_TRACKING_MASK
        connIds[idx] = connId
        timestamps[idx] = timestampMs
        seqs[idx] = seq
    }

    /** connId that sent [seq], or null when empty, collided or expired. */
    fun get(seq: Int, currentTimeMs: Long): Long? {
        val idx = seq and SEQ_TRACKING_MASK
        val id = connIds[idx]
        if (id == 0L || seqs[idx] != seq) return null
        if (currentTimeMs.satSub(timestamps[idx]) > SEQUENCE_TRACKING_MAX_AGE_MS) return null
        return id
    }

    /** Drop every entry owned by [connId]. O(n); only on removal/recovery. */
    fun removeConnection(connId: Long) {
        for (i in 0 until SEQ_TRACKING_SIZE) {
            if (connIds[i] == connId) {
                connIds[i] = 0L
                timestamps[i] = 0L
                seqs[i] = 0
            }
        }
    }
}
