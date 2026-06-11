// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/connection/congestion/mod.rs, classic.rs, enhanced.rs
//
// Congestion control strategies for SRTLA connections.
//
// Classic mode: matches original C implementation — simple window increase
//   based on in-flight packets, no time-based recovery.
// Enhanced mode: same base growth as classic + fast recovery + time-based
//   progressive window recovery.
//
// Window constants (src/protocol/constants.rs):
//   WINDOW_MIN 1 / WINDOW_DEF 20 / WINDOW_MAX 60 / WINDOW_MULT 1000
//   WINDOW_DECR 100 / WINDOW_INCR 30
package dev.abdulkadirozyurt.srtla.connection.congestion

import dev.abdulkadirozyurt.srtla.protocol.WINDOW_DECR
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_INCR
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_MAX
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_MIN
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_MULT

// src/connection/congestion/mod.rs
private const val NAK_BURST_WINDOW_MS: Long = 1000L
private const val NAK_BURST_LOG_THRESHOLD: Int = 5

// src/connection/congestion/enhanced.rs
private const val NORMAL_MIN_WAIT_MS: Long = 2000L
private const val FAST_MIN_WAIT_MS: Long = 500L
private const val NORMAL_INCREMENT_WAIT_MS: Long = 1000L
private const val FAST_INCREMENT_WAIT_MS: Long = 300L
private const val FAST_RECOVERY_DISABLE_WINDOW: Int = 12_000

/**
 * Congestion control and NAK tracking state.
 * Mirrors Rust `struct CongestionControl` in src/connection/congestion/mod.rs.
 */
class CongestionControl {
    var nakCount: Int = 0
    var lastNakTimeMs: Long = 0L
    var lastWindowIncreaseMs: Long = 0L
    var consecutiveAcksWithoutNak: Int = 0
    var fastRecoveryMode: Boolean = false
    var fastRecoveryStartMs: Long = 0L
    var nakBurstCount: Int = 0
    var nakBurstStartTimeMs: Long = 0L

    /** Reset all congestion control state. Mirrors Rust `CongestionControl::reset`. */
    fun reset() {
        nakCount = 0
        lastNakTimeMs = 0L
        nakBurstCount = 0
        nakBurstStartTimeMs = 0L
        lastWindowIncreaseMs = 0L
        consecutiveAcksWithoutNak = 0
        fastRecoveryMode = false
        fastRecoveryStartMs = 0L
    }

    /**
     * Handle NAK reception — common to both classic and enhanced.
     * Reduces window and tracks burst state.
     * Mirrors Rust `CongestionControl::handle_nak`.
     */
    fun handleNak(window: IntRef, seq: Int, label: String): Boolean {
        val currentTime = System.currentTimeMillis()
        nakCount = nakCount.saturatingAdd(1)

        val timeSinceLastNak = (currentTime - lastNakTimeMs).coerceAtLeast(0L)

        // Track NAK bursts
        if (lastNakTimeMs > 0L && timeSinceLastNak < NAK_BURST_WINDOW_MS) {
            if (nakBurstCount == 0) {
                nakBurstCount = 2
                nakBurstStartTimeMs = lastNakTimeMs
            } else {
                nakBurstCount = nakBurstCount.saturatingAdd(1)
            }
        } else {
            nakBurstCount = 0
            nakBurstStartTimeMs = 0L
        }

        lastNakTimeMs = currentTime
        consecutiveAcksWithoutNak = 0

        // Reduce window — src/connection/congestion/mod.rs::handle_nak
        val oldWindow = window.value
        window.value = maxOf(window.value - WINDOW_DECR, WINDOW_MIN * WINDOW_MULT)

        // Enter fast recovery mode if window is very low (enhanced mode)
        if (window.value <= 2000 && !fastRecoveryMode) {
            fastRecoveryMode = true
            fastRecoveryStartMs = currentTime
        }

        return true
    }

    /**
     * Handle SRTLA ACK specific — classic mode.
     * Only increase if in_flight_pkts * WINDOW_MULT > window.
     * Mirrors Rust `classic::handle_srtla_ack_specific` in src/connection/congestion/classic.rs.
     * C implementation lines 291-293.
     */
    fun handleSrtlaAckClassic(window: IntRef, inFlightPackets: Int, seq: Int, label: String) {
        // CLASSIC MODE: exact C implementation
        // Window increase only if in_flight_pkts*WINDOW_MULT > window
        if (inFlightPackets * WINDOW_MULT > window.value) {
            // Note: WINDOW_INCR - 1 in C code (matches Rust classic.rs)
            window.value = minOf(window.value + WINDOW_INCR - 1, WINDOW_MAX * WINDOW_MULT)
        }
    }

    /**
     * Handle SRTLA ACK — enhanced mode.
     * Identical window growth to classic; additionally manages fast recovery.
     * Mirrors Rust `enhanced::handle_srtla_ack` in src/connection/congestion/enhanced.rs.
     */
    fun handleSrtlaAckEnhanced(window: IntRef, inFlightPackets: Int, label: String) {
        // Enhanced mode: IDENTICAL window growth to classic mode
        if (inFlightPackets * WINDOW_MULT > window.value) {
            window.value = minOf(window.value + WINDOW_INCR - 1, WINDOW_MAX * WINDOW_MULT)
        }

        // Fast recovery mode: disable when window recovers enough
        val currentTime = System.currentTimeMillis()
        if (fastRecoveryMode && window.value >= FAST_RECOVERY_DISABLE_WINDOW) {
            fastRecoveryMode = false
        }
    }

    /**
     * Perform time-based window recovery (enhanced mode only).
     * Progressive rates based on time since last NAK.
     * Mirrors Rust `enhanced::perform_window_recovery` in src/connection/congestion/enhanced.rs.
     *
     * Recovery rates:
     *   10s+  no NAKs (or never): aggressive (WINDOW_INCR * 2)
     *   7s+:  moderate (WINDOW_INCR)
     *   5s+:  slow (WINDOW_INCR / 2)
     *   <5s:  minimal (WINDOW_INCR / 4)
     * All rates doubled in fast recovery mode.
     */
    fun performWindowRecovery(window: IntRef, connected: Boolean, label: String) {
        if (!connected || window.value >= WINDOW_MAX * WINDOW_MULT) return

        val now = System.currentTimeMillis()

        // Treat "never had NAK" as perfect connection — triggers aggressive recovery
        // src/connection/congestion/enhanced.rs: last_nak_time_ms == 0 → treat as u64::MAX
        val timeSinceLastNak: Long = if (lastNakTimeMs > 0L) {
            (now - lastNakTimeMs).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }

        // Clear NAK burst tracking if enough time has passed
        if (timeSinceLastNak >= NAK_BURST_WINDOW_MS && nakBurstCount > 0) {
            nakBurstCount = 0
            nakBurstStartTimeMs = 0L
        }

        val minWaitTime = if (fastRecoveryMode) FAST_MIN_WAIT_MS else NORMAL_MIN_WAIT_MS
        val incrementWait = if (fastRecoveryMode) FAST_INCREMENT_WAIT_MS else NORMAL_INCREMENT_WAIT_MS

        if (timeSinceLastNak > minWaitTime && (now - lastWindowIncreaseMs) > incrementWait) {
            val oldWindow = window.value
            val fastModeBonus = if (fastRecoveryMode) 2 else 1

            // Progressive recovery based on how long since last NAK
            when {
                timeSinceLastNak > 10_000L -> window.value += WINDOW_INCR * 2 * fastModeBonus
                timeSinceLastNak > 7_000L  -> window.value += WINDOW_INCR * fastModeBonus
                timeSinceLastNak > 5_000L  -> window.value += WINDOW_INCR * fastModeBonus / 2
                else                        -> window.value += WINDOW_INCR * fastModeBonus / 4
            }
            window.value = minOf(window.value, WINDOW_MAX * WINDOW_MULT)
            lastWindowIncreaseMs = now

            if (fastRecoveryMode && window.value >= FAST_RECOVERY_DISABLE_WINDOW) {
                fastRecoveryMode = false
            }
        }
    }

    /** Get time since last NAK in milliseconds, or null if no NAK has been received. */
    fun timeSinceLastNakMs(): Long? {
        if (lastNakTimeMs == 0L) return null
        return (System.currentTimeMillis() - lastNakTimeMs).coerceAtLeast(0L)
    }
}

/** Mutable integer reference used as output parameter for window updates. */
class IntRef(var value: Int)

private fun Int.saturatingAdd(other: Int): Int {
    val result = this.toLong() + other.toLong()
    return result.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
