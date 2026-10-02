// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/connections.rs
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.connection.LINK_WEIGHT_MIN
import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.net.UplinkBinder
import dev.abdulkadirozyurt.srtla.net.UplinkSocket
import dev.abdulkadirozyurt.srtla.net.createUplinkSocket
import dev.abdulkadirozyurt.srtla.net.resolveRemote
import dev.abdulkadirozyurt.srtla.net.resolveRemoteAll
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.connections")
private val idRng = SecureRandom()

fun uplinkLabel(receiverHost: String, receiverPort: Int, ip: InetAddress): String =
    "$receiverHost:$receiverPort via ${ip.hostAddress}"

/**
 * Apply a queued reload: remove links whose IP is gone, re-weight the links that
 * stay, and dial the new ones.
 */
fun applyConnectionChanges(
    state: SenderState,
    newIps: List<InetAddress>,
    newWeights: List<Int>,
    receiverHost: String,
    receiverPort: Int,
    binder: UplinkBinder,
) {
    val connections = state.connections
    val currentLabels = connections.map { it.label }.toHashSet()
    val desiredLabels = newIps.map { uplinkLabel(receiverHost, receiverPort, it) }.toHashSet()

    val oldLen = connections.size
    val removed = connections.filter { it.label !in desiredLabels }.map { it.connId }
    connections.removeAll { it.label !in desiredLabels }
    if (connections.size != oldLen) {
        log.info("removed ${oldLen - connections.size} stale connections")
        state.lastSelectedIdx = null
        for (id in removed) {
            state.seqTracker.removeConnection(id)
            state.connIo.remove(id)?.socket?.close()
        }
    }

    applyLinkWeights(connections, newIps, newWeights)

    val seen = HashSet<InetAddress>()
    val needIps = ArrayList<InetAddress>()
    val needWeights = ArrayList<Int>()
    for (ip in newIps) {
        if (!seen.add(ip)) continue
        if (uplinkLabel(receiverHost, receiverPort, ip) in currentLabels) continue
        needIps.add(ip)
        needWeights.add(weightFor(newIps, newWeights, ip))
    }
    if (needIps.isNotEmpty()) {
        val added = createConnectionsFromIps(needIps, needWeights, receiverHost, receiverPort, binder, state.connIo)
        connections.addAll(added)
        if (added.isNotEmpty()) {
            log.info("added ${added.size} new connections")
        } else {
            log.warning("failed to add any new connections (attempted ${needIps.size})")
        }
    }
}

/** Weight for [ip] from the parallel lists (first match wins); 1 when absent. */
fun weightFor(ips: List<InetAddress>, weights: List<Int>, ip: InetAddress): Int {
    val i = ips.indexOf(ip)
    return if (i >= 0) weights.getOrNull(i) ?: LINK_WEIGHT_MIN else LINK_WEIGHT_MIN
}

/** Set every connection's weight from the lists; an unnamed link goes back to 1. */
fun applyLinkWeights(connections: List<SrtlaConnection>, ips: List<InetAddress>, weights: List<Int>) {
    for (conn in connections) {
        val w = weightFor(ips, weights, conn.localIp)
        if (conn.linkWeight != w) {
            log.info("uplink ${conn.label} weight ${conn.linkWeight} -> $w")
            conn.linkWeight = w
        }
    }
}

fun createConnectionsFromIps(
    ips: List<InetAddress>,
    weights: List<Int>,
    receiverHost: String,
    receiverPort: Int,
    binder: UplinkBinder,
    connIo: ConnIoMap,
): MutableList<SrtlaConnection> {
    val out = ArrayList<SrtlaConnection>()
    for (ip in ips) {
        try {
            val (conn, io) = connectUplink(ip, receiverHost, receiverPort, binder)
            conn.linkWeight = weightFor(ips, weights, ip)
            log.info("added uplink ${conn.label} (weight ${conn.linkWeight})")
            connIo[conn.connId] = io
            out.add(conn)
        } catch (e: Exception) {
            log.warning("failed to add uplink ${ip.hostAddress} -> $receiverHost:$receiverPort: ${e.message}")
        }
    }
    return out
}

/** Non-zero random connection id (0 marks an empty sequence-tracker slot). */
fun newConnId(): Long {
    while (true) {
        val id = idRng.nextLong()
        if (id != 0L) return id
    }
}

/**
 * Open a socket bound to [ip] (steered by [binder]) and pair it with a fresh
 * socket-free connection under one connId.
 */
@Throws(IOException::class)
fun connectUplink(
    ip: InetAddress,
    receiverHost: String,
    receiverPort: Int,
    binder: UplinkBinder,
): Pair<SrtlaConnection, ConnIo> {
    val remote = resolveRemote(receiverHost, receiverPort)
    val ch = createUplinkSocket(ip)
    try {
        binder.bind(ch, ip)
        ch.configureBlocking(false)
    } catch (e: Exception) {
        ch.close()
        throw if (e is IOException) e else IOException(e.message, e)
    }
    val conn = SrtlaConnection(newConnId(), uplinkLabel(receiverHost, receiverPort, ip), ip, nowMs())
    return conn to ConnIo(UplinkSocket(ch, remote), binder, remote)
}

/**
 * Put a link into recovery and drop its sequence ownership. markForRecovery()
 * cannot reach the shell's tracker, which would otherwise keep attributing NAKs
 * to a link that was just wiped. Every recovery site calls this.
 */
fun recoverConnection(conn: SrtlaConnection, seqTracker: SequenceTracker) {
    conn.markForRecovery()
    seqTracker.removeConnection(conn.connId)
}

/**
 * Re-open this uplink's socket in place and reset its protocol state, then run a
 * detect-only DNS drift check. The caller restarts the reader.
 */
@Throws(IOException::class)
fun reconnectUplink(conn: SrtlaConnection, io: ConnIo, receiverHost: String, seqTracker: SequenceTracker, now: Long) {
    rebuildUplinkSocket(conn, io, seqTracker, now)
    spawnReceiverDnsDriftCheck(receiverHost, io.remote, now)
}

/** Rebuild the socket against io.remote (shared with the whole-bond re-home). */
@Throws(IOException::class)
fun rebuildUplinkSocket(conn: SrtlaConnection, io: ConnIo, seqTracker: SequenceTracker, now: Long) {
    val ch = createUplinkSocket(conn.localIp)
    try {
        io.binder.bind(ch, conn.localIp)
        ch.configureBlocking(false)
    } catch (e: Exception) {
        ch.close()
        throw if (e is IOException) e else IOException(e.message, e)
    }
    io.socket.close()
    io.socket = UplinkSocket(ch, io.remote)
    conn.resetForReconnect(now)
    seqTracker.removeConnection(conn.connId)
    conn.reconnection.resetStartupGrace(now)
}

/** At most one DNS-drift warning per minute, process-wide. */
private const val DNS_DRIFT_WARN_INTERVAL_MS: Long = 60_000L
private val lastDnsDriftWarnMs = AtomicLong(0L)

/** Claim the next drift-warning slot (compare-and-set; 0 = never warned). */
internal fun claimDnsDriftWarning(now: Long): Boolean {
    while (true) {
        val last = lastDnsDriftWarnMs.get()
        if (last != 0L && now.satSub(last) < DNS_DRIFT_WARN_INTERVAL_MS) return false
        if (lastDnsDriftWarnMs.compareAndSet(last, maxOf(now, 1L))) return true
    }
}

internal fun lastDnsDriftWarnMsForTest(): Long = lastDnsDriftWarnMs.get()

/** Our pinned address is gone from a non-empty answer. Empty is not drift. */
internal fun dnsDriftDetected(cached: InetSocketAddress, fresh: List<InetSocketAddress>): Boolean =
    fresh.isNotEmpty() && cached !in fresh

/**
 * Bond-wide drift: true only when the hostname answered and none of the
 * addresses the bond is pinned to appear. A reordered multi-A answer that still
 * lists one of ours has not moved the receiver.
 */
internal fun receiverMoved(current: List<InetSocketAddress>, fresh: List<InetSocketAddress>): Boolean =
    current.isNotEmpty() && current.all { dnsDriftDetected(it, fresh) }

/**
 * Detect-only check that the receiver hostname still resolves to the pinned
 * address. It never swaps io.remote: the bond is registered against one receiver
 * instance, and moving a single uplink would split it. Runs on a daemon thread
 * so a slow resolver cannot delay the reconnect.
 */
private fun spawnReceiverDnsDriftCheck(receiverHost: String, cached: InetSocketAddress, now: Long) {
    val t = Thread({
        try {
            val fresh = resolveRemoteAll(receiverHost, cached.port)
            if (dnsDriftDetected(cached, fresh) && claimDnsDriftWarning(now)) {
                log.warning(
                    "receiver DNS drift: $receiverHost no longer resolves to $cached (now ${fresh.joinToString(", ")}); " +
                        "keeping the current address because the bond is registered against this receiver instance",
                )
            }
        } catch (e: Exception) {
            log.fine { "could not re-resolve $receiverHost on reconnect (${e.message}); keeping $cached" }
        }
    }, "srtla-dns-drift")
    t.isDaemon = true
    t.start()
}
