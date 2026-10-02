// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/connection/mod.rs (free functions, LinkPhase)
package dev.abdulkadirozyurt.srtla.connection

import dev.abdulkadirozyurt.srtla.core.STALL_REJOIN_BACKOFF_MAX
import dev.abdulkadirozyurt.srtla.core.STALL_REJOIN_RAMP_FLOOR
import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_MULT

const val STARTUP_GRACE_MS: Long = 5_000L

/** RTT probes required before a link moves from Warming to Live. */
internal const val WARMING_RTT_PROBES: Int = 2

/** Maximum time a link may stay Warming before auto-promotion to Live. */
internal const val WARMING_TIMEOUT_MS: Long = 5_000L

/** Interval between quality multiplier recalculations. */
const val QUALITY_CACHE_INTERVAL_MS: Long = 50L

/**
 * Link lifecycle phase.
 *
 * A phase weights a link's score; it does not remove the link. Registering is
 * the sole exception, and only because the receiver has not returned REG3, so
 * data on that link would be discarded by the protocol. Warming used to be a
 * hard exclusion, which emptied the pool at go-live when every link warms.
 */
sealed class LinkPhase {
    object Registering : LinkPhase() {
        override fun toString() = "registering"
    }

    /** REG3 received, accumulating RTT probes. Usable but de-rated. */
    data class Warming(val rttProbes: Int, val enteredMs: Long) : LinkPhase() {
        override fun toString() = "warming($rttProbes)"
    }

    object Live : LinkPhase() {
        override fun toString() = "live"
    }

    /** Quality degraded: still scheduled, de-prioritised, never excluded. */
    object Degraded : LinkPhase() {
        override fun toString() = "degraded"
    }

    /** Only Registering is excluded, because the protocol forbids it. */
    fun isSchedulable(): Boolean = this !is Registering

    /**
     * Scheduling weight folded into the link's score. Degraded stays at 1.0:
     * degradation is already priced in by the quality multiplier and the gates.
     */
    fun weight(): Double = when (this) {
        is Registering -> 0.0
        is Warming -> 0.8
        is Live, is Degraded -> 1.0
    }
}

/** Cached quality multiplier, recalculated every [QUALITY_CACHE_INTERVAL_MS]. */
class CachedQuality(var multiplier: Double = 1.0, var lastCalculatedMs: Long = 0L)

// ── Operator link weights (Moblin "connection priorities") ──────────────────

const val LINK_WEIGHT_MIN: Int = 1
const val LINK_WEIGHT_MAX: Int = 10
/** Above this window a weighted link gets its full weight. */
const val LINK_WEIGHT_FULL_ABOVE_WINDOW: Int = 20 * WINDOW_MULT
/** At or below this window the weight is ignored. */
const val LINK_WEIGHT_NONE_AT_OR_BELOW_WINDOW: Int = 10 * WINDOW_MULT

/**
 * Score multiplier for operator weight [weight] at congestion window [window],
 * copied from Moblin's RemoteConnection.score(): full weight above 20 000,
 * fading linearly to 1 between 20 000 and 10 000, 1 below. A weight of 1 is
 * exactly 1.0 everywhere, so an unweighted file schedules as before.
 */
fun linkWeightMultiplier(window: Int, weight: Int): Float {
    val w = weight.coerceIn(LINK_WEIGHT_MIN, LINK_WEIGHT_MAX).toFloat()
    return when {
        window > LINK_WEIGHT_FULL_ABOVE_WINDOW -> w
        window > LINK_WEIGHT_NONE_AT_OR_BELOW_WINDOW -> {
            val factor = (window - LINK_WEIGHT_NONE_AT_OR_BELOW_WINDOW).toFloat() /
                (LINK_WEIGHT_FULL_ABOVE_WINDOW - LINK_WEIGHT_NONE_AT_OR_BELOW_WINDOW).toFloat()
            1.0f + (w - 1.0f) * factor
        }
        else -> 1.0f
    }
}

/**
 * Normalise weights in place so the lowest is 1, keeping the differences
 * (Moblin: `priority - lowest + 1`). Inputs are clamped to 1..10 first.
 */
fun normaliseLinkWeights(weights: IntArray) {
    for (i in weights.indices) weights[i] = weights[i].coerceIn(LINK_WEIGHT_MIN, LINK_WEIGHT_MAX)
    val lowest = weights.minOrNull() ?: return
    for (i in weights.indices) weights[i] = weights[i] - lowest + 1
}

/**
 * Share of its natural score a link competes with while ramping back in after
 * a gate, rising linearly from [STALL_REJOIN_RAMP_FLOOR] to 1.0 over [rampMs].
 *
 * A held-out link drains its in-flight count to zero while time-based window
 * recovery keeps growing its window, so `window / (in_flight + 1)` makes it the
 * best-looking link the instant it is released. The ramp makes it earn its
 * share back under a growing load. `rampStartMs == 0` or an elapsed ramp mean 1.0.
 */
fun rejoinRampMultiplier(rampStartMs: Long, rampMs: Long, nowMs: Long): Double {
    if (rampStartMs == 0L || rampMs == 0L) return 1.0
    val elapsed = nowMs.satSub(rampStartMs)
    if (elapsed >= rampMs) return 1.0
    val progress = elapsed.toDouble() / rampMs.toDouble()
    return (STALL_REJOIN_RAMP_FLOOR + (1.0 - STALL_REJOIN_RAMP_FLOOR) * progress)
        .coerceIn(STALL_REJOIN_RAMP_FLOOR, 1.0)
}

/**
 * Rejoin-dwell multiplier to serve after the stall latch engages again.
 * Doubles each time a rejoin fails to outlast [probationMs], capped at
 * [STALL_REJOIN_BACKOFF_MAX]; a rejoin that held, or the first engagement
 * (`releasedAtMs == 0`), resets it to 1. 0 and 1 both mean 1x.
 */
fun stallRejoinBackoffNext(backoff: Int, releasedAtMs: Long, heldForMs: Long, probationMs: Long): Int {
    if (releasedAtMs == 0L || heldForMs >= probationMs) return 1
    val doubled = if (backoff <= 1) 2 else (backoff.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return minOf(doubled, STALL_REJOIN_BACKOFF_MAX)
}

/**
 * Whether delivery proof at this round trip is worth anything against a one-way
 * deadline of [budgetMs]. Half the RTT is the one-way delay; a packet arriving
 * after the receiver's buffer is dropped, whatever else is true about the link.
 * Gates only the latch *release*. A zero budget (peer never declared one) or a
 * link with no RTT baseline passes.
 */
fun deliveryProofIsTimely(smoothRttMs: Double, budgetMs: Int): Boolean {
    if (budgetMs == 0 || smoothRttMs <= 0.0) return true
    return (smoothRttMs / 2.0) <= budgetMs.toDouble()
}
