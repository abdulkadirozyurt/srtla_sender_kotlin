// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/test_helpers.rs, src/test_helpers.rs
//
// Socket-free connection builders for protocol/selection tests, plus a real
// loopback ConnIo for tests that drive I/O.
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.connection.LinkPhase
import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.net.SourceIpBinder
import dev.abdulkadirozyurt.srtla.net.UplinkSocket
import dev.abdulkadirozyurt.srtla.sender.ClientSink
import dev.abdulkadirozyurt.srtla.sender.ConnIo
import dev.abdulkadirozyurt.srtla.sender.ConnIoMap
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicLong

private val nextTestConnId = AtomicLong(1000L)

/**
 * A connected, Live test connection (Rust `build_connection`): REG3 done,
 * established and grace deadline at [now], lastReceived = [now].
 */
fun buildTestConnection(localIp: InetAddress, label: String, now: Long = nowMs()): SrtlaConnection {
    val c = SrtlaConnection(nextTestConnId.getAndIncrement(), label, localIp, now)
    c.connected = true
    c.lastReceived = now
    c.reconnection.connectionEstablishedMs = now
    c.reconnection.startupGraceDeadlineMs = now
    c.phase = LinkPhase.Live
    return c
}

/** Rust `create_test_connection`: one link on 127.0.0.1. */
fun createTestConnection(now: Long = nowMs()): SrtlaConnection =
    buildTestConnection(InetAddress.getByName("127.0.0.1"), "test-connection", now)

/** Rust `create_test_connections`: links on 192.168.1.10+i labelled test-connection-i. */
fun createTestConnections(count: Int, now: Long = nowMs()): MutableList<SrtlaConnection> =
    MutableList(count) { i ->
        buildTestConnection(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, (10 + i).toByte())), "test-connection-$i", now)
    }

/** A real loopback socket (127.0.0.1:0) aimed at 127.0.0.1:8080. */
fun createTestConnIo(remote: InetSocketAddress = InetSocketAddress("127.0.0.1", 8080)): ConnIo {
    val ch = DatagramChannel.open()
    ch.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    ch.configureBlocking(false)
    return ConnIo(UplinkSocket(ch, remote), SourceIpBinder, remote)
}

/** One loopback ConnIo per connection, keyed by connId. */
fun createTestConnIoMap(connections: List<SrtlaConnection>): ConnIoMap {
    val m = ConnIoMap()
    for (c in connections) m[c.connId] = createTestConnIo()
    return m
}

/** ClientSink that records every packet sent to the local client. */
class RecordingClientSink : ClientSink {
    val sent = ArrayList<Pair<ByteArray, SocketAddress>>()

    override fun sendToClient(data: ByteArray, len: Int, addr: SocketAddress) {
        sent.add(data.copyOf(len) to addr)
    }
}

/** A client address for tests that need one. */
val TEST_CLIENT_ADDR: InetSocketAddress = InetSocketAddress("127.0.0.1", 6000)
