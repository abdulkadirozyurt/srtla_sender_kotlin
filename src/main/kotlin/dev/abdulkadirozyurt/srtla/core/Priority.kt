// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/priority.rs
//
// Critical-packet priority sidecar state. An encoder that knows it is about to
// push a keyframe opens a short "critical window" over a dedicated loopback UDP
// socket; during the window the scheduler routes data packets to the
// highest-quality link. Loopback UDP shares the stack path with the SRT data,
// so priority events stay ordered against the packets they describe.
//
// Wire format, one request per datagram, 5 bytes:
//   byte 0     : 0xC1 magic ("Critical v1")
//   bytes 1..5 : u32 big-endian window length in ms
// The deadline is now + window_ms; overlapping windows only extend it.
package dev.abdulkadirozyurt.srtla.core

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import java.util.concurrent.atomic.AtomicLong

/** Magic byte identifying a priority-sidecar v1 datagram. */
const val PRIORITY_PROTO_MAGIC: Int = 0xc1

/** Datagram length: [magic u8][window_ms u32 big-endian]. */
const val PRIORITY_DATAGRAM_LEN: Int = 5

/**
 * Shared critical-window deadline plus observability counters. Thread-safe:
 * the sidecar listener writes, the event loop reads.
 */
class CriticalWindow {
    private val deadlineMs = AtomicLong(0L)
    private val windowsReceived = AtomicLong(0L)
    private val malformedDatagrams = AtomicLong(0L)

    /** Push the deadline forward (fetch-max); a back-dated window never shortens it. */
    fun extendTo(deadline: Long) {
        deadlineMs.accumulateAndGet(deadline) { a, b -> maxOf(a, b) }
        windowsReceived.incrementAndGet()
    }

    /** Scheduler hot-path check. */
    fun isCriticalNow(nowMs: Long): Boolean = deadlineMs.get() > nowMs

    fun windowsReceived(): Long = windowsReceived.get()

    fun malformedDatagrams(): Long = malformedDatagrams.get()

    fun recordMalformed() {
        malformedDatagrams.incrementAndGet()
    }
}

/**
 * Highest-quality connected, schedulable link the scheduler has not held out of
 * the rotation; null hands the decision back to normal selection.
 *
 * Gated links are skipped deliberately: a held-out link carries no unique
 * payload, accrues no NAKs, and its quality multiplier decays to a pristine 1.0
 * while the link carrying the stream absorbs every NAK. Without the gate check
 * the excluded link would win this comparison systematically.
 */
fun selectBestQualityIdx(conns: List<SrtlaConnection>, nowMs: Long): Int? {
    var bestIdx: Int? = null
    var bestQuality = Double.NEGATIVE_INFINITY
    for ((i, c) in conns.withIndex()) {
        if (!c.connected || !c.isSchedulable() || c.isTimedOut(nowMs) || c.isStallGated() || c.isQualityExcluded()) {
            continue
        }
        val q = c.qualityCache.multiplier
        if (q > bestQuality) {
            bestQuality = q
            bestIdx = i
        }
    }
    return bestIdx
}
