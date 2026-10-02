// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/connection/congestion/{mod,classic,enhanced}.rs
//
// Classic mode matches the original C implementation: window growth based on
// in-flight packets, no time-based recovery. Enhanced mode keeps the same
// growth and adds fast recovery plus time-based progressive window recovery,
// gated on RTT velocity.
package dev.abdulkadirozyurt.srtla.connection.congestion

import dev.abdulkadirozyurt.srtla.core.satAdd
import dev.abdulkadirozyurt.srtla.core.satMul
import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_DECR
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_INCR
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_MAX
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_MIN
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_MULT
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.congestion")

private const val NAK_BURST_WINDOW_MS: Long = 1000L
private const val NAK_BURST_LOG_THRESHOLD: Int = 5

private const val NORMAL_MIN_WAIT_MS: Long = 2000L
private const val FAST_MIN_WAIT_MS: Long = 500L
private const val NORMAL_INCREMENT_WAIT_MS: Long = 1000L
private const val FAST_INCREMENT_WAIT_MS: Long = 300L
internal const val FAST_RECOVERY_DISABLE_WINDOW: Int = 12_000

/**
 * RTT velocity (ms/sample) above which time-based recovery is halved. The Kalman
 * velocity has no dt term, so this is calibrated against the sampling cadence.
 */
internal const val RTT_VELOCITY_GATE_THRESHOLD: Double = 2.0

/** Mutable integer reference used as an output parameter for window updates. */
class IntRef(var value: Int)

/** Congestion control and NAK tracking state. */
class CongestionControl {
    var nakCount: Int = 0
    var lastNakTimeMs: Long = 0L
    var lastWindowIncreaseMs: Long = 0L
    var consecutiveAcksWithoutNak: Int = 0
    var fastRecoveryMode: Boolean = false
    var fastRecoveryStartMs: Long = 0L
    var nakBurstCount: Int = 0
    var nakBurstStartTimeMs: Long = 0L

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

    /** Handle a NAK (common to classic and enhanced). Shrinks the window. */
    fun handleNak(window: IntRef, seq: Int, label: String, nowMs: Long): Boolean {
        nakCount = nakCount.satAdd(1)
        val timeSinceLastNak = nowMs.satSub(lastNakTimeMs)

        if (lastNakTimeMs > 0L && timeSinceLastNak < NAK_BURST_WINDOW_MS) {
            if (nakBurstCount == 0) {
                nakBurstCount = 2
                nakBurstStartTimeMs = lastNakTimeMs
            } else {
                nakBurstCount = nakBurstCount.satAdd(1)
            }
        } else {
            if (nakBurstCount >= NAK_BURST_LOG_THRESHOLD) {
                log.warning("$label: NAK burst ended - $nakBurstCount NAKs in ${nowMs.satSub(nakBurstStartTimeMs)}ms")
            }
            nakBurstCount = 0
            nakBurstStartTimeMs = 0L
        }

        lastNakTimeMs = nowMs
        consecutiveAcksWithoutNak = 0

        val oldWindow = window.value
        window.value = maxOf(window.value - WINDOW_DECR, WINDOW_MIN * WINDOW_MULT)

        if (window.value <= 3000) {
            val burstInfo = if (nakBurstCount > 1) " [BURST: $nakBurstCount NAKs]" else ""
            log.warning("$label: NAK reduced window $oldWindow → ${window.value} (seq=$seq, total_naks=$nakCount$burstInfo)")
        }

        if (window.value <= 2000 && !fastRecoveryMode) {
            fastRecoveryMode = true
            fastRecoveryStartMs = nowMs
            log.warning("$label: Enabling FAST RECOVERY MODE - window ${window.value}")
        }
        return true
    }

    /**
     * Classic SRTLA ACK (C lines 291-293): grow by WINDOW_INCR - 1 only while
     * in_flight * WINDOW_MULT exceeds the window. The product saturates so an
     * extreme in-flight count cannot wrap negative and flip the verdict.
     */
    fun handleSrtlaAckSpecificClassic(window: IntRef, inFlightPackets: Int, seq: Int, label: String) {
        if (inFlightPackets.satMul(WINDOW_MULT) > window.value) {
            val old = window.value
            window.value = minOf(window.value + WINDOW_INCR - 1, WINDOW_MAX * WINDOW_MULT)
            log.fine { "$label: SRTLA ACK specific increased window $old → ${window.value} (seq=$seq, in_flight=$inFlightPackets) [CLASSIC]" }
        }
    }

    /** Enhanced SRTLA ACK: identical growth to classic, plus fast-recovery exit. */
    fun handleSrtlaAckEnhanced(window: IntRef, inFlightPackets: Int, label: String, nowMs: Long) {
        if (inFlightPackets.satMul(WINDOW_MULT) > window.value) {
            val old = window.value
            window.value = minOf(window.value + WINDOW_INCR - 1, WINDOW_MAX * WINDOW_MULT)
            if (old != window.value && old <= 10_000) {
                log.fine { "$label: ACK increased window $old → ${window.value} (in_flight=$inFlightPackets, fast_mode=$fastRecoveryMode) [ENHANCED]" }
            }
        }
        if (fastRecoveryMode && window.value >= FAST_RECOVERY_DISABLE_WINDOW) {
            fastRecoveryMode = false
            log.fine { "$label: Disabling FAST RECOVERY MODE after enhanced ACK recovery (window=${window.value}, duration=${nowMs.satSub(fastRecoveryStartMs)}ms)" }
        }
    }

    /**
     * Time-based window recovery (enhanced only). Rate depends on time since the
     * last NAK (10s+: 2x, 7s+: 1x, 5s+: 0.5x, else 0.25x of WINDOW_INCR), doubled
     * in fast recovery, halved while RTT velocity exceeds the gate threshold.
     * "Never had a NAK" counts as a very long time, so a fresh healthy link that
     * gets little ACK-driven growth still recovers.
     */
    fun performWindowRecovery(window: IntRef, connected: Boolean, rttVelocity: Double, label: String, nowMs: Long) {
        if (!connected || window.value >= WINDOW_MAX * WINDOW_MULT) return

        val timeSinceLastNak = if (lastNakTimeMs > 0L) nowMs.satSub(lastNakTimeMs) else Long.MAX_VALUE

        if (timeSinceLastNak >= NAK_BURST_WINDOW_MS && nakBurstCount > 0) {
            nakBurstCount = 0
            nakBurstStartTimeMs = 0L
        }

        val minWaitTime = if (fastRecoveryMode) FAST_MIN_WAIT_MS else NORMAL_MIN_WAIT_MS
        val incrementWait = if (fastRecoveryMode) FAST_INCREMENT_WAIT_MS else NORMAL_INCREMENT_WAIT_MS

        if (timeSinceLastNak > minWaitTime && nowMs.satSub(lastWindowIncreaseMs) > incrementWait) {
            val oldWindow = window.value
            val fastModeBonus = if (fastRecoveryMode) 2 else 1
            val velocityScale = if (rttVelocity > RTT_VELOCITY_GATE_THRESHOLD) {
                log.fine { "$label: RTT velocity ${"%.2f".format(rttVelocity)} ms/sample > threshold, halving recovery rate" }
                0.5
            } else {
                1.0
            }
            val baseIncr = when {
                timeSinceLastNak > 10_000L -> WINDOW_INCR * 2 * fastModeBonus
                timeSinceLastNak > 7_000L -> WINDOW_INCR * fastModeBonus
                timeSinceLastNak > 5_000L -> WINDOW_INCR * fastModeBonus / 2
                else -> WINDOW_INCR * fastModeBonus / 4
            }
            window.value += (baseIncr.toDouble() * velocityScale).toInt()
            window.value = minOf(window.value, WINDOW_MAX * WINDOW_MULT)
            lastWindowIncreaseMs = nowMs

            if (window.value > oldWindow) {
                val timeStr = if (lastNakTimeMs == 0L) "never" else "%.1fs".format(timeSinceLastNak / 1000.0)
                log.fine { "$label: Time-based window recovery $oldWindow → ${window.value} (last NAK: $timeStr, fast_mode=$fastRecoveryMode, vel=${"%.2f".format(rttVelocity)}ms/sample)" }
            }
            if (fastRecoveryMode && window.value >= FAST_RECOVERY_DISABLE_WINDOW) {
                fastRecoveryMode = false
                log.fine { "$label: Disabling FAST RECOVERY MODE after time-based recovery (window=${window.value})" }
            }
        }
    }

    /** Time since the last NAK in ms, or null when there has been none. */
    fun timeSinceLastNakMs(nowMs: Long): Long? =
        if (lastNakTimeMs == 0L) null else nowMs.satSub(lastNakTimeMs)
}
