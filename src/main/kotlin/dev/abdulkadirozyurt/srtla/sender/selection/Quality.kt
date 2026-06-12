// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/selection/quality.rs
//
// Quality scoring for enhanced/rtt-threshold mode connection selection.
// Calculates a multiplier based on NAK history, burst detection, and RTT.
//
// Cache behaviour: Kotlin has no thread-local cache like the Rust version;
// callers hold a lock so a simple per-call calculation is safe and correct.
// A lightweight 50ms cache is kept on QualityCache to mirror Rust behaviour.
package dev.abdulkadirozyurt.srtla.sender.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection

// src/sender/selection/quality.rs — constants, birebir
/** Startup grace period in ms: early NAKs do not permanently degrade a link. */
const val STARTUP_GRACE_PERIOD_MS: Long = 30_000L       // quality.rs:STARTUP_GRACE_PERIOD_MS

/** Quality multiplier for a perfect connection (no NAKs ever). */
const val PERFECT_CONNECTION_BONUS: Double = 1.1        // quality.rs:PERFECT_CONNECTION_BONUS

/** Multiplier during startup when NAKs occur (light penalty only). */
const val STARTUP_NAK_PENALTY: Double = 0.98            // quality.rs:STARTUP_NAK_PENALTY

/** Exponential decay half-life for NAK recovery (ms). */
const val HALF_LIFE_MS: Double = 2000.0                 // quality.rs:HALF_LIFE_MS

/** Maximum initial penalty after a NAK (0.5 → 50 % penalty at time 0). */
const val MAX_PENALTY: Double = 0.5                     // quality.rs:MAX_PENALTY

/** Minimum NAK burst count to trigger burst penalty. */
const val NAK_BURST_THRESHOLD: Int = 5                  // quality.rs:NAK_BURST_THRESHOLD

/** Maximum age in ms for a NAK burst to still apply extra penalty. */
const val NAK_BURST_MAX_AGE_MS: Long = 3_000L           // quality.rs:NAK_BURST_MAX_AGE_MS

/** Additional multiplier for NAK bursts (0.7 → 30 % extra penalty). */
const val NAK_BURST_PENALTY: Double = 0.7               // quality.rs:NAK_BURST_PENALTY

/** RTT below this value (ms) earns a bonus. */
const val RTT_BONUS_THRESHOLD_MS: Double = 200.0        // quality.rs:RTT_BONUS_THRESHOLD_MS

/** Minimum RTT (ms) used in bonus denominator to avoid division issues. */
const val MIN_RTT_MS: Double = 50.0                     // quality.rs:MIN_RTT_MS

/** Maximum RTT bonus multiplier (3 % for low-latency connections). */
const val MAX_RTT_BONUS: Double = 1.03                  // quality.rs:MAX_RTT_BONUS

// Cache interval: recalculate at most every 50 ms (mirrors Rust CachedQuality)
private const val QUALITY_CACHE_INTERVAL_MS: Long = 50L

/**
 * Per-connection quality cache.
 * Attach one instance to a SrtlaConnection wrapper or pass via an external map.
 * In the JVM port the SelectionOrchestrator keeps a map indexed by connId,
 * but callers that call [calculateQualityMultiplier] directly get no caching —
 * mirroring the Rust uncached path used in unit tests.
 */
class QualityCache {
    var multiplier: Double = 1.0
        private set
    private var lastCalcMs: Long = 0L

    /**
     * Return cached multiplier if still fresh, else recalculate.
     * Mirrors Rust `SrtlaConnection::get_cached_quality_multiplier`.
     */
    fun get(conn: SrtlaConnection, currentTimeMs: Long): Double {
        if (currentTimeMs - lastCalcMs >= QUALITY_CACHE_INTERVAL_MS) {
            multiplier = calculateQualityMultiplier(conn, currentTimeMs)
            lastCalcMs = currentTimeMs
        }
        return multiplier
    }

    fun reset() {
        multiplier = 1.0
        lastCalcMs = 0L
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Public API
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Calculate quality multiplier for [conn] at [currentTimeMs].
 *
 * Mirrors Rust `calculate_quality_multiplier` / `calculate_quality_multiplier_uncached`
 * in src/sender/selection/quality.rs.
 *
 * Return value:
 *   - 1.1  — perfect connection (zero NAKs ever)
 *   - 0.98 — startup grace, NAKs present
 *   - 0.5–1.0 — exponential NAK decay after grace
 *   - ×0.7  — extra burst penalty if burst ≥ 5 within 3 s
 *   - ×1.0–1.03 — RTT bonus on top
 */
fun calculateQualityMultiplier(conn: SrtlaConnection, currentTimeMs: Long): Double {
    // ── Startup grace (first 30 s) ──────────────────────────────────────────
    val connectionAgeMs = (currentTimeMs - conn.connectionEstablishedMs())
        .coerceAtLeast(0L)
    if (connectionAgeMs < STARTUP_GRACE_PERIOD_MS) {
        return if (conn.totalNakCount() == 0) PERFECT_CONNECTION_BONUS
        else STARTUP_NAK_PENALTY
    }

    // ── Exponential NAK decay ───────────────────────────────────────────────
    val qualityMult: Double = when (val nakAgeMs = conn.timeSinceLastNakMs()) {
        null -> {
            if (conn.totalNakCount() == 0) PERFECT_CONNECTION_BONUS
            else 1.0   // had NAKs before but none recently tracked
        }
        else -> {
            // decay_factor = exp(-age / half_life)
            // penalty      = MAX_PENALTY * decay_factor
            // mult         = 1 - penalty        (recovers from 0.5 → 1.0)
            val decayFactor = Math.exp(-(nakAgeMs.toDouble()) / HALF_LIFE_MS)
            val penalty = MAX_PENALTY * decayFactor
            var mult = 1.0 - penalty

            // Extra penalty for recent NAK burst (≥5 NAKs within 3 s)
            if (conn.nakBurstCount() >= NAK_BURST_THRESHOLD && nakAgeMs < NAK_BURST_MAX_AGE_MS) {
                mult *= NAK_BURST_PENALTY
            }
            mult
        }
    }

    // ── RTT bonus ───────────────────────────────────────────────────────────
    val rttBonus = calculateRttBonus(conn)
    return qualityMult * rttBonus
}

/**
 * RTT bonus: 1.0–MAX_RTT_BONUS, never a penalty.
 * Formula: min(threshold / max(rtt, MIN_RTT), MAX_RTT_BONUS), floor 1.0.
 * Mirrors Rust `calculate_rtt_bonus` in quality.rs.
 */
private fun calculateRttBonus(conn: SrtlaConnection): Double {
    val smoothRtt = conn.getSmoothRttMs()
    if (smoothRtt <= 0.0) return 1.0   // no RTT data yet

    // rtt_factor = min(THRESHOLD / actual_rtt, MAX_BONUS)
    val rttFactor = (RTT_BONUS_THRESHOLD_MS / smoothRtt.coerceAtLeast(MIN_RTT_MS))
        .coerceAtMost(MAX_RTT_BONUS)

    return rttFactor.coerceAtLeast(1.0)   // only bonus, never penalty
}
