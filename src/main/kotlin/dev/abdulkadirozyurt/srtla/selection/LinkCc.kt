// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/selection/link_cc.rs
//
// Per-link congestion-control soft cap. A small per-connection state machine
// produces `target_bps`, a soft cap the Enhanced scheduler steers by (soft-cap
// multiplier and BDP in-flight cap). The sustained `loss_degraded` latch feeds
// the routing loss gate; the instantaneous BackingOff state does not.
//
// States: Bootstrap (no RTT yet), Climbing (additive increase: Normal 2%,
// HAI 6%, FastRecovery 4%), Holding (RTT inflating), BackingOff (loss we
// caused: -15%, floored at delivered throughput), Drain (RTT >= 2x floor:
// one-shot -25%).
//
// Only back off for loss you caused: a load gate on entry, a delivered-rate
// floor on depth (target_bps steers, it does not pace, so a cap below proven
// delivery never self-corrects), and an efficacy test on duration (a ~39% cut
// that does not move the loss marks it uncongestive until a re-test).
package dev.abdulkadirozyurt.srtla.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.satSub

private const val LOSS_WINDOW_MS: Long = 1_000L
private const val LOSS_BACKOFF_PERMILLE: Int = 5
private const val BACKOFF_PERMILLE: Int = 850
/** Delivered throughput (permille of target) above which loss is attributed to us. */
internal const val BACKOFF_MIN_LOAD_PERMILLE: Int = 300
internal const val BACKOFF_EFFICACY_TICKS: Int = 3
private const val BACKOFF_EFFICACY_IMPROVEMENT_PERMILLE: Int = 800
internal const val LOSS_UNCONGESTIVE_RETEST_TICKS: Int = 30
private const val AI_STEP_PERMILLE: Int = 20
private const val HAI_STEP_PERMILLE: Int = 60
private const val FAST_RECOVERY_STEP_PERMILLE: Int = 40
internal const val FAST_RECOVERY_TICKS: Int = 5
private const val DRAIN_RTT_INFLATION: Double = 2.0
private const val DRAIN_PERMILLE: Int = 750
private const val HAI_VARIANCE_FRACTION: Double = 0.10

/** Assumed SRT payload size for converting byte counters into packet counts. */
const val ASSUMED_SRT_PAYLOAD_BYTES: Long = 1316L

private const val RTT_HOLD_FACTOR: Double = 1.5
internal const val MIN_TARGET_BPS: Long = 100_000L
internal const val MAX_TARGET_BPS: Long = 200_000_000L
internal const val INITIAL_TARGET_BPS: Long = 1_000_000L
private const val LOSS_EWMA_TAU_MS: Double = 2_000.0
internal const val LOSS_DEGRADE_ENTER: Double = 0.55
internal const val LOSS_DEGRADE_CLEAR: Double = 0.25
internal const val LOSS_DEGRADE_SUSTAIN_MS: Long = 4_000L
/** Window over which the CC's minimum RTT is tracked. */
internal const val CC_RTT_MIN_WINDOW_MS: Long = 30_000L
/** One throughput sample may not exceed this factor times the current estimate. */
internal const val CC_OUTLIER_FACTOR: Double = 4.0

enum class CcState(val wireName: String) {
    BOOTSTRAP("bootstrap"),
    CLIMBING("climbing"),
    HOLDING("holding"),
    BACKING_OFF("backing_off"),
    DRAIN("drain");

    fun asStr(): String = wireName
}

enum class ClimbMode(val wireName: String) {
    NORMAL("normal"),
    HAI("hai"),
    FAST_RECOVERY("fast_recovery");

    fun asStr(): String = wireName
}

private class LossSample(val tsMs: Long, val lost: Long, val sent: Long)

private const val U32_MAX: Long = 0xFFFF_FFFFL

private fun u32SatAdd(a: Long, b: Long): Long = minOf(a + b, U32_MAX)

/** Per-connection CC state. */
class LinkCongestionState {
    var state: CcState = CcState.BOOTSTRAP
    var climbMode: ClimbMode = ClimbMode.NORMAL
    var targetBps: Long = MIN_TARGET_BPS
    /** Age-bucketed EWMA of RTT (ms). */
    internal var rttEwmaMs: Double = 0.0
    /** Variance proxy: 1:3 weighted moving average of |sample - prev ewma|. */
    internal var rttVarMs: Double = 0.0
    /** Windowed minimum RTT (CC_RTT_MIN_WINDOW_MS). */
    internal var rttMinMs: Double = Double.POSITIVE_INFINITY
    internal var rttMinStampMs: Long = 0L
    internal var lastRttUpdateMs: Long = 0L
    private val lossSamples = ArrayDeque<LossSample>()
    private var windowLost: Long = 0L
    private var windowSent: Long = 0L
    internal var fastRecoveryTicks: Int = 0
    private var prevBytesSentTotal: Long = 0L
    private var prevNakTotal: Int = 0
    private var trafficBaselineSet: Boolean = false
    internal var lossEwma: Double = 0.0
    private var lossEwmaLastMs: Long = 0L
    private var lossHighSinceMs: Long = 0L
    internal var lossDegraded: Boolean = false
    private var backoffTicks: Int = 0
    private var backoffEntryLossPm: Int = 0
    internal var lossUncongestive: Boolean = false
    private var uncongestiveTicks: Int = 0

    /**
     * Feed an RTT sample. Age-bucketed EWMA weights (new:old): >=2s reset,
     * >=1s 1:1, >=500ms 1:4, >=250ms 1:8, else 1:16. The first sample snaps.
     */
    fun recordRtt(rttMs: Double, nowMs: Long) {
        if (!rttMs.isFinite() || rttMs <= 0.0) return
        val ageMs = nowMs.satSub(lastRttUpdateMs)
        if (rttEwmaMs == 0.0 || ageMs >= 2_000L) {
            rttEwmaMs = rttMs
            rttVarMs = 0.0
            lastRttUpdateMs = nowMs
            updateRttMin(rttMs, nowMs)
            return
        }
        val wOld = when {
            ageMs >= 1_000L -> 1.0
            ageMs >= 500L -> 4.0
            ageMs >= 250L -> 8.0
            else -> 16.0
        }
        val wNew = 1.0
        val prev = rttEwmaMs
        rttEwmaMs = (rttMs * wNew + prev * wOld) / (wNew + wOld)
        val dev = Math.abs(rttMs - prev)
        rttVarMs = (dev * 1.0 + rttVarMs * 3.0) / 4.0
        updateRttMin(rttMs, nowMs)
        lastRttUpdateMs = nowMs
    }

    /** Windowed min: adopt a lower sample, or reset once the held min ages out. */
    private fun updateRttMin(rttMs: Double, nowMs: Long) {
        val stale = nowMs.satSub(rttMinStampMs) > CC_RTT_MIN_WINDOW_MS
        if (!rttMinMs.isFinite() || rttMs < rttMinMs || stale) {
            rttMinMs = rttMs
            rttMinStampMs = nowMs
        }
    }

    /**
     * Feed cumulative (bytes sent, NAK total) counters; forwards per-tick deltas
     * to [recordLoss]. The first call only stashes a baseline.
     */
    fun observeTraffic(bytesSentTotal: Long, nakTotal: Int, nowMs: Long) {
        if (!trafficBaselineSet) {
            prevBytesSentTotal = bytesSentTotal
            prevNakTotal = nakTotal
            trafficBaselineSet = true
            return
        }
        val deltaBytes = bytesSentTotal.satSub(prevBytesSentTotal)
        val deltaNak = maxOf(nakTotal.toLong() - prevNakTotal.toLong(), 0L)
        prevBytesSentTotal = bytesSentTotal
        prevNakTotal = nakTotal
        if (deltaBytes == 0L && deltaNak == 0L) return
        var sentPkts = minOf(deltaBytes / ASSUMED_SRT_PAYLOAD_BYTES, U32_MAX)
        val lostPkts = minOf(deltaNak, U32_MAX)
        // NAKs on a quiet link: keep the permille ratio bounded.
        if (lostPkts > 0 && sentPkts < 1) sentPkts = 1
        recordLoss(sentPkts, lostPkts, nowMs)
    }

    /** Feed a (sent, lost) sample into the 1 s sliding window. */
    fun recordLoss(sent: Long, lost: Long, nowMs: Long) {
        lossSamples.addLast(LossSample(nowMs, lost, sent))
        windowSent = u32SatAdd(windowSent, sent)
        windowLost = u32SatAdd(windowLost, lost)
        evictExpired(nowMs)
    }

    private fun evictExpired(nowMs: Long) {
        val cutoff = nowMs.satSub(LOSS_WINDOW_MS)
        while (true) {
            val front = lossSamples.firstOrNull() ?: break
            if (front.tsMs < cutoff) {
                windowSent = windowSent.satSub(front.sent)
                windowLost = windowLost.satSub(front.lost)
                lossSamples.removeFirst()
            } else {
                break
            }
        }
    }

    /** Loss permille over the window. */
    fun lossPermille(): Int {
        if (windowSent == 0L) return 0
        val permille = windowLost * 1_000L / windowSent
        return minOf(permille, 1_000_000L).toInt()
    }

    /**
     * Judge whether the loss-driven backoff achieves anything and latch
     * lossUncongestive when it demonstrably does not. Runs before the state
     * transition, so `state` is still last tick's state. The verdict expires on a
     * timer only: clearing it on RTT inflation re-opened the backoff path on every
     * inflated tick and ratcheted the cap to the floor.
     */
    private fun updateBackoffEfficacy(lossHigh: Boolean, lossPm: Int) {
        if (!lossHigh) {
            backoffTicks = 0
            backoffEntryLossPm = 0
            lossUncongestive = false
            uncongestiveTicks = 0
            return
        }
        if (lossUncongestive) {
            uncongestiveTicks++
            if (uncongestiveTicks >= LOSS_UNCONGESTIVE_RETEST_TICKS) {
                lossUncongestive = false
                uncongestiveTicks = 0
                backoffTicks = 0
                backoffEntryLossPm = lossPm
            }
            return
        }
        if (state != CcState.BACKING_OFF) {
            backoffTicks = 0
            backoffEntryLossPm = lossPm
            return
        }
        backoffTicks++
        if (backoffTicks < BACKOFF_EFFICACY_TICKS) return
        val improved = lossPm.toLong() * 1_000L < backoffEntryLossPm.toLong() * BACKOFF_EFFICACY_IMPROVEMENT_PERMILLE
        if (improved) {
            backoffTicks = 0
            backoffEntryLossPm = lossPm
        } else {
            lossUncongestive = true
            uncongestiveTicks = 0
        }
    }

    /** Recompute the state and targetBps; called once per housekeeping tick. */
    fun tick(observedBps: Long, nowMs: Long) {
        evictExpired(nowMs)
        if (!rttEwmaMs.isFinite() || rttEwmaMs == 0.0) {
            state = CcState.BOOTSTRAP
            climbMode = ClimbMode.NORMAL
            targetBps = MIN_TARGET_BPS
            return
        }

        val lossPm = lossPermille()
        updateLossEwma(lossPm, nowMs)
        val rttInflation = if (rttMinMs.isFinite() && rttMinMs > 0.0) rttEwmaMs / rttMinMs else 1.0

        // Outlier rejection, floored at the initial estimate so the first seed
        // is not pinned to the minimum target.
        val baseline = maxOf(targetBps, INITIAL_TARGET_BPS).toDouble()
        val saneObserved = minOf(observedBps.toDouble(), CC_OUTLIER_FACTOR * baseline).toLong()

        if (targetBps == MIN_TARGET_BPS) {
            val seed = maxOf(saneObserved, INITIAL_TARGET_BPS)
            targetBps = seed.coerceIn(MIN_TARGET_BPS, MAX_TARGET_BPS)
        }

        // Is this loss ours? Loaded enough to fill the bottleneck, and the
        // backoff has to be working.
        // Both sides stay far below Long range (observed <= 4 x 200 Mbps).
        val loaded = saneObserved * 1_000L >= targetBps * BACKOFF_MIN_LOAD_PERMILLE
        val lossHigh = lossPm > LOSS_BACKOFF_PERMILLE
        updateBackoffEfficacy(lossHigh, lossPm)

        val prevState = state
        val nextState = when {
            lossHigh && loaded && !lossUncongestive -> CcState.BACKING_OFF
            rttInflation >= DRAIN_RTT_INFLATION -> CcState.DRAIN
            rttInflation > RTT_HOLD_FACTOR -> CcState.HOLDING
            // Loss on an under-driven link is wire loss: let it climb.
            else -> CcState.CLIMBING
        }

        // Arm fast recovery on leaving BackingOff/Drain; the budget is consumed
        // after pickClimbMode reads it, so the arming tick counts.
        if ((prevState == CcState.BACKING_OFF || prevState == CcState.DRAIN) && nextState == CcState.CLIMBING) {
            fastRecoveryTicks = FAST_RECOVERY_TICKS
        }
        state = nextState

        val prev = targetBps.toDouble()
        val next = when (nextState) {
            CcState.BOOTSTRAP -> {
                climbMode = ClimbMode.NORMAL
                prev
            }
            CcState.CLIMBING -> {
                val mode = pickClimbMode()
                climbMode = mode
                val stepPm = when (mode) {
                    ClimbMode.NORMAL -> AI_STEP_PERMILLE
                    ClimbMode.HAI -> HAI_STEP_PERMILLE
                    ClimbMode.FAST_RECOVERY -> FAST_RECOVERY_STEP_PERMILLE
                }
                val step = (prev * stepPm) / 1000.0
                // Never grow past 2x measured traffic; hold when idle.
                val measuredCap = saneObserved.toDouble() * 2.0
                if (saneObserved > 0) {
                    maxOf(prev, MIN_TARGET_BPS.toDouble()) + maxOf(minOf(step, measuredCap - prev), 0.0)
                } else {
                    prev
                }
            }
            CcState.HOLDING -> {
                climbMode = ClimbMode.NORMAL
                prev
            }
            CcState.BACKING_OFF -> {
                climbMode = ClimbMode.NORMAL
                // Multiplicative decrease, never below proven delivery.
                val decreased = (prev * BACKOFF_PERMILLE) / 1000.0
                val deliveredFloor = minOf(saneObserved.toDouble(), prev)
                maxOf(decreased, deliveredFloor)
            }
            CcState.DRAIN -> {
                climbMode = ClimbMode.NORMAL
                // One-shot: cut only on entry, hold while drained.
                if (prevState != CcState.DRAIN) (prev * DRAIN_PERMILLE) / 1000.0 else prev
            }
        }
        targetBps = next.toLong().coerceIn(MIN_TARGET_BPS, MAX_TARGET_BPS)

        if (nextState == CcState.CLIMBING) {
            if (fastRecoveryTicks > 0) fastRecoveryTicks--
        } else {
            fastRecoveryTicks = 0
        }
    }

    /**
     * Fold the windowed loss into the time-decayed EWMA (tau 2 s) and update the
     * latched verdict (enter > 0.55 sustained 4 s, clear < 0.25).
     */
    private fun updateLossEwma(lossPm: Int, nowMs: Long) {
        val inst = (lossPm.toDouble() / 1_000.0).coerceIn(0.0, 1.0)
        if (lossEwmaLastMs == 0L) {
            lossEwma = inst
        } else {
            val dt = nowMs.satSub(lossEwmaLastMs).toDouble()
            val alpha = 1.0 - Math.exp(-dt / LOSS_EWMA_TAU_MS)
            lossEwma += (inst - lossEwma) * alpha
        }
        lossEwmaLastMs = nowMs
        if (lossEwma > LOSS_DEGRADE_ENTER) {
            if (lossHighSinceMs == 0L) {
                lossHighSinceMs = nowMs
            } else if (nowMs.satSub(lossHighSinceMs) >= LOSS_DEGRADE_SUSTAIN_MS) {
                lossDegraded = true
            }
        } else {
            lossHighSinceMs = 0L
            if (lossEwma < LOSS_DEGRADE_CLEAR) lossDegraded = false
        }
    }

    /** FastRecovery inside its window, else Hai when RTT is stable, else Normal. */
    private fun pickClimbMode(): ClimbMode {
        if (fastRecoveryTicks > 0) return ClimbMode.FAST_RECOVERY
        if (rttEwmaMs > 0.0 && rttVarMs <= rttEwmaMs * HAI_VARIANCE_FRACTION) return ClimbMode.HAI
        return ClimbMode.NORMAL
    }

    fun snapshot(): LinkCcSnapshot = LinkCcSnapshot(
        state = state,
        climbMode = climbMode,
        targetBps = targetBps,
        rttEwmaMs = rttEwmaMs,
        rttVarMs = rttVarMs,
        rttMinMs = if (rttMinMs.isFinite()) rttMinMs else 0.0,
        lossPermille = lossPermille(),
        lossEwma = lossEwma,
        lossDegraded = lossDegraded,
    )
}

data class LinkCcSnapshot(
    val state: CcState,
    val climbMode: ClimbMode,
    val targetBps: Long,
    val rttEwmaMs: Double,
    val rttVarMs: Double,
    val rttMinMs: Double,
    val lossPermille: Int,
    /** Time-decayed loss fraction (0..1). */
    val lossEwma: Double,
    /** Latched sustained-loss verdict; demotes, never removes. */
    val lossDegraded: Boolean,
)

/** One [LinkCongestionState] per connection, driven by the housekeeping tick. */
class LinkCcController {
    private val perConn = HashMap<Long, LinkCongestionState>()

    /** Update every link's CC state; returns a snapshot per connId. */
    fun tickAll(connections: List<SrtlaConnection>, nowMs: Long): Map<Long, LinkCcSnapshot> {
        val alive = HashMap<Long, LinkCcSnapshot>(connections.size)
        for (conn in connections) {
            val entry = perConn.getOrPut(conn.connId) { LinkCongestionState() }
            val rttMs = conn.getSmoothRttMs()
            if (rttMs > 0.0) entry.recordRtt(rttMs, nowMs)
            entry.observeTraffic(conn.bitrate.bytesSentTotal, conn.totalNakCount(), nowMs)
            val observedBps = maxOf(conn.bitrate.currentBitrateBps, 0.0).toLong()
            entry.tick(observedBps, nowMs)
            alive[conn.connId] = entry.snapshot()
        }
        perConn.keys.retainAll(alive.keys)
        return alive
    }
}
