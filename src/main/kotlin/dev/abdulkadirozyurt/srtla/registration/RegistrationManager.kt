// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/registration/{mod,probing}.rs
//
// REG1 → REG2 → REG3 state machine, sans-IO: methods return the packets to
// send and the shell transmits them.
//
// Registration frames carry no authentication, and uplink sockets are
// deliberately unconnected (accept any source), so anything that reaches an
// uplink's port can forge one. A REG3 or REG_ERR is therefore accepted only
// while the addressed uplink has that phase of the handshake in flight;
// anything else is counted and reported as "not a registration packet".
package dev.abdulkadirozyurt.srtla.registration

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.protocol.REG2_TIMEOUT
import dev.abdulkadirozyurt.srtla.protocol.REG3_TIMEOUT
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_ID_LEN
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG2
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG3
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG_ERR
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_REG_NGP
import dev.abdulkadirozyurt.srtla.protocol.createReg1Packet
import dev.abdulkadirozyurt.srtla.protocol.createReg2Packet
import dev.abdulkadirozyurt.srtla.protocol.getPacketType
import java.security.SecureRandom
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.registration")
private val rng = SecureRandom()

private fun randomId(): ByteArray = ByteArray(SRTLA_ID_LEN).also { rng.nextBytes(it) }

enum class RegistrationEvent { REG_NGP, REG2, REG3, REG_ERR }

internal enum class ProbingState { NOT_STARTED, PROBING, WAITING_FOR_PROBES, COMPLETE }

internal class ProbeResult(val connIdx: Int, val probeSentMs: Long, var rttMs: Long? = null)

/** Packets the registration driver decided to send this tick. */
class RegDriverSends(
    /** REG1 to a single target uplink: (connIdx, packet). */
    val reg1: Pair<Int, ByteArray>?,
    /** REG2 to broadcast to every uplink. */
    val broadcastReg2: ByteArray?,
)

class RegistrationManager {
    val srtlaId: ByteArray = randomId()
    private var pendingReg2Idx: Int? = null
    var pendingTimeoutAtMs: Long = 0L
        internal set
    var activeConnections: Int = 0
        internal set
    var hasConnected: Boolean = false
    var broadcastReg2Pending: Boolean = false
        internal set
    var reg1TargetIdx: Int? = null
        internal set
    var reg1NextSendAtMs: Long = 0L
        internal set

    private var probingState = ProbingState.NOT_STARTED
    private var probeId: ByteArray = randomId()
    private val probeResults = ArrayList<ProbeResult>(4)

    /**
     * Uplink indices with a REG2 queued. A REG3 is honored only for a member, and
     * the grant is one-shot: a duplicate or replayed REG3 cannot re-fire the
     * "just registered" effects that wipe a live link's state. Indices are
     * positional into the shell's connection list.
     */
    private val awaitingReg3 = ArrayList<Int>(4)

    /** connected flags snapshotted by updateActiveConnections, same indexing. */
    private val connectedSnapshot = ArrayList<Boolean>(4)

    var outOfPhaseReg3: Long = 0L
        private set
    var outOfPhaseRegErr: Long = 0L
        private set

    /**
     * Wind the handshake back to pre-REG1 for a whole-bond re-home.
     *
     * The receiver-issued half of srtlaId is re-randomized (it only meant
     * something to the instance that minted it); our own half, the only part any
     * receiver reads from a REG1, is kept so a receiver that derives its half
     * deterministically reissues the same full id. Everything else is cleared.
     * hasConnected survives: it only drives log wording.
     */
    fun resetForRehome() {
        val fresh = randomId()
        fresh.copyInto(srtlaId, destinationOffset = SRTLA_ID_LEN / 2, startIndex = SRTLA_ID_LEN / 2)
        pendingReg2Idx = null
        pendingTimeoutAtMs = 0L
        activeConnections = 0
        broadcastReg2Pending = false
        reg1TargetIdx = null
        reg1NextSendAtMs = 0L
        awaitingReg3.clear()
        connectedSnapshot.clear()
        probingState = ProbingState.NOT_STARTED
        probeId = randomId()
        probeResults.clear()
    }

    /**
     * Arm the one-shot REG3 grant. Armed when a REG2 is queued, not when it
     * provably left the host: over-arming only admits a REG3 for an uplink we did
     * try to register, while under-arming would refuse a legitimate one.
     */
    private fun armReg3Grant(connIdx: Int) {
        if (!awaitingReg3.contains(connIdx)) awaitingReg3.add(connIdx)
    }

    private fun revokeReg3Grant(connIdx: Int) {
        awaitingReg3.removeAll { it == connIdx }
    }

    private fun isConnectedSnapshot(connIdx: Int): Boolean = connectedSnapshot.getOrNull(connIdx) ?: false

    /** Build a REG1 for [connIdx] and move into "awaiting REG2". */
    fun buildReg1For(connIdx: Int, now: Long): ByteArray {
        val pkt = createReg1Packet(srtlaId)
        log.info("REG1 → uplink #$connIdx (${pkt.size} bytes)")
        pendingReg2Idx = connIdx
        reg1TargetIdx = connIdx
        pendingTimeoutAtMs = now + REG2_TIMEOUT * 1000L
        reg1NextSendAtMs = now + 1000L
        return pkt
    }

    /**
     * Build a REG2 from the current id and arm this uplink's one-shot REG3 grant.
     * This is the per-uplink reconnect resend, so it arms unconditionally.
     */
    fun buildReg2(connIdx: Int): ByteArray {
        val pkt = createReg2Packet(srtlaId)
        log.info("REG2 → uplink #$connIdx (${pkt.size} bytes)")
        armReg3Grant(connIdx)
        return pkt
    }

    /**
     * Classify and apply a registration packet. An out-of-phase REG3/REG_ERR
     * returns null: "consumed, change nothing".
     */
    fun processRegistrationPacket(connIdx: Int, buf: ByteArray, len: Int, nowMs: Long): RegistrationEvent? =
        when (getPacketType(buf, len)) {
            SRTLA_TYPE_REG_NGP -> {
                log.fine { "REG_NGP from uplink #$connIdx" }
                handleRegNgp(connIdx, nowMs)
                RegistrationEvent.REG_NGP
            }
            SRTLA_TYPE_REG2 -> {
                log.fine { "REG2 from uplink #$connIdx (len=$len)" }
                handleReg2(connIdx, buf, len, nowMs)
                RegistrationEvent.REG2
            }
            SRTLA_TYPE_REG3 -> {
                log.fine { "REG3 from uplink #$connIdx" }
                if (handleReg3(connIdx)) RegistrationEvent.REG3 else null
            }
            SRTLA_TYPE_REG_ERR -> {
                log.fine { "REG_ERR from uplink #$connIdx" }
                if (handleRegErr(connIdx, nowMs)) RegistrationEvent.REG_ERR else null
            }
            else -> null
        }

    fun processRegistrationPacket(connIdx: Int, buf: ByteArray, nowMs: Long): RegistrationEvent? =
        processRegistrationPacket(connIdx, buf, buf.size, nowMs)

    /**
     * Decide this tick's registration sends and advance the state machine. The
     * caller transmits REG1 to `reg1.first` and broadcasts REG2 to every uplink.
     */
    fun regDriverPendingSends(connectionCount: Int, now: Long): RegDriverSends {
        var reg1: Pair<Int, ByteArray>? = null
        var broadcast: ByteArray? = null

        if (activeConnections == 0) {
            val idx = reg1TargetIdx
            if (idx != null) {
                if (pendingReg2Idx == null && now >= reg1NextSendAtMs) {
                    val pkt = createReg1Packet(srtlaId)
                    log.info("REG1 → uplink #$idx (${pkt.size} bytes)")
                    pendingReg2Idx = idx
                    pendingTimeoutAtMs = now + REG2_TIMEOUT * 1000L
                    reg1NextSendAtMs = now + REG2_TIMEOUT * 1000L
                    reg1 = idx to pkt
                } else if (pendingReg2Idx != null) {
                    log.fine { "REG1 pending for uplink #$idx (timeout at $pendingTimeoutAtMs), skipping send" }
                }
            } else {
                log.fine { "No REG1 target selected; awaiting REG_NGP" }
            }
        }

        if (broadcastReg2Pending) {
            val pkt = createReg2Packet(srtlaId)
            log.info("broadcast REG2 to $connectionCount uplinks (${pkt.size} bytes)")
            for (idx in 0 until connectionCount) {
                // Re-arming an established uplink would reopen the one-shot gate,
                // and the REG3 this broadcast provokes would wipe a live link.
                if (isConnectedSnapshot(idx)) {
                    log.fine { "REG2 → uplink #$idx not re-armed (already connected)" }
                    continue
                }
                armReg3Grant(idx)
            }
            broadcast = pkt
            broadcastReg2Pending = false
        }
        return RegDriverSends(reg1, broadcast)
    }

    private fun handleRegNgp(connIdx: Int, nowMs: Long) {
        if (probingState == ProbingState.WAITING_FOR_PROBES) {
            handleProbeResponse(connIdx, nowMs)
            return
        }
        if (activeConnections == 0 && pendingReg2Idx == null) {
            log.fine { "REG_NGP from uplink #$connIdx accepted as REG1 target" }
            reg1TargetIdx = connIdx
            reg1NextSendAtMs = nowMs
        } else {
            log.fine { "REG_NGP from uplink #$connIdx ignored (active connections present or pending)" }
        }
    }

    private fun handleReg2(connIdx: Int, buf: ByteArray, len: Int, nowMs: Long) {
        if (len < 2 + SRTLA_ID_LEN) return
        if (pendingReg2Idx == connIdx) {
            buf.copyInto(srtlaId, destinationOffset = 0, startIndex = 2, endIndex = 2 + SRTLA_ID_LEN)
            log.fine { "REG2 from uplink #$connIdx accepted; broadcasting to peers" }
            pendingReg2Idx = null
            pendingTimeoutAtMs = nowMs + REG3_TIMEOUT * 1000L
            broadcastReg2Pending = true
            reg1TargetIdx = null
            reg1NextSendAtMs = 0L
        }
    }

    /** True only when this REG3 answers a REG2 the uplink has in flight. Consumes the grant. */
    private fun handleReg3(connIdx: Int): Boolean {
        if (!awaitingReg3.contains(connIdx)) {
            outOfPhaseReg3++
            logOutOfPhase("REG3", connIdx, outOfPhaseReg3)
            return false
        }
        revokeReg3Grant(connIdx)
        hasConnected = true
        return true
    }

    /**
     * True only when this REG_ERR answers a handshake the uplink has in flight. A
     * forged REG_ERR is the cheapest remote DoS; phase-gating bounds a flood of
     * them at zero effect.
     */
    private fun handleRegErr(connIdx: Int, nowMs: Long): Boolean {
        val awaitingReg2 = pendingReg2Idx == connIdx
        val awaitingReg3Now = awaitingReg3.contains(connIdx)
        if (!awaitingReg2 && !awaitingReg3Now) {
            outOfPhaseRegErr++
            logOutOfPhase("REG_ERR", connIdx, outOfPhaseRegErr)
            return false
        }
        if (awaitingReg2) {
            log.fine { "REG_ERR for uplink #$connIdx while awaiting REG2" }
            pendingReg2Idx = null
            pendingTimeoutAtMs = 0L
            reg1TargetIdx = null
            reg1NextSendAtMs = nowMs + REG2_TIMEOUT * 1000L
        } else {
            log.fine { "REG_ERR for uplink #$connIdx while awaiting REG3" }
        }
        revokeReg3Grant(connIdx)
        log.warning("registration failed for connection $connIdx")
        return true
    }

    /** First rejection of each kind logs loudly; a flood drops to debug. */
    private fun logOutOfPhase(kind: String, connIdx: Int, count: Long) {
        if (count == 1L) {
            log.warning("$kind for uplink #$connIdx ignored: no matching registration in flight (further out-of-phase frames logged at debug)")
        } else {
            log.fine { "$kind for uplink #$connIdx ignored: no matching registration in flight ($count so far)" }
        }
    }

    /** Build an immediate REG1 answer to a REG_NGP when one is due. */
    fun reg1IfNgpImmediate(connIdx: Int, now: Long): ByteArray? {
        if (activeConnections == 0 && pendingReg2Idx == null && reg1TargetIdx == connIdx && now >= reg1NextSendAtMs) {
            log.fine { "REG_NGP immediate send for uplink #$connIdx" }
            return buildReg1For(connIdx, now)
        }
        return null
    }

    /** Recount connected links (C: recalculated each housekeeping cycle). */
    fun updateActiveConnections(connections: List<SrtlaConnection>) {
        val newCount = connections.count { it.connected }
        if (newCount != activeConnections) {
            if (newCount > activeConnections) {
                log.info("connection established (active=$newCount)")
            } else {
                log.info("connection(s) lost - active connections: $newCount")
            }
        }
        activeConnections = newCount
        connectedSnapshot.clear()
        for (c in connections) connectedSnapshot.add(c.connected)
    }

    fun pendingReg2Idx(): Int? = pendingReg2Idx

    fun clearPendingIfTimedOut(nowMs: Long): Int? {
        val idx = pendingReg2Idx ?: return null
        if (pendingTimeoutAtMs != 0L && nowMs >= pendingTimeoutAtMs) {
            log.warning("REG2 wait exceeded ${REG2_TIMEOUT * 1000}ms for uplink #$idx; clearing pending handshake")
            pendingReg2Idx = null
            pendingTimeoutAtMs = 0L
            reg1TargetIdx = null
            reg1NextSendAtMs = nowMs
            return idx
        }
        return null
    }

    fun getSelectedConnectionIdx(): Int? = reg1TargetIdx

    // ── Probing (probing.rs) ─────────────────────────────────────────────────

    /**
     * Build a REG2 probe for every connection and move to WaitingForProbes.
     * Returns (connIdx, packet) pairs; empty when probing already started or a
     * link is active. Probes carry an id the receiver cannot know, so it answers
     * with REG_NGP, which measures the path RTT.
     */
    fun startProbing(connections: List<SrtlaConnection>, now: Long): List<Pair<Int, ByteArray>> {
        if (probingState != ProbingState.NOT_STARTED || activeConnections > 0) return emptyList()
        log.info("Starting RTT probing for ${connections.size} connections")
        probingState = ProbingState.PROBING
        probeResults.clear()
        val probes = ArrayList<Pair<Int, ByteArray>>(connections.size)
        for ((idx, conn) in connections.withIndex()) {
            probes.add(idx to conn.probeReg2Packet(probeId, now))
            probeResults.add(ProbeResult(idx, now))
        }
        if (probeResults.isNotEmpty()) {
            probingState = ProbingState.WAITING_FOR_PROBES
            pendingTimeoutAtMs = now + 2000L
            log.info("Waiting for probe responses from ${probeResults.size} connections")
        } else {
            log.warning("No connections available for probing - using first connection as fallback")
            probingState = ProbingState.COMPLETE
        }
        return probes
    }

    fun handleProbeResponse(connIdx: Int, now: Long) {
        if (probingState != ProbingState.WAITING_FOR_PROBES) return
        val r = probeResults.firstOrNull { it.connIdx == connIdx } ?: return
        if (r.rttMs == null) {
            val rtt = now.satSub(r.probeSentMs)
            r.rttMs = rtt
            log.info("Probe response from connection #$connIdx (RTT: ${rtt}ms)")
        }
    }

    /** Finish probing once every probe answered or the 2 s timeout passed. */
    fun checkProbingComplete(now: Long = nowMs()): Boolean {
        if (probingState != ProbingState.WAITING_FOR_PROBES) return false
        val allResponded = probeResults.all { it.rttMs != null }
        val timedOut = now >= pendingTimeoutAtMs
        if (!allResponded && !timedOut) return false

        val responded = probeResults.count { it.rttMs != null }
        if (timedOut) {
            log.info("Probe timeout reached - $responded of ${probeResults.size} connections responded")
        } else {
            log.info("All $responded probe responses received")
        }
        val best = probeResults.filter { it.rttMs != null }.minByOrNull { it.rttMs!! }
        if (best != null) {
            reg1TargetIdx = best.connIdx
            log.info("Selected connection #${best.connIdx} for initial registration (RTT: ${best.rttMs}ms)")
        } else {
            log.warning("No connections responded to probes - will use first connection")
            reg1TargetIdx = 0
        }
        reg1NextSendAtMs = now
        probingState = ProbingState.COMPLETE
        pendingTimeoutAtMs = 0L
        return true
    }

    fun isProbing(): Boolean =
        probingState == ProbingState.PROBING || probingState == ProbingState.WAITING_FOR_PROBES

    // ── Test accessors (Rust: cfg(test-internals)) ───────────────────────────

    fun setPendingReg2Idx(v: Int?) { pendingReg2Idx = v }
    fun setPendingTimeoutAtMs(v: Long) { pendingTimeoutAtMs = v }
    fun setReg1TargetIdx(v: Int?) { reg1TargetIdx = v }
    fun setReg1NextSendAtMs(v: Long) { reg1NextSendAtMs = v }
    fun setBroadcastReg2Pending(v: Boolean) { broadcastReg2Pending = v }
    fun isAwaitingReg3(connIdx: Int): Boolean = awaitingReg3.contains(connIdx)
    /** Arm the REG3 grant without building a REG2 ("a REG2 is in flight"). */
    fun armReg3Gate(connIdx: Int) = armReg3Grant(connIdx)
    fun probeResultsCount(): Int = probeResults.size

    fun simulateProbeResult(connIdx: Int, rttMs: Long, now: Long = nowMs()) {
        probeResults.add(ProbeResult(connIdx, now.satSub(rttMs), rttMs))
    }

    fun setProbingStateWaiting(now: Long = nowMs()) {
        probingState = ProbingState.WAITING_FOR_PROBES
        pendingTimeoutAtMs = now + 2000L
    }
}
