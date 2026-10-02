// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/selection/classic.rs
//
// Original SRTLA selection, C-exact: pick the highest window/(in_flight+1).
// The score is multiplied by the link's operator weight multiplier, which is
// exactly 1.0 for an unweighted link.
package dev.abdulkadirozyurt.srtla.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection

/** Highest capacity score, or null when every link is timed out or gated. */
fun selectClassic(conns: List<SrtlaConnection>, nowMs: Long): Int? {
    var bestIdx: Int? = null
    var bestScore = -1
    for ((i, c) in conns.withIndex()) {
        // stallGated is only set when a healthier link exists, so skipping it
        // can never starve the pool.
        if (c.isTimedOut(nowMs) || !c.isSchedulable() || c.stallGated) continue
        val score = weightedScore(c)
        if (score > bestScore) {
            bestScore = score
            bestIdx = i
        }
    }
    return bestIdx
}

/**
 * getScore() scaled by the weight multiplier and truncated, like Moblin's
 * `Int(Float(score) * priority)`. Weight 1 returns getScore() unchanged.
 */
fun weightedScore(c: SrtlaConnection): Int {
    val score = c.getScore()
    if (c.linkWeight <= 1 || score <= 0) return score
    return (score.toFloat() * c.weightMultiplier()).toInt()
}
