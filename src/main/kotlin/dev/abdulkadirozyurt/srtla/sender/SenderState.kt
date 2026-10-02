// Kotlin port of irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/{mod,uplink}.rs (event-loop locals, ConnIo)
//
// Upstream keeps the sender's mutable state as locals of one tokio task. The
// Kotlin event loop owns the same state in a SenderState, so the handlers can
// be driven directly from tests without sockets or a selector.
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.net.UplinkBinder
import dev.abdulkadirozyurt.srtla.net.UplinkSocket
import dev.abdulkadirozyurt.srtla.registration.RegistrationManager
import dev.abdulkadirozyurt.srtla.selection.LinkCcController
import dev.abdulkadirozyurt.srtla.selection.WeakLinkFilter
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * The I/O half of an uplink, kept out of the pure [SrtlaConnection]. Keyed by
 * connId in a [ConnIoMap]. binder/remote are kept for reconnect, which rebuilds
 * the socket in place.
 */
class ConnIo(var socket: UplinkSocket, val binder: UplinkBinder, var remote: InetSocketAddress)

/** Shell-owned connId → ConnIo map. Only the event loop touches it. */
typealias ConnIoMap = HashMap<Long, ConnIo>

/**
 * Receive registration for uplink sockets (upstream: reader tasks). The event
 * loop registers channels with its Selector; tests use [NoopReaderRegistry].
 */
interface ReaderRegistry {
    /** The connection's socket was replaced: stop reading the old one, read the new one. */
    fun restartReaderFor(conn: SrtlaConnection, io: ConnIo)

    /** Ensure every live connection is read and removed ones are not. */
    fun syncReaders(connections: List<SrtlaConnection>, connIo: ConnIoMap)
}

object NoopReaderRegistry : ReaderRegistry {
    override fun restartReaderFor(conn: SrtlaConnection, io: ConnIo) {}
    override fun syncReaders(connections: List<SrtlaConnection>, connIo: ConnIoMap) {}
}

/** Downstream path to the local SRT client (the listener socket). */
fun interface ClientSink {
    fun sendToClient(data: ByteArray, len: Int, addr: SocketAddress)
}

/** Queued SIGHUP reload, applied on the next housekeeping tick. */
class PendingConnectionChanges(
    val newIps: List<java.net.InetAddress>,
    /** Normalised operator weight per entry of newIps. */
    val newWeights: List<Int>,
    val receiverHost: String,
    val receiverPort: Int,
)

/** All mutable sender state owned by the event loop. */
class SenderState(
    val connections: MutableList<SrtlaConnection> = ArrayList(),
    val connIo: ConnIoMap = ConnIoMap(),
    var reg: RegistrationManager = RegistrationManager(),
    val seqTracker: SequenceTracker = SequenceTracker(),
    val clientDedup: ClientDedup = ClientDedup(),
    var rehome: RehomeGate = RehomeGate(true),
) {
    var lastSelectedIdx: Int? = null
    var lastClientAddr: SocketAddress? = null
    var allFailedAt: Long? = null
    var pendingChanges: PendingConnectionChanges? = null
    val weakLinkFilter: WeakLinkFilter = WeakLinkFilter()
    val linkCcController: LinkCcController = LinkCcController()
}
