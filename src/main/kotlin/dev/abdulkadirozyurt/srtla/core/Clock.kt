// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/utils.rs
//
// Process-wide monotonic clock. Every timeout, RTT sample and congestion
// deadline in this codebase is a difference between two nowMs() reads, so the
// clock must never move backwards. System.currentTimeMillis() can step back on
// an NTP correction; System.nanoTime() cannot. We anchor once and report
// baseMs + elapsed, so values keep an epoch-scale magnitude while only the
// differences carry meaning.
//
// The sans-IO core never calls nowMs() itself: callers inject `now` into every
// method that needs time. Only the shell (event loop, housekeeping, stats) reads
// the clock.
package dev.abdulkadirozyurt.srtla.core

private object MonotonicClock {
    val baseMs: Long = System.currentTimeMillis()
    val anchorNs: Long = System.nanoTime()
}

/**
 * Monotonic time in milliseconds, anchored to an epoch-scale base.
 * Guaranteed non-decreasing within a process. Use it only for elapsed time
 * between two reads, never as a timestamp shared with another machine.
 */
fun nowMs(): Long = MonotonicClock.baseMs + (System.nanoTime() - MonotonicClock.anchorNs) / 1_000_000L

// ── Unsigned-style arithmetic helpers ────────────────────────────────────────
// Rust uses u64 timestamps with saturating_sub; Kotlin Long is signed, so
// "a - b clamped at zero" is spelled out once here.

/** `max(this - other, 0)`, the u64 `saturating_sub` used for elapsed times. */
fun Long.satSub(other: Long): Long = if (this > other) this - other else 0L

/** `this + other`, saturating at [Long.MAX_VALUE]. */
fun Long.satAdd(other: Long): Long {
    val r = this + other
    return if (other > 0 && r < this) Long.MAX_VALUE else r
}

/** `this * other` for non-negative operands, saturating at [Long.MAX_VALUE]. */
fun Long.satMul(other: Long): Long {
    if (this == 0L || other == 0L) return 0L
    return if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other
}

/** `this + other` on Int, saturating at the Int range. */
fun Int.satAdd(other: Int): Int =
    (this.toLong() + other.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

/** `this - other` on Int, saturating at the Int range. */
fun Int.satSubI(other: Int): Int =
    (this.toLong() - other.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

/** `this * other` on Int, saturating at the Int range. */
fun Int.satMul(other: Int): Int =
    (this.toLong() * other.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
