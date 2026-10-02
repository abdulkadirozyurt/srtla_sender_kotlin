// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/uplink_recv.rs
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.connection.SrtlaIncoming
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_ACK
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_TYPE_KEEPALIVE
import dev.abdulkadirozyurt.srtla.protocol.SRT_TYPE_ACK
import dev.abdulkadirozyurt.srtla.protocol.SRT_TYPE_HANDSHAKE
import dev.abdulkadirozyurt.srtla.protocol.SRT_TYPE_NAK
import dev.abdulkadirozyurt.srtla.protocol.getPacketType
import dev.abdulkadirozyurt.srtla.protocol.parseSrtAck
import dev.abdulkadirozyurt.srtla.protocol.parseSrtHandshakeLatency
import dev.abdulkadirozyurt.srtla.protocol.parseSrtNak
import dev.abdulkadirozyurt.srtla.protocol.parseSrtlaAck
import dev.abdulkadirozyurt.srtla.registration.RegistrationEvent
import dev.abdulkadirozyurt.srtla.registration.RegistrationManager
import java.net.SocketAddress
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.uplink")

/**
 * Process one received uplink datagram: update the connection's protocol state
 * and collect the receive-side effects into an [SrtlaIncoming].
 *
 * The one inline I/O kept here is the latency-critical ACK fast path: an SRT
 * ACK goes straight to the client (deduplicated across uplinks) instead of
 * through forwardToClient. ACK/NAK sequence numbers are reported for every copy.
 */
fun processUplinkPacket(
    conn: SrtlaConnection,
    connIdx: Int,
    reg: RegistrationManager,
    clientSink: ClientSink,
    clientAddr: SocketAddress?,
    clientDedup: ClientDedup,
    data: ByteArray,
    len: Int,
    now: Long,
): SrtlaIncoming {
    val incoming = SrtlaIncoming()
    incoming.readAny = true
    val pt = getPacketType(data, len) ?: return incoming

    val event = reg.processRegistrationPacket(connIdx, data, len, now)
    if (event != null) {
        when (event) {
            RegistrationEvent.REG_NGP -> {
                // The immediate REG1 answer is an effect the shell sends.
                incoming.reg1Send = reg.reg1IfNgpImmediate(connIdx, now)
            }
            RegistrationEvent.REG3 -> {
                conn.clearPreRegistrationState(now)
                conn.connected = true
                conn.lastReceived = now
                if (conn.reconnection.connectionEstablishedMs == 0L) {
                    conn.reconnection.connectionEstablishedMs = now
                }
                conn.markReconnectSuccess()
            }
            RegistrationEvent.REG_ERR -> {
                conn.connected = false
                conn.lastReceived = null
            }
            RegistrationEvent.REG2 -> {}
        }
        return incoming
    }

    // Liveness only counts for a registered link, or one never established. A
    // link in recovery must stay timed out so housekeeping rebuilds its socket
    // and re-sends REG2; letting receiver traffic refresh it here parks it in
    // limbo: alive to the timeout check, excluded from scheduling, never
    // re-registered.
    if (conn.connected || conn.reconnection.connectionEstablishedMs == 0L) {
        conn.lastReceived = now
    }

    when (pt) {
        SRT_TYPE_ACK -> {
            parseSrtAck(data, len)?.let { incoming.ackNumbers.add(it) }
            if (clientAddr != null && clientDedup.shouldForwardAck(data, len, now)) {
                clientSink.sendToClient(data, len, clientAddr)
            }
        }
        SRT_TYPE_NAK -> {
            val naks = parseSrtNak(data, len)
            if (naks.isNotEmpty()) {
                log.fine { "NAK received from ${conn.label}: ${naks.size} sequences" }
                for (s in naks) incoming.nakNumbers.add(s)
            }
            if (clientAddr != null && clientDedup.shouldForwardNak(data, len, now)) {
                incoming.forwardToClient.add(data.copyOf(len))
            }
        }
        SRTLA_TYPE_ACK -> {
            val acks = parseSrtlaAck(data, len)
            if (acks.isNotEmpty()) {
                log.finest { "SRTLA ACK received from ${conn.label}: ${acks.size} sequences" }
                for (s in acks) incoming.srtlaAckNumbers.add(s)
            }
        }
        SRT_TYPE_HANDSHAKE -> {
            // The far end declares its TSBPD receive delay in the clear here.
            // Only the response carries negotiated values.
            val hs = parseSrtHandshakeLatency(data, len)
            if (hs != null && hs.isResponse && hs.rcvMs != null) {
                log.fine { "${conn.label}: SRT peer declared a ${hs.rcvMs}ms receive buffer" }
                incoming.negotiatedLatencyMs = hs.rcvMs
            }
            // Sniffing only: the handshake still belongs to the client.
            incoming.forwardToClient.add(data.copyOf(len))
        }
        SRTLA_TYPE_KEEPALIVE -> {
            if (conn.rtt.handleKeepaliveResponse(data, len, conn.label, now) != null) {
                conn.recordRttProbe()
                // Delivery proof: a completed keepalive round trip.
                conn.lastAckOrRttSampleMs = now
            }
        }
        else -> incoming.forwardToClient.add(data.copyOf(len))
    }
    return incoming
}
