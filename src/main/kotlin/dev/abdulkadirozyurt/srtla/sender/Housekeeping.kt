// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/housekeeping.rs
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.connection.STARTUP_GRACE_MS
import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.satSub
import java.io.IOException
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.housekeeping")

/** All links down for this long ends the sender (or re-homes the bond). */
const val GLOBAL_TIMEOUT_MS: Long = 10_000L

/** The bond could not be (re-)established; the sender exits with an error. */
class AllLinksFailedException(message: String) : Exception(message)

/**
 * One housekeeping pass: registration timeouts and probing, per-link reconnect,
 * keepalives, window recovery, bitrate, phase, batch regime, the registration
 * driver, and the all-links-failed timer.
 *
 * The all-failed timer measures time since the links failed, never process
 * uptime: that made a transient all-down blip trip the instant uptime exceeded
 * the window.
 */
@Throws(AllLinksFailedException::class)
fun handleHousekeeping(
    state: SenderState,
    receiverHost: String,
    classic: Boolean,
    nowMs: Long,
    readers: ReaderRegistry,
) {
    val connections = state.connections
    val reg = state.reg
    expireReg2Wait(state, nowMs, readers)
    finishProbingIfDue(state, nowMs)

    for ((i, conn) in connections.withIndex()) {
        if (conn.isTimedOut(nowMs)) {
            if (conn.shouldAttemptReconnect(nowMs)) {
                conn.recordReconnectAttempt(nowMs)
                if (conn.connectionEstablishedMs() == 0L) {
                    log.fine { "${conn.label} initial registration timed out; retrying" }
                } else {
                    log.warning("${conn.label} timed out; attempting full socket reconnection")
                }
                val io = state.connIo[conn.connId]
                if (io != null) {
                    try {
                        reconnectUplink(conn, io, receiverHost, state.seqTracker, nowMs)
                        readers.restartReaderFor(conn, io)
                    } catch (e: IOException) {
                        log.warning("${conn.label} failed to reconnect: ${e.message}")
                        recoverConnection(conn, state.seqTracker)
                    }
                } else {
                    log.warning("${conn.label} has no I/O entry; marking for recovery")
                    recoverConnection(conn, state.seqTracker)
                }

                val pending = reg.pendingReg2Idx()
                when {
                    pending == i -> {
                        log.info("${conn.label} marked for recovery; re-sending REG1")
                        sendOn(state, conn, reg.buildReg1For(i, nowMs), nowMs, "REG1", i)
                    }
                    pending != null -> log.fine { "${conn.label} timed out but another uplink is awaiting REG2; deferring" }
                    // Initial-registration target: the driver re-sends its REG1 this pass.
                    reg.reg1TargetIdx == i -> log.fine { "${conn.label} timed out; REG1 retry comes from the driver" }
                    else -> {
                        log.info("${conn.label} marked for recovery; re-sending REG2")
                        sendOn(state, conn, reg.buildReg2(i), nowMs, "REG2", i)
                    }
                }
            } else {
                log.fine { "${conn.label} timed out but in retry interval" }
            }
            continue
        }

        if (conn.needsKeepalive(nowMs)) sendQuiet(state, conn, conn.keepalivePacket(nowMs))
        if (conn.needsRttMeasurement(nowMs)) sendQuiet(state, conn, conn.keepalivePacket(nowMs))
        if (!classic) conn.performWindowRecovery(nowMs)
        conn.calculateBitrate(nowMs)
        conn.updatePhase(nowMs)
        conn.recomputeBatchRegime()
    }

    reg.updateActiveConnections(connections)

    sendRegistration(state, nowMs, readers)

    val active = connections.count { !it.isTimedOut(nowMs) }
    if (active == 0) {
        if (state.allFailedAt == null) state.allFailedAt = nowMs
        if (reg.hasConnected) log.severe("warning: no available connections")
        val failedAt = state.allFailedAt!!
        if (nowMs.satSub(failedAt) > GLOBAL_TIMEOUT_MS) {
            // The bond is genuinely dead: the only place a whole-bond re-home
            // may consider moving to a newly-resolved receiver address.
            val deadFor = nowMs.satSub(failedAt)
            if (tryRehome(state, readers, receiverHost, deadFor, nowMs)) {
                // The fresh registration gets a full window.
                state.allFailedAt = nowMs
                return
            }
            if (reg.hasConnected) {
                log.severe("Failed to re-establish any connections")
                throw AllLinksFailedException("Failed to re-establish any connections")
            } else {
                log.severe("Failed to establish any initial connections")
                throw AllLinksFailedException("Failed to establish any initial connections")
            }
        }
    } else {
        state.allFailedAt = null
    }
}

/**
 * Event-loop wake at a registration deadline: expire the REG2 wait and send the
 * next REG1 now, without waiting for the 1 s housekeeping tick.
 */
fun handleRegistrationWake(state: SenderState, nowMs: Long, readers: ReaderRegistry) {
    expireReg2Wait(state, nowMs, readers)
    finishProbingIfDue(state, nowMs)
    sendRegistration(state, nowMs, readers)
}

/**
 * Register links added mid-session at once. A new link gets the startup grace
 * meant for the first registration, so it sat 5 s before its first REG2. With a
 * group in place a REG2 is all it needs. This matters most when every other
 * link is gone: the receiver drops an idle SRT peer after ~5 s, so a link that
 * waits out the grace comes back too late to save the stream.
 */
fun registerAddedUplinks(state: SenderState, added: List<SrtlaConnection>, nowMs: Long) {
    val reg = state.reg
    // No group yet, or initial registration owns the handshake: leave it to the driver.
    if (!reg.hasConnected || reg.pendingReg2Idx() != null || reg.reg1TargetIdx != null) return
    for (conn in added) {
        val idx = state.connections.indexOf(conn)
        if (idx < 0) continue
        conn.reconnection.startupGraceDeadlineMs = 0L
        // Later retries follow the normal pacing from here.
        conn.recordReconnectAttempt(nowMs)
        sendOn(state, conn, reg.buildReg2(idx), nowMs, "REG2", idx)
    }
}

/**
 * End RTT probing when every probe answered or the probe timeout passed, and give
 * the chosen link the startup grace. Runs from the registration wake, so REG1
 * leaves in the same pass instead of on the next 1 s housekeeping tick.
 */
private fun finishProbingIfDue(state: SenderState, nowMs: Long) {
    val reg = state.reg
    if (!reg.isProbing() || !reg.checkProbingComplete(nowMs)) return
    val conn = reg.getSelectedConnectionIdx()?.let { state.connections.getOrNull(it) } ?: return
    conn.reconnection.startupGraceDeadlineMs = nowMs + STARTUP_GRACE_MS
    log.fine { "${conn.label}: Reset grace period after being selected for initial registration" }
}

/**
 * On a REG2 wait expiry during initial registration, rebind the target uplink to
 * a fresh socket (new source port) so the next REG1 leaves on a new flow.
 */
private fun expireReg2Wait(state: SenderState, nowMs: Long, readers: ReaderRegistry) {
    val reg = state.reg
    val idx = reg.clearPendingIfTimedOut(nowMs) ?: return
    if (reg.activeConnections != 0) return
    val conn = state.connections.getOrNull(idx) ?: return
    val io = state.connIo[conn.connId] ?: return
    log.info("REG1 retry #${reg.reg1Attempts + 1} on fresh socket (wait ${reg.nextReg2WaitMs()}ms)")
    try {
        rebuildUplinkSocket(conn, io, state.seqTracker, nowMs)
        readers.restartReaderFor(conn, io)
    } catch (e: IOException) {
        log.warning("${conn.label} fresh socket for REG1 retry failed: ${e.message}")
    }
}

/**
 * Send this pass's registration packets: REG1 to the target, REG2 broadcast, and
 * REG2 resends to uplinks still awaiting REG3. A restarted registration rebinds
 * the target uplink first; its fresh REG1 leaves on the next driver pass.
 */
private fun sendRegistration(state: SenderState, nowMs: Long, readers: ReaderRegistry) {
    val connections = state.connections
    val sends = state.reg.regDriverPendingSends(connections.size, nowMs)
    sends.restartUplink?.let { idx ->
        val conn = connections.getOrNull(idx)
        val io = conn?.let { state.connIo[it.connId] }
        if (conn != null && io != null) {
            try {
                rebuildUplinkSocket(conn, io, state.seqTracker, nowMs)
                readers.restartReaderFor(conn, io)
            } catch (e: IOException) {
                log.warning("${conn.label} fresh socket for registration restart failed: ${e.message}")
            }
        }
    }
    sends.reg1?.let { (idx, pkt) ->
        val conn = connections.getOrNull(idx)
        if (conn != null) sendOn(state, conn, pkt, nowMs, "REG1", idx)
    }
    for ((idx, pkt) in sends.reg2Retries) {
        val conn = connections.getOrNull(idx) ?: continue
        sendOn(state, conn, pkt, nowMs, "REG2", idx)
    }
    sends.broadcastReg2?.let { pkt ->
        for ((i, conn) in connections.withIndex()) {
            val io = state.connIo[conn.connId] ?: continue
            try {
                io.socket.send(pkt)
                conn.noteSent(nowMs)
                log.fine { "REG2 → uplink #$i sent" }
            } catch (_: IOException) {
            }
        }
    }
}

private fun sendOn(state: SenderState, conn: dev.abdulkadirozyurt.srtla.connection.SrtlaConnection, pkt: ByteArray, now: Long, what: String, idx: Int) {
    val io = state.connIo[conn.connId] ?: return
    try {
        io.socket.send(pkt)
        conn.noteSent(now)
    } catch (e: IOException) {
        log.warning("Failed to send $what to uplink #$idx: ${e.message}")
    }
}

private fun sendQuiet(state: SenderState, conn: dev.abdulkadirozyurt.srtla.connection.SrtlaConnection, pkt: ByteArray) {
    try {
        state.connIo[conn.connId]?.socket?.send(pkt)
    } catch (_: IOException) {
    }
}
