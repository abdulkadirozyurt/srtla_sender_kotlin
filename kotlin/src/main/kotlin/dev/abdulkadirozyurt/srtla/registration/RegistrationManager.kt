// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/registration/mod.rs, src/registration/probing.rs
//
// REG1 → REG2 → REG3 state machine.
// REG2 timeout: REG2_TIMEOUT (4s). REG3 timeout: REG3_TIMEOUT (4s).
// CONN timeout: CONN_TIMEOUT (5s).
// ID: SecureRandom, SRTLA_ID_LEN (256) bytes.
// Probing: send REG2 to all uplinks with unique probe ID, pick lowest RTT.
package dev.abdulkadirozyurt.srtla.registration

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.protocol.*
import java.security.SecureRandom

// Probing state enum — mirrors Rust probing.rs::ProbingState
internal enum class ProbingState {
    NotStarted, Probing, WaitingForProbes, Complete
}

internal data class ProbeResult(
    val connIdx: Int,
    val probeSentMs: Long,
    var rttMs: Long? = null,
)

/**
 * SRTLA registration state machine.
 * Mirrors Rust `SrtlaRegistrationManager` in src/registration/mod.rs.
 *
 * REG1 → server sends REG_NGP (no good path) to select sender
 * REG1 → server replies REG2 (echo ID back, possibly modified)
 * sender broadcasts REG2 to ALL uplinks → server sends REG3 to each
 * registration complete when REG3 received.
 */
class RegistrationManager {
    val srtlaId: ByteArray = ByteArray(SRTLA_ID_LEN).also { SecureRandom().nextBytes(it) }

    private var pendingReg2Idx: Int? = null
    var pendingTimeoutAtMs: Long = 0L
    var activeConnections: Int = 0
    var hasConnected: Boolean = false
    var broadcastReg2Pending: Boolean = false
    var reg1TargetIdx: Int? = null
    var reg1NextSendAtMs: Long = 0L

    // Probing state (src/registration/probing.rs)
    private var probingState: ProbingState = ProbingState.NotStarted
    private val probeId: ByteArray = ByteArray(SRTLA_ID_LEN).also { SecureRandom().nextBytes(it) }
    private val probeResults: MutableList<ProbeResult> = mutableListOf()

    // ── Public accessors (for tests) ────────────────────────────────────────

    fun pendingReg2Idx(): Int? = pendingReg2Idx
    fun setPendingReg2Idx(v: Int?) { pendingReg2Idx = v }
    fun testSetPendingTimeoutAtMs(v: Long) { pendingTimeoutAtMs = v }
    fun testSetReg1TargetIdx(v: Int?) { reg1TargetIdx = v }
    fun testSetReg1NextSendAtMs(v: Long) { reg1NextSendAtMs = v }
    fun testSetBroadcastReg2Pending(v: Boolean) { broadcastReg2Pending = v }
    fun probeResultsCount(): Int = probeResults.size
    fun isProbing(): Boolean = probingState == ProbingState.Probing
                            || probingState == ProbingState.WaitingForProbes
    fun setProbeStateWaiting() {
        probingState = ProbingState.WaitingForProbes
        pendingTimeoutAtMs = System.currentTimeMillis() + 2000L
    }
    fun simulateProbeResult(connIdx: Int, rttMs: Long) {
        val now = System.currentTimeMillis()
        probeResults += ProbeResult(connIdx, probeSentMs = now - rttMs, rttMs = rttMs)
    }

    // ── Registration packet processing ─────────────────────────────────────

    sealed class RegistrationEvent {
        object RegNgp : RegistrationEvent()
        object Reg2   : RegistrationEvent()
        object Reg3   : RegistrationEvent()
        object RegErr : RegistrationEvent()
    }

    /**
     * Process an incoming packet from uplink [connIdx].
     * Returns a RegistrationEvent if the packet is a registration message, null otherwise.
     * Mirrors Rust `SrtlaRegistrationManager::process_registration_packet`.
     */
    fun processRegistrationPacket(connIdx: Int, buf: ByteArray): RegistrationEvent? {
        return when (getPacketType(buf)) {
            SRTLA_TYPE_REG_NGP -> { handleRegNgp(connIdx); RegistrationEvent.RegNgp }
            SRTLA_TYPE_REG2    -> { handleReg2(connIdx, buf); RegistrationEvent.Reg2 }
            SRTLA_TYPE_REG3    -> { handleReg3(connIdx); RegistrationEvent.Reg3 }
            SRTLA_TYPE_REG_ERR -> { handleRegErr(connIdx); RegistrationEvent.RegErr }
            else               -> null
        }
    }

    private fun handleRegNgp(connIdx: Int) {
        if (probingState == ProbingState.WaitingForProbes) {
            handleProbeResponse(connIdx)
            return
        }
        if (activeConnections == 0 && pendingReg2Idx == null) {
            reg1TargetIdx = connIdx
            reg1NextSendAtMs = System.currentTimeMillis()
        }
    }

    private fun handleReg2(connIdx: Int, buf: ByteArray) {
        if (buf.size < 2 + SRTLA_ID_LEN) return
        if (pendingReg2Idx == connIdx) {
            // Server returns full ID starting at byte 2
            buf.copyInto(srtlaId, destinationOffset = 0, startIndex = 2, endIndex = 2 + SRTLA_ID_LEN)
            pendingReg2Idx = null
            pendingTimeoutAtMs = System.currentTimeMillis() + REG3_TIMEOUT * 1000L
            broadcastReg2Pending = true
            reg1TargetIdx = null
            reg1NextSendAtMs = 0L
        }
    }

    private fun handleReg3(@Suppress("UNUSED_PARAMETER") connIdx: Int) {
        hasConnected = true
    }

    private fun handleRegErr(connIdx: Int) {
        if (pendingReg2Idx == connIdx) { /* log only */ }
        pendingReg2Idx = null
        pendingTimeoutAtMs = 0L
        reg1TargetIdx = null
        // Wait for fresh REG_NGP before retrying
        reg1NextSendAtMs = System.currentTimeMillis() + REG2_TIMEOUT * 1000L
    }

    // ── Registration driver (sends REG1 / broadcasts REG2) ─────────────────

    /**
     * Called each housekeeping cycle to advance the registration state machine.
     * Mirrors Rust `SrtlaRegistrationManager::reg_driver_send_if_needed`.
     */
    fun regDriverSendIfNeeded(connections: List<SrtlaConnection>) {
        // If nothing connected yet, send REG1 to the selected target
        if (activeConnections == 0) {
            val idx = reg1TargetIdx
            if (idx != null) {
                val now = System.currentTimeMillis()
                if (pendingReg2Idx == null && now >= reg1NextSendAtMs) {
                    val pkt = createReg1Packet(srtlaId)
                    connections.getOrNull(idx)?.sendPacket(pkt)
                    pendingReg2Idx = idx
                    pendingTimeoutAtMs = now + REG2_TIMEOUT * 1000L
                    reg1NextSendAtMs = now + REG2_TIMEOUT * 1000L
                }
            }
        }

        // Broadcast REG2 to all uplinks when flag is set
        if (broadcastReg2Pending) {
            val pkt = createReg2Packet(srtlaId)
            for (conn in connections) conn.sendPacket(pkt)
            broadcastReg2Pending = false
        }
    }

    /**
     * Send REG1 to a specific uplink immediately.
     * Mirrors Rust `SrtlaRegistrationManager::send_reg1_to`.
     */
    fun sendReg1To(connIdx: Int, conn: SrtlaConnection) {
        val pkt = createReg1Packet(srtlaId)
        conn.sendPacket(pkt)
        val now = System.currentTimeMillis()
        pendingReg2Idx = connIdx
        reg1TargetIdx = connIdx
        pendingTimeoutAtMs = now + REG2_TIMEOUT * 1000L
        reg1NextSendAtMs = now + 1000L
    }

    /**
     * Send REG2 to a specific uplink.
     * Mirrors Rust `SrtlaRegistrationManager::send_reg2_to`.
     */
    fun sendReg2To(conn: SrtlaConnection) {
        val pkt = createReg2Packet(srtlaId)
        conn.sendPacket(pkt)
    }

    /**
     * Try to send REG1 immediately if conditions are met (called on REG_NGP).
     * Mirrors Rust `SrtlaRegistrationManager::try_send_reg1_immediately`.
     */
    fun trySendReg1Immediately(connIdx: Int, conn: SrtlaConnection) {
        if (activeConnections == 0
            && pendingReg2Idx == null
            && reg1TargetIdx == connIdx
            && System.currentTimeMillis() >= reg1NextSendAtMs) {
            sendReg1To(connIdx, conn)
        }
    }

    /**
     * Check and clear REG2 wait if timeout exceeded.
     * Mirrors Rust `SrtlaRegistrationManager::clear_pending_if_timed_out`.
     */
    fun clearPendingIfTimedOut(nowMs: Long): Int? {
        val idx = pendingReg2Idx ?: return null
        if (pendingTimeoutAtMs != 0L && nowMs >= pendingTimeoutAtMs) {
            pendingReg2Idx = null
            pendingTimeoutAtMs = 0L
            reg1TargetIdx = null
            reg1NextSendAtMs = nowMs
            return idx
        }
        return null
    }

    /**
     * Recalculate active connection count from actual connection state.
     * Mirrors Rust `SrtlaRegistrationManager::update_active_connections`.
     */
    fun updateActiveConnections(connections: List<SrtlaConnection>) {
        activeConnections = connections.count { it.connected }
    }

    // ── Probing (src/registration/probing.rs) ───────────────────────────────

    /**
     * Start RTT probing: send probe REG2 to all uplinks, pick lowest RTT.
     * Mirrors Rust `SrtlaRegistrationManager::start_probing`.
     */
    fun startProbing(connections: List<SrtlaConnection>) {
        if (probingState != ProbingState.NotStarted || activeConnections > 0) return
        probingState = ProbingState.Probing
        probeResults.clear()

        val probeStart = System.currentTimeMillis()
        for ((idx, conn) in connections.withIndex()) {
            try {
                val pkt = createReg2Packet(probeId)
                conn.sendPacket(pkt)
                val sentMs = System.currentTimeMillis()
                conn.reconnection.startupGraceDeadlineMs = sentMs + 5_000L
                probeResults += ProbeResult(connIdx = idx, probeSentMs = sentMs)
            } catch (_: Exception) {}
        }

        if (probeResults.isNotEmpty()) {
            probingState = ProbingState.WaitingForProbes
            pendingTimeoutAtMs = System.currentTimeMillis() + 2000L
        } else {
            probingState = ProbingState.Complete
            if (connections.isNotEmpty()) {
                reg1TargetIdx = 0
                reg1NextSendAtMs = probeStart
            }
        }
    }

    /** Record a probe response from uplink [connIdx]. */
    fun handleProbeResponse(connIdx: Int) {
        if (probingState != ProbingState.WaitingForProbes) return
        val now = System.currentTimeMillis()
        val result = probeResults.find { it.connIdx == connIdx && it.rttMs == null }
        if (result != null) {
            result.rttMs = (now - result.probeSentMs).coerceAtLeast(0L)
        }
    }

    /**
     * Check if probing is complete (all responded or timeout).
     * Returns true if probing just completed.
     * Mirrors Rust `SrtlaRegistrationManager::check_probing_complete`.
     */
    fun checkProbingComplete(): Boolean {
        if (probingState != ProbingState.WaitingForProbes) return false
        val now = System.currentTimeMillis()
        val allResponded = probeResults.all { it.rttMs != null }
        val timedOut = now >= pendingTimeoutAtMs

        if (!allResponded && !timedOut) return false

        val best = probeResults.filter { it.rttMs != null }.minByOrNull { it.rttMs!! }
        reg1TargetIdx = best?.connIdx ?: 0
        reg1NextSendAtMs = now
        probingState = ProbingState.Complete
        pendingTimeoutAtMs = 0L
        return true
    }

    fun getSelectedConnectionIdx(): Int? = reg1TargetIdx
}
