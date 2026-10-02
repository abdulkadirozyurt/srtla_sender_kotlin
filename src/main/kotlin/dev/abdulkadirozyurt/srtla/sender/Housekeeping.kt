// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/housekeeping.rs
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.connection.STARTUP_GRACE_MS
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
    reg.clearPendingIfTimedOut(nowMs)

    if (reg.isProbing()) {
        reg.checkProbingComplete(nowMs)
        if (!reg.isProbing()) {
            val idx = reg.getSelectedConnectionIdx()
            val conn = idx?.let { connections.getOrNull(it) }
            if (conn != null) {
                conn.reconnection.startupGraceDeadlineMs = nowMs + STARTUP_GRACE_MS
                log.fine { "${conn.label}: Reset grace period after being selected for initial registration" }
            }
        }
    }

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

    val sends = reg.regDriverPendingSends(connections.size, nowMs)
    sends.reg1?.let { (idx, pkt) ->
        val conn = connections.getOrNull(idx)
        if (conn != null) sendOn(state, conn, pkt, nowMs, "REG1", idx)
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
