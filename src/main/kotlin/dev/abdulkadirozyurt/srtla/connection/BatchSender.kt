// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/connection/batch_send.rs
//
// Packet batching inspired by Moblin: buffer data packets per link and flush
// on a size threshold or a 15 ms timer. The queue is pure state; draining it
// performs no I/O. The shell transmits the drained datagrams.
//
// JVM DEVIATION: there is no sendmmsg on the JVM, so the shell sends a drained
// batch one datagram at a time. The queue still matters: getScore() counts
// queued packets as in-flight, which is what bounds per-link queue depth.
//
// Adaptive regimes pick the size threshold from the link's observed bitrate:
//   LowActivity (<= 500 kbps): 4, Normal: 16, HighLoad (> 5 Mbps): 32.
package dev.abdulkadirozyurt.srtla.connection

import dev.abdulkadirozyurt.srtla.core.satSub
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.batch")

/** Max datagrams per drained batch. */
const val BATCH_SEND_SIZE: Int = 32

/** Bitrate above which a connection is high-load. */
const val HIGH_LOAD_THRESHOLD_BPS: Double = 5_000_000.0
/** Bitrate at or below which a connection is low-activity. */
const val LOW_ACTIVITY_THRESHOLD_BPS: Double = 500_000.0

private const val BATCH_SIZE_LOW_ACTIVITY: Int = 4
private const val BATCH_SIZE_NORMAL: Int = 16
private const val BATCH_SIZE_HIGH_LOAD: Int = 32

/** Default size threshold (Normal regime). */
const val BATCH_SIZE_THRESHOLD: Int = BATCH_SIZE_NORMAL

/** Maximum time in ms between flushes (Moblin uses 15ms). */
const val FLUSH_INTERVAL_MS: Long = 15L

/** Adaptive batch-size regime, driven by observed per-link bitrate. */
enum class BatchRegime(val wireName: String, val batchSize: Int) {
    LOW_ACTIVITY("low_activity", BATCH_SIZE_LOW_ACTIVITY),
    NORMAL("normal", BATCH_SIZE_NORMAL),
    HIGH_LOAD("high_load", BATCH_SIZE_HIGH_LOAD);

    fun asStr(): String = wireName

    companion object {
        fun fromBps(bps: Double): BatchRegime = when {
            bps > HIGH_LOAD_THRESHOLD_BPS -> HIGH_LOAD
            bps <= LOW_ACTIVITY_THRESHOLD_BPS -> LOW_ACTIVITY
            else -> NORMAL
        }
    }
}

/**
 * One drained datagram: bytes, tracked sequence (null for untracked packets such
 * as probes) and the time it was queued.
 */
class DrainedPacket(val data: ByteArray, val seq: Int?, val queueTimeMs: Long)

/** Queues packets and drains them for the shell to transmit. */
class BatchSender {
    private val queue = ArrayList<ByteArray>(BATCH_SIZE_HIGH_LOAD)
    private val sequences = ArrayList<Int?>(BATCH_SIZE_HIGH_LOAD)
    private val queueTimes = ArrayList<Long>(BATCH_SIZE_HIGH_LOAD)

    /** nowMs of the last drain; drives the 15 ms time-flush window. */
    private var lastFlushMs: Long = 0L

    var regime: BatchRegime = BatchRegime.NORMAL
        private set

    /** Queue a copy of [data]. Returns true when the size threshold is reached. */
    fun queuePacket(data: ByteArray, len: Int, seq: Int?, currentTimeMs: Long): Boolean {
        queue.add(data.copyOf(len))
        sequences.add(seq)
        queueTimes.add(currentTimeMs)
        return queue.size >= regime.batchSize
    }

    fun queuePacket(data: ByteArray, seq: Int?, currentTimeMs: Long): Boolean =
        queuePacket(data, data.size, seq, currentTimeMs)

    /** Update the regime; called from housekeeping each tick. */
    fun setRegime(regime: BatchRegime) {
        this.regime = regime
    }

    /** True once the queue has held packets for at least [FLUSH_INTERVAL_MS]. */
    fun needsTimeFlush(nowMs: Long): Boolean =
        queue.isNotEmpty() && nowMs.satSub(lastFlushMs) >= FLUSH_INTERVAL_MS

    fun hasQueuedPackets(): Boolean = queue.isNotEmpty()

    /** Packets queued but not yet flushed; counted as in-flight by getScore(). */
    fun queuedCount(): Int = queue.size

    /** Empty the queue and re-arm the flush window at [nowMs]. Pure: no I/O. */
    fun drain(nowMs: Long): List<DrainedPacket> {
        lastFlushMs = nowMs
        if (queue.isEmpty()) return emptyList()
        val out = ArrayList<DrainedPacket>(queue.size)
        for (i in queue.indices) out.add(DrainedPacket(queue[i], sequences[i], queueTimes[i]))
        if (out.size > 1) log.finest { "Batch drain: ${out.size} packets ready to send" }
        queue.clear()
        sequences.clear()
        queueTimes.clear()
        return out
    }

    fun reset() {
        queue.clear()
        sequences.clear()
        queueTimes.clear()
        lastFlushMs = 0L
    }
}
