// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/connection/socket.rs, batch_send.rs, batch_recv.rs
//
// JVM DEVIATION NOTE (src/connection/batch_send.rs, batch_recv.rs):
//   Rust uses sendmmsg/recvmmsg Linux syscalls for batching multiple UDP
//   packets in a single kernel call. JVM has no equivalent API (java.nio
//   exposes only send/receive one datagram at a time). We replace the batch
//   operations with a plain send/receive loop per packet. Semantics are
//   identical; only syscall overhead differs — acceptable for JVM targets.
//
// Source: src/connection/socket.rs
//   bind_from_ip → InetSocketAddress(sourceIp, 0) bind via DatagramChannel.
//   UplinkSocketFactory interface allows Android to substitute Network.bindSocket.
//
// Buffer sizes: 100 MB each (matching Rust socket.rs).
package dev.abdulkadirozyurt.srtla.connection

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

// src/connection/socket.rs — buffer sizes
private const val SEND_BUF_SIZE: Int = 100 * 1024 * 1024
private const val RECV_BUF_SIZE: Int = 100 * 1024 * 1024

/**
 * Factory interface for creating and binding UDP sockets.
 * Default implementation binds to (sourceIp, port=0) via DatagramChannel.
 * Android can substitute a Network-aware implementation that calls
 * Network.bindSocket() for multi-path binding.
 *
 * Mirrors the platform-abstraction intent in the plan (PLAN.md §3).
 */
interface UplinkSocketFactory {
    /**
     * Create a DatagramChannel bound to [sourceIp] on ephemeral port 0.
     * The channel must be connected to [remoteAddr] before use.
     */
    fun createAndBind(sourceIp: InetAddress, remoteAddr: InetSocketAddress): DatagramChannel
}

/** Default implementation: plain InetSocketAddress(sourceIp, 0) bind. */
object DefaultUplinkSocketFactory : UplinkSocketFactory {
    override fun createAndBind(sourceIp: InetAddress, remoteAddr: InetSocketAddress): DatagramChannel {
        val ch = DatagramChannel.open()
        ch.configureBlocking(false)
        try { ch.socket().sendBufferSize = SEND_BUF_SIZE } catch (_: SocketException) {}
        try { ch.socket().receiveBufferSize = RECV_BUF_SIZE } catch (_: SocketException) {}
        ch.socket().bind(InetSocketAddress(sourceIp, 0))
        ch.connect(remoteAddr)
        return ch
    }
}

/**
 * Thin wrapper around a connected DatagramChannel.
 * Replaces Rust `BatchUdpSocket` / `BatchSender` / `BatchReceiver`
 * with plain send/receive loops (see JVM DEVIATION NOTE above).
 *
 * Thread-safety: send() and recv() must be called from a single thread
 * each (or externally synchronized). SrtlaConnection.sendPacket() is
 * called from the sender thread; recvPacket() from the per-uplink reader
 * thread.
 */
class UplinkSocket(val channel: DatagramChannel) : AutoCloseable {

    private val sendBuf = ByteBuffer.allocateDirect(1500)
    private val recvBuf = ByteBuffer.allocateDirect(1500)

    /**
     * Send [data] on the channel (non-blocking attempt; drops if would-block).
     * Matches Rust batch_send behaviour: individual send per packet.
     */
    fun send(data: ByteArray) {
        sendBuf.clear()
        val len = minOf(data.size, sendBuf.capacity())
        sendBuf.put(data, 0, len)
        sendBuf.flip()
        try { channel.write(sendBuf) } catch (_: Exception) {}
    }

    /**
     * Blocking receive into a fresh ByteArray. Returns null on error / channel closed.
     * Used by the per-uplink reader thread.
     */
    fun recv(): ByteArray? {
        // Switch to blocking mode for reader threads
        recvBuf.clear()
        return try {
            val blockingCh = channel.configureBlocking(true)
            val n = (blockingCh as DatagramChannel).read(recvBuf)
            if (n <= 0) null
            else ByteArray(n).also { recvBuf.flip(); recvBuf.get(it) }
        } catch (_: Exception) { null }
    }

    /**
     * Non-blocking receive attempt. Returns null if no data is available.
     */
    fun tryRecv(): ByteArray? {
        recvBuf.clear()
        return try {
            channel.configureBlocking(false)
            val n = channel.read(recvBuf)
            if (n <= 0) null
            else ByteArray(n).also { recvBuf.flip(); recvBuf.get(it) }
        } catch (_: Exception) { null }
    }

    override fun close() {
        try { channel.close() } catch (_: Exception) {}
    }
}
