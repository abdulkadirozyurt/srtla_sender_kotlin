// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/selection/classifier.rs
//
// Weak-link classifier, run once per housekeeping tick (~1 Hz).
//
// 1. Establish a delay budget: the peer's declared receive buffer doubled onto
//    the round-trip scale, or else max(longest_rtt * 3, 500ms) capped at 5000ms.
// 2. Three tiers: best = 40%, safe = 50%, max = 60% of the budget
//    (capped at 2.5s / 2.5s / 5s).
// 3. Pick the tightest tier where >85% of throughput fits, with a 50%/25%
//    cascade fallback.
// 4. A link is weak when its RTT busts the tier or a standing queue forms
//    (sustained for WEAK_SUSTAIN_TICKS), or when its throughput share falls
//    below the entering threshold (0.25/N, leaving at 0.75/N: 3x hysteresis).
//    A share-weak link gets a probation re-test with backoff.
package dev.abdulkadirozyurt.srtla.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection

private const val TARGET_BEST_SAFE_CAP_MS: Int = 2500
private const val TARGET_MAX_CAP_MS: Int = 5000
private const val RTT_TO_DELAY_BUDGET_MULT: Double = 3.0
internal const val MIN_BUDGET_MS: Int = 500
internal const val MAX_BUDGET_MS: Int = 5000

/**
 * Ceiling on a budget taken from the peer's declared buffer; bounds a garbage
 * or hostile value. MAX_BUDGET_MS does not apply to a measured buffer.
 */
internal const val MAX_NEGOTIATED_BUDGET_MS: Int = 20_000

private const val SHARE_85_PERMILLE: Long = 850
private const val SHARE_50_PERMILLE: Long = 500
private const val SHARE_25_PERMILLE: Long = 250

private const val ENTER_FAIR_SHARE_NUMERATOR: Long = 250
private const val LEAVE_FAIR_SHARE_NUMERATOR: Long = 750

/** Below this total throughput every link is reported not-weak. */
private const val MIN_TOTAL_BPS_FOR_CLASSIFICATION: Double = 100_000.0

/** Consecutive ticks a delay signal must persist before the link is weak. */
internal const val WEAK_SUSTAIN_TICKS: Int = 2

/** Continuous share-weak ticks (~15 s) before a probation re-test. */
internal const val PROBATION_INTERVAL_TICKS: Int = 15

/** Length of the probation window in ticks (~3 s): forced not-weak. */
internal const val PROBATION_WINDOW_TICKS: Int = 3

/** Ceiling on the probation-interval multiplier (~4 min between re-tests). */
internal const val PROBATION_BACKOFF_MAX: Int = 16

/**
 * Multiplier on PROBATION_INTERVAL_TICKS for the next re-test: doubles each time
 * a re-test fails to hold, so a link that cannot carry its share stops costing
 * the stream a window on a fixed timer. 0 and 1 mean "no backoff yet".
 */
internal fun probationBackoffNext(backoff: Int): Int {
    val doubled = if (backoff <= 1) 2 else (backoff.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return minOf(doubled, PROBATION_BACKOFF_MAX)
}

enum class WeakReason(val wireName: String) {
    /** Passed all checks. */
    HEALTHY("healthy"),
    /** RTT exceeds the chosen delay tier. */
    HIGH_RTT("high_rtt"),
    /** RTT within tier but a standing queue is forming. */
    QUEUE_BUILDING("queue_building"),
    /** Connected but delivered no traffic. */
    NO_TRAFFIC("no_traffic"),
    /** Throughput share below the entering (or leaving) threshold. */
    LOW_SHARE("low_share"),
    /** Total throughput below the classification floor. */
    BYPASSED("bypassed");

    /**
     * A delay verdict means the link is late, not under-used: a late link must
     * not carry unique payload, an under-used one keeps a small share of it.
     */
    fun isDelay(): Boolean = this == HIGH_RTT || this == QUEUE_BUILDING
}

class LinkClassification(
    val connId: Long,
    val weak: Boolean,
    val reason: WeakReason,
    /** Throughput share in permille of total (0..1000). */
    val sharePermille: Int,
    /** Threshold the share was checked against (permille). */
    val thresholdPermille: Int,
)

class ClassificationResult(
    /** Delay tier the cascade chose (ms); 0 when bypassed. */
    val selectedDelayMs: Int,
    /** Delay budget the tiers were derived from. */
    val estimatedMaxDelayMs: Int,
    val perLink: List<LinkClassification>,
)

/**
 * Stateful filter tracking per-link hysteresis, the delay-signal streak and the
 * probation re-test state, keyed by connId.
 */
class WeakLinkFilter {
    private var prevWeak = HashMap<Long, Boolean>()
    private var delayWeakStreak = HashMap<Long, Int>()
    private var weakStreak = HashMap<Long, Int>()
    private var probationTicks = HashMap<Long, Int>()
    internal var probationBackoff = HashMap<Long, Int>()
        private set

    /**
     * Classify every link for this tick. [negotiatedLatencyMs] is the receive
     * buffer the SRT peer declared, or 0 if unknown.
     */
    fun classify(conns: List<SrtlaConnection>, negotiatedLatencyMs: Int): ClassificationResult {
        val perLink = ArrayList<LinkClassification>(conns.size)
        var totalBps = 0.0
        var longestRttMs = 0
        var connectedCount = 0
        for (conn in conns) {
            if (!conn.connected) continue
            connectedCount++
            totalBps += maxOf(conn.bitrate.currentBitrateBps, 0.0)
            val rttMs = conn.getSmoothRttMs().toInt()
            if (rttMs > longestRttMs) longestRttMs = rttMs
        }

        if (totalBps < MIN_TOTAL_BPS_FOR_CLASSIFICATION || connectedCount == 0) {
            for (conn in conns) perLink.add(LinkClassification(conn.connId, false, WeakReason.BYPASSED, 0, 0))
            // Do not carry stale weak flags across an idle period.
            prevWeak.clear()
            delayWeakStreak.clear()
            weakStreak.clear()
            probationTicks.clear()
            probationBackoff.clear()
            return ClassificationResult(0, 0, perLink)
        }

        val estimatedMaxDelayMs = delayBudgetMs(negotiatedLatencyMs, longestRttMs)
        val targetBest = targetBestDelayMs(estimatedMaxDelayMs)
        val targetSafe = targetSafeDelayMs(estimatedMaxDelayMs)
        val targetMax = targetMaxDelayMs(estimatedMaxDelayMs)

        var bestBps = 0.0
        var safeBps = 0.0
        var maxBps = 0.0
        for (conn in conns) {
            if (!conn.connected) continue
            val bps = maxOf(conn.bitrate.currentBitrateBps, 0.0)
            val rttMs = conn.getSmoothRttMs().toInt()
            if (rttMs <= targetBest) bestBps += bps
            if (rttMs <= targetSafe) safeBps += bps
            if (rttMs <= targetMax) maxBps += bps
        }
        val selectedDelay = pickTier(totalBps, bestBps, safeBps, maxBps, targetBest, targetSafe, targetMax)

        val nConnected = connectedCount.toLong()
        val enterThreshold = (ENTER_FAIR_SHARE_NUMERATOR / nConnected).toInt()
        val leaveThreshold = (LEAVE_FAIR_SHARE_NUMERATOR / nConnected).toInt()
        val nextPrevWeak = HashMap<Long, Boolean>(conns.size)
        val nextDelayStreak = HashMap<Long, Int>(conns.size)
        val nextWeakStreak = HashMap<Long, Int>(conns.size)
        val nextProbation = HashMap<Long, Int>(conns.size)
        val nextProbationBackoff = HashMap<Long, Int>(conns.size)

        for (conn in conns) {
            if (!conn.connected) {
                perLink.add(LinkClassification(conn.connId, false, WeakReason.HEALTHY, 0, 0))
                continue
            }
            val id = conn.connId
            val rttMs = conn.getSmoothRttMs().toInt()
            val bps = maxOf(conn.bitrate.currentBitrateBps, 0.0)
            val sharePermille = if (totalBps > 0.0) ((bps * 1000.0) / totalBps).coerceIn(0.0, 1000.0).toInt() else 0

            val wasWeak = prevWeak[id] ?: false
            val threshold = if (wasWeak) leaveThreshold else enterThreshold

            val delaySignal = when {
                rttMs > selectedDelay -> WeakReason.HIGH_RTT
                conn.queueBuildingSuspected() -> WeakReason.QUEUE_BUILDING
                else -> null
            }
            val delayStreak = if (delaySignal != null) {
                val prev = delayWeakStreak[id] ?: 0
                if (prev == Int.MAX_VALUE) prev else prev + 1
            } else {
                0
            }
            nextDelayStreak[id] = delayStreak
            val delayWeak = delayStreak >= WEAK_SUSTAIN_TICKS

            var weak: Boolean
            var reason: WeakReason
            when {
                delayWeak -> { weak = true; reason = delaySignal!! }
                bps == 0.0 -> { weak = true; reason = WeakReason.NO_TRAFFIC }
                wasWeak && sharePermille < leaveThreshold -> { weak = true; reason = WeakReason.LOW_SHARE }
                !wasWeak && sharePermille < enterThreshold -> { weak = true; reason = WeakReason.LOW_SHARE }
                else -> { weak = false; reason = WeakReason.HEALTHY }
            }

            // Probation re-test: breaks the share-starvation latch. A live delay
            // verdict cancels a window outright; the wait doubles after every
            // failed re-test; only a healthy verdict outside a window resets it.
            val shareWeak = weak && (reason == WeakReason.LOW_SHARE || reason == WeakReason.NO_TRAFFIC)
            var probation = probationTicks[id] ?: 0
            var streak = weakStreak[id] ?: 0
            var backoff = probationBackoff[id] ?: 0
            if (probation > 0 && delayWeak) {
                probation = 0
                streak = 0
            } else if (probation > 0) {
                probation -= 1
                streak = 0
                weak = false
                reason = WeakReason.HEALTHY
            } else if (shareWeak) {
                streak = if (streak == Int.MAX_VALUE) streak else streak + 1
                val interval = (PROBATION_INTERVAL_TICKS.toLong() * maxOf(backoff, 1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (streak >= interval) {
                    // This trigger tick stays gated; the next window ticks are not.
                    streak = 0
                    probation = PROBATION_WINDOW_TICKS
                    backoff = probationBackoffNext(backoff)
                }
            } else {
                streak = 0
                // A delay-weak link reaches this arm too; only healthy clears.
                if (!weak) backoff = 1
            }
            nextWeakStreak[id] = streak
            nextProbation[id] = probation
            nextProbationBackoff[id] = backoff
            nextPrevWeak[id] = weak
            perLink.add(LinkClassification(id, weak, reason, sharePermille, threshold))
        }

        prevWeak = nextPrevWeak
        delayWeakStreak = nextDelayStreak
        weakStreak = nextWeakStreak
        probationTicks = nextProbation
        probationBackoff = nextProbationBackoff
        return ClassificationResult(selectedDelay, estimatedMaxDelayMs, perLink)
    }
}

internal fun deriveMaxDelayBudget(longestRttMs: Int): Int {
    val raw = (longestRttMs.toDouble() * RTT_TO_DELAY_BUDGET_MULT).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return raw.coerceIn(MIN_BUDGET_MS, MAX_BUDGET_MS)
}

/**
 * Delay budget the tiers are cut from. The tiers compare round trips, so the
 * one-way deadline is doubled. 0 means the peer never declared one.
 */
internal fun delayBudgetMs(negotiatedLatencyMs: Int, longestRttMs: Int): Int {
    if (negotiatedLatencyMs == 0) return deriveMaxDelayBudget(longestRttMs)
    val doubled = (negotiatedLatencyMs.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return doubled.coerceIn(MIN_BUDGET_MS, MAX_NEGOTIATED_BUDGET_MS)
}

internal fun targetBestDelayMs(estMs: Int): Int = minOf(estMs.toLong() * 40 / 100, TARGET_BEST_SAFE_CAP_MS.toLong()).toInt()
internal fun targetSafeDelayMs(estMs: Int): Int = minOf(estMs.toLong() * 50 / 100, TARGET_BEST_SAFE_CAP_MS.toLong()).toInt()
internal fun targetMaxDelayMs(estMs: Int): Int = minOf(estMs.toLong() * 60 / 100, TARGET_MAX_CAP_MS.toLong()).toInt()

internal fun pickTier(
    totalBps: Double,
    bestBps: Double,
    safeBps: Double,
    maxBps: Double,
    bestDelay: Int,
    safeDelay: Int,
    maxDelay: Int,
): Int {
    val bestPm = ((bestBps * 1000.0) / totalBps).toLong()
    val safePm = ((safeBps * 1000.0) / totalBps).toLong()
    val maxPm = ((maxBps * 1000.0) / totalBps).toLong()
    if (bestPm > SHARE_85_PERMILLE) return bestDelay
    if (safePm > SHARE_85_PERMILLE) return safeDelay
    if (maxPm > SHARE_85_PERMILLE) {
        if (bestPm > SHARE_50_PERMILLE) return bestDelay
        if (safePm > SHARE_50_PERMILLE) return safeDelay
        if (maxPm > SHARE_50_PERMILLE) return maxDelay
        if (bestPm > SHARE_25_PERMILLE) return bestDelay
        if (safePm > SHARE_25_PERMILLE) return safeDelay
        return maxDelay
    }
    return maxDelay
}
