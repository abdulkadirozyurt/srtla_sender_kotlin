// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/net/{mod,socket,batch_recv}.rs
//
// Uplink socket I/O (shell). The pure connection/scheduler core sits on top of
// this layer and never touches a socket.
//
// The socket is deliberately NOT connected: every send names the receiver
// explicitly and the receive path accepts datagrams from any source. A
// connected UDP socket makes the kernel drop replies from any other address,
// which broke receivers behind NAT and the C reference receiver, whose replies
// can come from a different source than the one dialed.
//
// JVM DEVIATION: no sendmmsg/recvmmsg. A drained batch goes out one datagram
// at a time; reception is driven by the sender's NIO Selector.
package dev.abdulkadirozyurt.srtla.net

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.UnsupportedAddressTypeException
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.net")

private const val SEND_BUF_SIZE: Int = 100 * 1024 * 1024
private const val RECV_BUF_SIZE: Int = 100 * 1024 * 1024

/**
 * Strategy for steering a fresh uplink socket onto a specific egress path.
 *
 * On a multi-homed Linux host binding the source IP is enough (source-based
 * routing): [SourceIpBinder]. On Android the app must call
 * `Network.bindSocket` on the wifi or cellular Network instead; supply a binder
 * that does that. The uplink identity stays keyed on the IP either way.
 */
fun interface UplinkBinder {
    /** Steer [channel] (created, buffers set, no traffic yet) onto the egress for [ip]. */
    @Throws(IOException::class)
    fun bind(channel: DatagramChannel, ip: InetAddress)
}

/** Default binder: bind the uplink source IP on an ephemeral port. */
object SourceIpBinder : UplinkBinder {
    override fun bind(channel: DatagramChannel, ip: InetAddress) {
        channel.bind(InetSocketAddress(ip, 0))
    }
}

/** Create a non-blocking UDP channel with the standard 100 MB buffers. */
@Throws(IOException::class)
fun createUplinkSocket(domainFor: InetAddress): DatagramChannel {
    val family = if (domainFor is Inet6Address) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET
    val ch = DatagramChannel.open(family)
    try {
        ch.configureBlocking(false)
        try {
            ch.setOption(StandardSocketOptions.SO_SNDBUF, SEND_BUF_SIZE)
        } catch (e: IOException) {
            log.warning("Failed to set send buffer size to $SEND_BUF_SIZE: ${e.message}")
        }
        try {
            ch.setOption(StandardSocketOptions.SO_RCVBUF, RECV_BUF_SIZE)
        } catch (e: IOException) {
            log.warning("Failed to set receive buffer size to $RECV_BUF_SIZE: ${e.message}")
        }
    } catch (e: IOException) {
        ch.close()
        throw e
    }
    return ch
}

/** Every address the receiver's hostname currently answers with. */
@Throws(IOException::class)
fun resolveRemoteAll(host: String, port: Int): List<InetSocketAddress> =
    InetAddress.getAllByName(host).map { InetSocketAddress(it, port) }

/** First DNS answer: the deterministic pick every uplink dials. */
@Throws(IOException::class)
fun resolveRemote(host: String, port: Int): InetSocketAddress =
    resolveRemoteAll(host, port).firstOrNull() ?: throw IOException("no DNS result for $host")

/**
 * An unconnected uplink channel paired with the receiver address it sends to.
 * Not thread-safe: owned by the sender's event loop.
 */
class UplinkSocket(val channel: DatagramChannel, val peer: InetSocketAddress) : AutoCloseable {
    private val sendBuf: ByteBuffer = ByteBuffer.allocateDirect(MAX_DATAGRAM)

    /**
     * Send one datagram to [peer]. Returns the bytes accepted; 0 means the socket
     * buffer is full (non-blocking would-block).
     */
    @Throws(IOException::class)
    fun send(data: ByteArray, len: Int = data.size): Int {
        sendBuf.clear()
        sendBuf.put(data, 0, minOf(len, sendBuf.capacity()))
        sendBuf.flip()
        return try {
            channel.send(sendBuf, peer)
        } catch (e: UnsupportedAddressTypeException) {
            // Unchecked on the JVM (e.g. an IPv4-only socket aimed at an IPv6
            // receiver). Upstream treats every send error alike, so surface it as
            // an IOException and let the caller recover the link.
            throw IOException("cannot send to $peer: address family not supported by this uplink", e)
        }
    }

    /** Receive one datagram into [buf]; returns the source or null when none is ready. */
    @Throws(IOException::class)
    fun receive(buf: ByteBuffer): SocketAddress? = channel.receive(buf)

    override fun close() {
        try {
            channel.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Large enough for any SRT/SRTLA datagram (MTU 1500) with margin. */
        const val MAX_DATAGRAM: Int = 2048
    }
}

/**
 * How far a batch flush got before the socket refused it: [sent] of [total]
 * datagrams were accepted, the rest never left the host.
 */
class BatchSendError(val sent: Int, val total: Int, override val cause: IOException) :
    IOException("$sent of $total datagrams sent before the socket failed: ${cause.message}", cause)

/**
 * Send every datagram of a batch. A zero-byte send (buffer full) counts as an
 * error so the link is recovered rather than livelocked.
 */
fun sendAllDatagrams(socket: UplinkSocket, bufs: List<ByteArray>): BatchSendError? {
    var sent = 0
    for (b in bufs) {
        val n = try {
            socket.send(b)
        } catch (e: IOException) {
            return BatchSendError(sent, bufs.size, e)
        }
        if (n == 0 && b.isNotEmpty()) {
            return BatchSendError(sent, bufs.size, IOException("socket accepted no datagram (would block)"))
        }
        sent++
    }
    return null
}
