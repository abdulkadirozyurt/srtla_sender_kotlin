// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/seq.rs
//
// Serial-number arithmetic for SRT sequence numbers (RFC 1982 style, mod 2^31).
//
// SRT sequence numbers are 31 bits wide: they count up to 0x7fff_ffff, wrap
// back to 0, and start from a randomized value, so a live stream crosses the
// wrap at an arbitrary moment. Comparing them as plain ints is wrong exactly
// once per cycle: a post-wrap sequence (small) sorts below a pre-wrap one
// (huge), so every fresh ACK looks like a duplicate.
//
// The fix is the one SRT itself uses: compare the distance between two
// sequences inside the 31-bit space and call the shorter direction the
// ordering. Two sequences are comparable while they are less than 2^30 apart;
// a wider distance is ambiguous and reads as "before", i.e. stale.
package dev.abdulkadirozyurt.srtla.core

/** Number of distinct SRT sequence numbers (2^31), as a Long to avoid sign issues. */
private const val SEQ_SPACE: Long = 0x8000_0000L

/** Half the sequence space: the largest distance with an unambiguous direction. */
private const val SEQ_HALF: Long = SEQ_SPACE / 2

/** The 31 significant bits of an SRT sequence number. */
const val SEQ_MASK: Int = 0x7fff_ffff

/**
 * Sentinel for `SrtlaConnection.highestAckedSeq` meaning "no cumulative ACK
 * seen yet". It sits outside the 31-bit space, so no normalized sequence can
 * collide with it; code must test for it explicitly.
 */
const val NO_ACK_YET: Int = Int.MIN_VALUE

/** Reduce a wire word to the 31-bit sequence space (clears a corrupt MSB). */
fun seqNormalize(seq: Int): Int = seq and SEQ_MASK

/**
 * Signed distance `a - b` within the 31-bit space, in [-2^30, 2^30).
 * Positive means `a` is ahead of `b`. Callers must handle [NO_ACK_YET] first.
 */
fun seqDiff(a: Int, b: Int): Int {
    val d = ((a.toLong() and 0xffff_ffffL) - (b.toLong() and 0xffff_ffffL)) and SEQ_MASK.toLong()
    return if (d >= SEQ_HALF) (d - SEQ_SPACE).toInt() else d.toInt()
}

/** True when `a` is strictly ahead of `b` in serial order (wrap-aware `a > b`). */
fun seqAfter(a: Int, b: Int): Boolean = seqDiff(a, b) > 0

/** The sequence following `seq`, wrapping 0x7fff_ffff back to 0. */
fun seqNext(seq: Int): Int = (seq + 1) and SEQ_MASK
