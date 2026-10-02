// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/config.rs
//
// Runtime configuration that can be flipped while streaming. Backed by atomics
// for lock-free reads; the event loop calls snapshot() once per select.
package dev.abdulkadirozyurt.srtla.config

import dev.abdulkadirozyurt.srtla.core.CONN_TIMEOUT_MS
import dev.abdulkadirozyurt.srtla.core.CONN_TIMEOUT_MS_MAX
import dev.abdulkadirozyurt.srtla.core.CONN_TIMEOUT_MS_MIN
import dev.abdulkadirozyurt.srtla.core.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.core.STALL_ACK_STALE_MS
import dev.abdulkadirozyurt.srtla.core.STALL_MIN_IN_FLIGHT_PACKETS
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DynamicConfig(
    mode: SchedulingMode = SchedulingMode.ENHANCED,
    qualityEnabled: Boolean = true,
    stallDeselect: Boolean = true,
    stallMinInFlight: Int = STALL_MIN_IN_FLIGHT_PACKETS,
    stallAckStaleMs: Long = STALL_ACK_STALE_MS,
    connTimeoutMs: Long = CONN_TIMEOUT_MS,
    rehomeOnFailure: Boolean = true,
) {
    private val mode = AtomicReference(mode)
    private val qualityEnabled = AtomicBoolean(qualityEnabled)
    private val stallDeselect = AtomicBoolean(stallDeselect)
    private val stallMinInFlight = AtomicInteger(stallMinInFlight)
    private val stallAckStaleMs = AtomicLong(stallAckStaleMs)
    private val connTimeoutMs = AtomicLong(connTimeoutMs.coerceIn(CONN_TIMEOUT_MS_MIN, CONN_TIMEOUT_MS_MAX))

    /**
     * Whole-bond re-home on a dead bond whose receiver hostname moved. Not part
     * of ConfigSnapshot: read once at sender start to build the re-home gate.
     */
    private val rehomeOnFailure = AtomicBoolean(rehomeOnFailure)

    /** Learned from the wire, not configured: see [setNegotiatedLatencyMs]. */
    private val negotiatedLatencyMs = AtomicInteger(0)

    fun rehomeOnFailure(): Boolean = rehomeOnFailure.get()

    /** Immutable view for one select iteration. */
    fun snapshot(): ConfigSnapshot = ConfigSnapshot(
        mode = mode.get(),
        qualityEnabled = qualityEnabled.get(),
        stallDeselect = stallDeselect.get(),
        stallMinInFlight = stallMinInFlight.get(),
        stallAckStaleMs = stallAckStaleMs.get(),
        connTimeoutMs = connTimeoutMs.get(),
        negotiatedLatencyMs = negotiatedLatencyMs.get(),
    )

    /**
     * Record the TSBPD receive delay the far-end SRT listener declared in its
     * handshake response. Returns true when the stored value changed.
     */
    fun setNegotiatedLatencyMs(latencyMs: Int): Boolean = negotiatedLatencyMs.getAndSet(latencyMs) != latencyMs

    fun mode(): SchedulingMode = mode.get()

    fun setMode(mode: SchedulingMode) = this.mode.set(mode)

    fun setQualityEnabled(enabled: Boolean) = qualityEnabled.set(enabled)

    fun setStallDeselect(enabled: Boolean) = stallDeselect.set(enabled)

    /**
     * Set the liveness timeout, clamped to [CONN_TIMEOUT_MS_MIN]..[CONN_TIMEOUT_MS_MAX].
     * Returns the applied value. A latency-aware client scales it to
     * max(default, 2 x SRT latency) so an absorbable outage resumes warm.
     */
    fun setConnTimeoutMs(ms: Long): Long {
        val applied = ms.coerceIn(CONN_TIMEOUT_MS_MIN, CONN_TIMEOUT_MS_MAX)
        connTimeoutMs.set(applied)
        return applied
    }

    companion object {
        /** Build from CLI arguments (Rust `DynamicConfig::from_cli`). */
        fun fromCli(
            mode: SchedulingMode,
            noQuality: Boolean,
            noStallDeselect: Boolean,
            stallMinInFlight: Int,
            stallAckStaleMs: Long,
            connTimeoutMs: Long,
            noRehome: Boolean,
        ): DynamicConfig = DynamicConfig(
            mode = mode,
            qualityEnabled = !noQuality,
            stallDeselect = !noStallDeselect,
            stallMinInFlight = stallMinInFlight,
            stallAckStaleMs = stallAckStaleMs,
            connTimeoutMs = connTimeoutMs,
            rehomeOnFailure = !noRehome,
        )
    }
}
