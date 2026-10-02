// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/selection/enhanced.rs
//
// Enhanced selection: quality-aware scoring with admission gates. Called for
// every SRT packet on purpose: getScore() counts queued packets as in-flight,
// so routing a packet lowers its own link's score, and re-deciding per packet
// is the feedback loop that bounds per-link queue depth.
package dev.abdulkadirozyurt.srtla.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.satSub
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.enhanced")

/** Headroom on the bandwidth-delay product for the per-link in-flight cap. */
private const val IN_FLIGHT_CAP_BDP_MULT: Double = 1.5

/** A new link must score 10% better to take the next packet. */
internal const val SWITCH_THRESHOLD: Double = 1.10

/** Floor on the CC soft-cap multiplier: keep a trickle so the CC can observe. */
internal const val CC_SOFT_CAP_FLOOR: Double = 0.10

/**
 * Score multiplier for a share-weak link while an un-gated link exists. The link
 * stays rankable at a crushed score, so it keeps the trickle of real traffic it
 * needs to earn back the throughput share that clears the verdict.
 */
internal const val GATED_LINK_PENALTY: Double = 0.02

/** A challenger must measure this many times better RTT to take the sole-carrier role. */
internal const val SOLE_CARRIER_MARGIN: Double = 2.0

/** Minimum time a link holds the sole-carrier role before any challenger can take it. */
internal const val SOLE_CARRIER_MIN_HOLD_MS: Long = 2000L

/** Ramp length for a link released from a non-stall exclusion. */
internal const val HELD_OUT_REJOIN_RAMP_MS: Long = 2000L

/**
 * Should the sole-carrier role move to the challenger? Keyed on smoothed RTT,
 * not score: the incumbent carries the stream, so its in-flight is high and an
 * idle challenger always scores better. An unmeasured RTT blocks the handover.
 */
fun soleCarrierHandover(
    incumbentRttMs: Double,
    challengerRttMs: Double,
    heldForMs: Long,
    holdMinMs: Long,
    margin: Double,
): Boolean {
    if (heldForMs < holdMinMs) return false
    if (!incumbentRttMs.isFinite() || !challengerRttMs.isFinite()) return false
    if (incumbentRttMs <= 0.0 || challengerRttMs <= 0.0) return false
    return challengerRttMs * margin <= incumbentRttMs
}

/**
 * Elect the one link that keeps carrying the payload while every schedulable
 * link is quality-gated, and hold that choice steady.
 *
 * With no healthy link the gates lift and per-packet ranking would split unique
 * sequences across links whose delays differ by an order of magnitude, stalling
 * the receiver's reorder buffer. The role is sticky: the incumbent keeps it until
 * it stops being a candidate, or a sibling measures SOLE_CARRIER_MARGIN times
 * better after SOLE_CARRIER_MIN_HOLD_MS.
 *
 * Returns the elected index while active; null when a healthy link exists or no
 * link is a candidate (the full-pool fallback then stands).
 */
internal fun electSoleCarrier(conns: List<SrtlaConnection>, currentTimeMs: Long, anyQualityOk: Boolean): Int? {
    if (anyQualityOk) {
        for (c in conns) releaseSoleCarrier(c, currentTimeMs)
        return null
    }
    fun candidate(c: SrtlaConnection) = !c.isTimedOut(currentTimeMs) && c.isSchedulable() && !c.stallGated

    var incumbent: Int? = null
    var bestMeasured: Pair<Int, Double>? = null
    var bestUnmeasured: Pair<Int, Int>? = null
    for ((i, c) in conns.withIndex()) {
        if (!candidate(c)) continue
        val rtt = c.getSmoothRttMs()
        if (rtt > 0.0) {
            if (bestMeasured == null || rtt < bestMeasured.second) bestMeasured = i to rtt
        } else {
            val score = c.getScore()
            if (bestUnmeasured == null || score > bestUnmeasured.second) bestUnmeasured = i to score
        }
        if (c.soleCarrier) incumbent = i
    }
    val best = bestMeasured?.first ?: bestUnmeasured?.first
    val bestRtt = bestMeasured?.second ?: Double.MAX_VALUE

    val keep: Int? = when {
        incumbent != null && best != null && best != incumbent -> {
            val inc = conns[incumbent]
            val heldFor = currentTimeMs.satSub(inc.soleCarrierSinceMs)
            if (soleCarrierHandover(inc.getSmoothRttMs(), bestRtt, heldFor, SOLE_CARRIER_MIN_HOLD_MS, SOLE_CARRIER_MARGIN)) {
                best
            } else {
                incumbent
            }
        }
        incumbent != null -> incumbent
        else -> best
    }

    // Re-forming around the same incumbent is not a handover and must not
    // restart the minimum hold.
    val handover = incumbent != null && keep != null && incumbent != keep

    for ((i, c) in conns.withIndex()) {
        if (i == keep) {
            if (c.soleCarrierExcluded) c.armRejoinRamp(currentTimeMs, HELD_OUT_REJOIN_RAMP_MS, false)
            if (!c.soleCarrier) {
                log.fine { "${c.label}: elected sole carrier (every link is quality gated)" }
                c.soleCarrier = true
                if (handover || c.soleCarrierSinceMs == 0L) c.soleCarrierSinceMs = currentTimeMs
                if (handover) c.soleCarrierElectionsCount++
            }
            c.soleCarrierExcluded = false
        } else {
            c.soleCarrier = false
            if (handover) c.soleCarrierSinceMs = 0L
            // Only a link that could have carried is held out by this election.
            c.soleCarrierExcluded = keep != null && candidate(c)
        }
    }
    return keep
}

/** Clear the role, arming the rejoin ramp if this ends an exclusion. */
private fun releaseSoleCarrier(c: SrtlaConnection, currentTimeMs: Long) {
    if (c.soleCarrierExcluded) {
        c.armRejoinRamp(currentTimeMs, HELD_OUT_REJOIN_RAMP_MS, false)
        c.soleCarrierExcluded = false
    }
    c.soleCarrier = false
}

/**
 * In-flight cap as a bandwidth-delay product:
 * `max(1, cc_target_bps * rtt_min_s / 8 * 1.5 / packet_bytes)`. Null when the CC
 * has published no target yet. A non-positive rtt_min falls back to 1 ms.
 */
fun inFlightCapPackets(ccTargetBps: Long, rttMinMs: Double): Int? {
    if (ccTargetBps == 0L) return null
    val rttMs = if (rttMinMs.isFinite() && rttMinMs > 0.0) rttMinMs else 1.0
    val bdpBytes = ccTargetBps.toDouble() * (rttMs / 1000.0) / 8.0 * IN_FLIGHT_CAP_BDP_MULT
    val cap = maxOf(Math.floor(bdpBytes / ASSUMED_SRT_PAYLOAD_BYTES.toDouble()), 1.0)
    return minOf(cap, Int.MAX_VALUE.toDouble()).toInt()
}

/** Whether in-flight already exceeds the BDP cap. */
fun inFlightCapExceeded(c: SrtlaConnection): Boolean {
    val cap = inFlightCapPackets(c.ccTargetBps, c.getRttMinMs()) ?: return false
    return c.inFlightPackets > cap
}

/**
 * CC soft-cap multiplier in [CC_SOFT_CAP_FLOOR, 1.0]: scales a link's score down
 * as measured throughput approaches cc_target_bps. 1.0 with no target or an idle
 * link.
 */
internal fun ccSoftCapMultiplier(conn: SrtlaConnection): Double {
    val cap = conn.ccTargetBps
    if (cap == 0L) return 1.0
    val measured = conn.bitrate.currentBitrateBps
    if (measured <= 0.0) return 1.0
    val capF = cap.toDouble()
    val headroom = maxOf(capF - measured, 0.0)
    return (headroom / capF).coerceIn(CC_SOFT_CAP_FLOOR, 1.0)
}

/** Enhanced selection; returns the chosen index or null. */
fun selectEnhanced(conns: List<SrtlaConnection>, lastIdx: Int?, currentTimeMs: Long, enableQuality: Boolean): Int? {
    // Is at least one link free of every gate? The loss gate uses the sustained
    // lossDegraded latch, not the per-window ccBackingOff.
    val anyUnconstrained = conns.any {
        !it.isTimedOut(currentTimeMs) && it.isSchedulable() && !it.weak && !it.lossDegraded &&
            !it.stallGated && !inFlightCapExceeded(it)
    }
    // The election keys on the quality gates only: the in-flight cap is a
    // transient that clears as the link drains.
    val anyQualityOk = conns.any {
        !it.isTimedOut(currentTimeMs) && it.isSchedulable() && !it.weak && !it.lossDegraded && !it.stallGated
    }
    val soleCarrier = electSoleCarrier(conns, currentTimeMs, anyQualityOk)

    // Held out of the rotation entirely (not merely demoted): a late link while a
    // healthy one can carry, or a loser of the sole-carrier election. A late link
    // is probed with duplicates by the shell instead.
    for ((i, c) in conns.withIndex()) {
        val schedulable = !c.isTimedOut(currentTimeMs) && c.isSchedulable() && !c.stallGated
        val late = c.lossDegraded || (c.weak && c.weakReason.isDelay())
        val excluded = schedulable &&
            ((anyUnconstrained && late) || (soleCarrier != null && i != soleCarrier))
        // Falling edge: a held-out link drained while out, so it ramps back in.
        if (c.qualityExcluded && !excluded) c.armRejoinRamp(currentTimeMs, HELD_OUT_REJOIN_RAMP_MS, false)
        c.qualityExcluded = excluded
    }

    var bestIdx: Int? = null
    var bestScore = -1.0
    var currentScore: Double? = null

    for ((i, c) in conns.withIndex()) {
        // A stall-gated link has a healthier alternative; skip it like a dead one.
        if (c.isTimedOut(currentTimeMs) || !c.isSchedulable() || c.stallGated) continue
        if (c.qualityExcluded) continue
        // The in-flight cap bounds queueing delay and self-clears as the link drains.
        if (anyUnconstrained && inFlightCapExceeded(c)) continue
        // Weak links left here are weak for share reasons only: crush, keep rankable.
        val gateMult = if (anyUnconstrained && c.weak) GATED_LINK_PENALTY else 1.0
        // Phase weight de-rates a warming link; the ramp de-rates a rejoining one.
        val base = c.getScore().toDouble() * c.phaseWeight() * c.rejoinRampMultiplier(currentTimeMs)
        val capMult = ccSoftCapMultiplier(c)
        val score = if (!enableQuality) {
            base * capMult * gateMult
        } else {
            val qualityMult = c.getCachedQualityMultiplier(currentTimeMs)
            val finalScore = base * qualityMult * capMult * gateMult
            logQualityState(c, qualityMult, base, finalScore, currentTimeMs)
            finalScore
        }
        if (i == lastIdx) currentScore = score
        if (score > bestScore) {
            bestScore = score
            bestIdx = i
        }
    }

    // No time-based switch cooldown: holding the decision fixed opens the
    // queued-as-in-flight feedback loop. Score hysteresis still damps noise.
    if (lastIdx != null && bestIdx != lastIdx) {
        val current = currentScore
        if (current != null && bestScore < current * SWITCH_THRESHOLD) {
            if (currentTimeMs % 1000 < 10) {
                log.fine { "Score hysteresis: staying with current connection (current: ${"%.1f".format(current)}, best: ${"%.1f".format(bestScore)}, threshold: ${"%.1f".format(current * SWITCH_THRESHOLD)})" }
            }
            return lastIdx
        }
    }
    return bestIdx
}

private fun logQualityState(c: SrtlaConnection, qualityMult: Double, base: Double, finalScore: Double, nowMs: Long) {
    if (qualityMult < 0.8) {
        log.fine { "${c.label} quality degraded: ${"%.2f".format(qualityMult)} (NAKs: ${c.totalNakCount()}, last: ${c.timeSinceLastNakMs(nowMs) ?: 0}ms ago, burst: ${c.nakBurstCount()}) base: ${base.toInt()} → final: ${finalScore.toInt()}" }
    } else if (qualityMult < 1.0 && c.nakBurstCount() > 0) {
        log.fine { "${c.label} quality recovering: ${"%.2f".format(qualityMult)} (burst: ${c.nakBurstCount()})" }
    }
}
