// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/packet_handler.rs
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.connection.SrtlaIncoming
import dev.abdulkadirozyurt.srtla.core.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import dev.abdulkadirozyurt.srtla.core.selectBestQualityIdx
import dev.abdulkadirozyurt.srtla.net.sendAllDatagrams
import dev.abdulkadirozyurt.srtla.protocol.getSrtSequenceNumber
import dev.abdulkadirozyurt.srtla.protocol.isSrtDataRetransmit
import dev.abdulkadirozyurt.srtla.protocol.setSrtDataRetransmit
import dev.abdulkadirozyurt.srtla.selection.selectConnectionIdx
import java.io.IOException
import java.net.SocketAddress
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.packet")

/**
 * Attribute a NAK to the uplink that sent the lost packet and shrink its window.
 * Prefers the tracker's record; once that link is found the scan never falls
 * through, so a duplicate NAK cannot be re-counted against another link. Returns
 * the index of the link that counted it.
 */
fun attributeNak(
    connections: List<SrtlaConnection>,
    seqTracker: SequenceTracker,
    nak: Int,
    currentTimeMs: Long,
): Int? {
    val owner = seqTracker.get(nak, currentTimeMs)
    if (owner != null) {
        val pos = connections.indexOfFirst { it.connId == owner }
        if (pos >= 0) return if (connections[pos].handleNak(nak, currentTimeMs)) pos else null
    }
    for ((i, c) in connections.withIndex()) {
        if (c.handleNak(nak, currentTimeMs)) return i
    }
    return null
}

/** Apply one datagram's ACK/NAK/SRTLA-ACK effects and relay to the client. */
fun processConnectionEvents(
    idx: Int,
    connections: List<SrtlaConnection>,
    lastClientAddr: SocketAddress?,
    clientSink: ClientSink,
    seqTracker: SequenceTracker,
    classic: Boolean,
    incoming: SrtlaIncoming,
    currentTimeMs: Long,
) {
    if (idx >= connections.size) return
    if (!incoming.readAny && incoming.ackNumbers.isEmpty() && incoming.nakNumbers.isEmpty() &&
        incoming.srtlaAckNumbers.isEmpty() && incoming.forwardToClient.isEmpty()
    ) {
        return
    }

    for (ack in incoming.ackNumbers) {
        // Every link prunes on a cumulative ACK; only the link that carried the
        // unique copy samples RTT from it. Probes are never in the tracker.
        val owner = seqTracker.get(ack, currentTimeMs)
        for (c in connections) c.handleSrtAck(ack, currentTimeMs, owner == c.connId)
    }

    for (srtlaAck in incoming.srtlaAckNumbers) {
        // Match the arrival link first: with duplicate probing a sequence can sit
        // in two links' logs, and a first-log-wins scan would let the healthy
        // link's ACK stamp delivery proof on the gated one.
        val foundOnArrival = connections[idx].handleSrtlaAckSpecific(srtlaAck, classic, currentTimeMs)
        if (!foundOnArrival) {
            for ((i, c) in connections.withIndex()) {
                if (i == idx) continue
                if (c.handleSrtlaAckSpecific(srtlaAck, classic, currentTimeMs)) break
            }
        }
        for (c in connections) c.handleSrtlaAckGlobal()
    }

    for (nak in incoming.nakNumbers) attributeNak(connections, seqTracker, nak, currentTimeMs)

    if (lastClientAddr != null) {
        for (pkt in incoming.forwardToClient) clientSink.sendToClient(pkt, pkt.size, lastClientAddr)
    }
}

/** Handle one datagram received on uplink [connId]. */
fun handleUplinkPacket(
    state: SenderState,
    connId: Long,
    data: ByteArray,
    len: Int,
    clientSink: ClientSink,
    configSnap: ConfigSnapshot,
    config: DynamicConfig?,
    now: Long,
) {
    if (len <= 0) return
    val connections = state.connections
    val idx = connections.indexOfFirst { it.connId == connId }
    if (idx < 0) return
    val conn = connections[idx]
    val incoming = processUplinkPacket(conn, idx, state.reg, clientSink, state.lastClientAddr, state.clientDedup, data, len, now)

    // Flush the deferred immediate-REG1 effect on this link's socket.
    val reg1 = incoming.reg1Send
    if (reg1 != null) {
        incoming.reg1Send = null
        val io = state.connIo[conn.connId]
        if (io != null) {
            try {
                io.socket.send(reg1)
                conn.noteSent(now)
            } catch (e: IOException) {
                log.warning("${conn.label}: failed to send immediate REG1: ${e.message}")
            }
        }
    }
    // The peer's receive buffer is the deadline every link is judged against,
    // so it belongs on the shared config, not on the link that carried it.
    val latency = incoming.negotiatedLatencyMs
    if (latency != null && config != null && config.setNegotiatedLatencyMs(latency)) {
        log.info("SRT delivery budget is now ${latency}ms (from the peer's handshake)")
    }
    processConnectionEvents(
        idx, connections, state.lastClientAddr, clientSink, state.seqTracker,
        configSnap.mode.isClassic(), incoming, now,
    )
}

/**
 * Pre-registration choice: the last selected link while still connected and
 * live, else any non-timed-out link.
 */
fun selectPreRegistrationConnection(connections: List<SrtlaConnection>, lastSelectedIdx: Int?, nowMs: Long): Int? {
    if (lastSelectedIdx != null) {
        val c = connections.getOrNull(lastSelectedIdx)
        if (c != null && c.connected && !c.isTimedOut(nowMs)) return lastSelectedIdx
    }
    val i = connections.indexOfFirst { !it.isTimedOut(nowMs) }
    return if (i >= 0) i else null
}

/**
 * Route one SRT packet from the local client.
 *
 * Must-land traffic overrides normal selection and goes to the best-quality
 * link: packets inside an encoder-opened critical window (keyframes), and SRT
 * retransmits, which fill a hole the receiver is already waiting on.
 */
fun handleSrtPacket(
    state: SenderState,
    data: ByteArray,
    len: Int,
    src: SocketAddress,
    configSnap: ConfigSnapshot,
    criticalWindow: CriticalWindow,
    packetTimeMs: Long,
) {
    if (len <= 0) return
    val connections = state.connections
    val seq = getSrtSequenceNumber(data, len)
    if (!state.reg.hasConnected) {
        val selIdx = selectPreRegistrationConnection(connections, state.lastSelectedIdx, packetTimeMs)
        if (selIdx != null) forwardViaConnection(state, selIdx, data, len, seq, packetTimeMs)
        state.lastClientAddr = src
        return
    }

    var selIdx = selectConnectionIdx(connections, state.lastSelectedIdx, packetTimeMs, configSnap)
    if (seq != null && (criticalWindow.isCriticalNow(packetTimeMs) || isSrtDataRetransmit(data, len))) {
        val best = selectBestQualityIdx(connections, packetTimeMs)
        if (best != null && selIdx != best) {
            log.finest { "critical override (window/retransmit): link ${selIdx ?: -1} -> $best" }
            selIdx = best
        }
    }

    if (selIdx != null) {
        forwardViaConnection(state, selIdx, data, len, seq, packetTimeMs)
        if (seq != null) sendStallProbes(state, selIdx, data, len, seq, packetTimeMs)
    } else {
        log.warning("no available connection to forward packet from $src")
    }
    state.lastClientAddr = src
}

/**
 * Queue a packet on [selIdx] and track its sequence. A switch deliberately does
 * not flush the previous link's batch: each link flushes on its own threshold or
 * timer, so interleaved routing just fills several batches concurrently.
 */
fun forwardViaConnection(
    state: SenderState,
    selIdx: Int,
    data: ByteArray,
    len: Int,
    seq: Int?,
    packetTimeMs: Long,
) {
    val connections = state.connections
    if (selIdx >= connections.size) return
    val last = state.lastSelectedIdx
    if (last != selIdx) {
        if (last != null) {
            if (last < connections.size) {
                log.finest { "Connection switch: ${connections[last].label} → ${connections[selIdx].label} (seq: $seq)" }
            }
        } else {
            log.fine { "Initial connection selected: ${connections[selIdx].label} (seq: $seq)" }
        }
        state.lastSelectedIdx = selIdx
    }
    val conn = connections[selIdx]
    val needsFlush = conn.queueDataPacket(data, len, seq, packetTimeMs)
    // Tracked when queued (not when flushed) for accurate NAK attribution.
    if (seq != null) state.seqTracker.insert(seq, conn.connId, packetTimeMs)
    if (needsFlush) {
        val io = state.connIo[conn.connId]
        if (io != null) flushConnection(conn, io, state.seqTracker, "batch flush")
    }
}

/**
 * Duplicate-packet probing on links held out of the rotation (stall-gated, or
 * quality-excluded for being late). One in STALL_PROBE_ONE_IN_N routed packets
 * is also queued on each held-out link with the same sequence and the R bit set.
 * The receiver dedups it, so a late probe never stalls the buffer, while a
 * delivered one earns the link an SRTLA ACK: delivery proof plus an RTT sample.
 * Never inserted into the sequence tracker, so NAKs stay with the real carrier.
 */
fun sendStallProbes(
    state: SenderState,
    selIdx: Int,
    data: ByteArray,
    len: Int,
    probeSeq: Int,
    packetTimeMs: Long,
) {
    var probe: ByteArray? = null
    for ((i, conn) in state.connections.withIndex()) {
        if (i == selIdx) continue
        if (!(conn.isStallGated() || conn.isQualityExcluded()) || !conn.connected) continue
        if (!conn.stallProbeDue()) continue
        log.finest { "${conn.label}: sending duplicate probe (seq $probeSeq)" }
        val p = probe ?: data.copyOf(len).also {
            setSrtDataRetransmit(it)
            probe = it
        }
        val needsFlush = conn.queueProbePacket(p, p.size, probeSeq, packetTimeMs)
        if (needsFlush) {
            val io = state.connIo[conn.connId]
            if (io != null) flushConnection(conn, io, state.seqTracker, "probe batch flush")
        }
    }
}

/**
 * Drain one link's batch and send it; if the socket refuses it, put the link
 * into recovery. takeBatch registers the whole batch as in-flight before the
 * I/O, and only recovery clears those registrations, so every flush path funnels
 * through here. The unconfirmed remainder is dropped, not re-queued: SRT's own
 * NAK/retransmit refills the hole over the healthy links.
 */
fun flushConnection(conn: SrtlaConnection, io: ConnIo, seqTracker: SequenceTracker, what: String) {
    val now = dev.abdulkadirozyurt.srtla.core.nowMs()
    val batch = conn.takeBatch(now)
    if (batch.isEmpty()) return
    val err = sendAllDatagrams(io.socket, batch.map { it.data })
    if (err != null) {
        log.warning("${conn.label}: $what failed, marking for recovery: ${err.message}")
        recoverConnection(conn, seqTracker)
        return
    }
    conn.noteSent(now)
}

/** Flush every link with queued packets (15 ms timer). */
fun flushAllBatches(state: SenderState, now: Long) {
    val connections = state.connections
    if (connections.none { it.hasQueuedPackets() || it.needsBatchFlush(now) }) return
    for (conn in connections) {
        if (conn.needsBatchFlush(now) || conn.hasQueuedPackets()) {
            val io = state.connIo[conn.connId] ?: continue
            flushConnection(conn, io, state.seqTracker, "periodic batch flush")
        }
    }
}
