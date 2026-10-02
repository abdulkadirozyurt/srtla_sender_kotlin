// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/mod.rs (run_sender_with_config)
//
// THREAD MODEL: upstream runs the whole sender as one tokio task that selects
// over the local SRT listener, the uplink packet channel, a 1 s housekeeping
// timer and a 15 ms batch-flush timer. The Kotlin port keeps that shape: one
// event-loop thread multiplexes the listener and every uplink channel with a
// java.nio Selector and fires both timers itself. All connection state is owned
// by that thread, so no locks guard it. Other threads talk to the loop only
// through atomics (stop, reload request, DynamicConfig) and thread-safe shared
// objects (SharedStats, CriticalWindow, SubscriptionHub).
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.net.SourceIpBinder
import dev.abdulkadirozyurt.srtla.net.UplinkBinder
import dev.abdulkadirozyurt.srtla.net.UplinkSocket
import dev.abdulkadirozyurt.srtla.selection.WeakReason
import dev.abdulkadirozyurt.srtla.selection.CcState
import dev.abdulkadirozyurt.srtla.telemetry.SharedStats
import dev.abdulkadirozyurt.srtla.telemetry.SubscriptionHub
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.sender")

const val HOUSEKEEPING_INTERVAL_MS: Long = 1000L
private const val STATUS_LOG_INTERVAL_MS: Long = 30_000L
private const val BATCH_FLUSH_INTERVAL_MS: Long = 15L

/** Datagrams read from one ready socket per wake-up, so no socket starves the rest. */
private const val MAX_READS_PER_WAKE: Int = 64

/** Client sends that hit a full socket buffer and wait for the next loop pass. */
private const val MAX_PENDING_CLIENT_SENDS: Int = 1024

private object ListenerTag

/**
 * The bonding sender. [run] blocks the calling thread until [stop] is called or
 * startup fails.
 */
class SrtlaSender(
    private val localSrtPort: Int,
    private val receiverHost: String,
    private val receiverPort: Int,
    private val ipsFile: String,
    private val config: DynamicConfig,
    private val stats: SharedStats = SharedStats(),
    private val criticalWindow: CriticalWindow = CriticalWindow(),
    private val hub: SubscriptionHub = SubscriptionHub(),
    private val binder: UplinkBinder = SourceIpBinder,
) {
    private val running = AtomicBoolean(false)
    private val reloadRequested = AtomicBoolean(false)
    @Volatile private var selector: Selector? = null

    /** Local SRT port actually bound (useful when [localSrtPort] is 0). */
    @Volatile var boundLocalPort: Int = -1
        private set

    /** Set once the loop has bound the listener and dialed the uplinks. */
    @Volatile var started: Boolean = false
        private set

    /** Owned by the event-loop thread; never touch it from another thread while running. */
    val state: SenderState = SenderState(rehome = RehomeGate(config.rehomeOnFailure()))

    private val keys = HashMap<Long, SelectionKey>()
    private val recvArr = ByteArray(UplinkSocket.MAX_DATAGRAM)
    private val recvBuf: ByteBuffer = ByteBuffer.wrap(recvArr)
    private val pendingClient = ArrayDeque<Pair<ByteArray, SocketAddress>>()
    private var listener: DatagramChannel? = null

    /** Ask the loop to stop; safe from any thread. */
    fun stop() {
        running.set(false)
        selector?.wakeup()
    }

    /** Queue an IP-list reload (SIGHUP equivalent); safe from any thread. */
    fun requestIpReload() {
        reloadRequested.set(true)
        selector?.wakeup()
    }

    fun isRunning(): Boolean = running.get()

    private val clientSink = ClientSink { data, len, addr -> sendToClient(data, len, addr) }

    private val readers = object : ReaderRegistry {
        override fun restartReaderFor(conn: SrtlaConnection, io: ConnIo) {
            keys.remove(conn.connId)?.cancel()
            register(conn.connId, io)
        }

        override fun syncReaders(connections: List<SrtlaConnection>, connIo: ConnIoMap) {
            val active = HashSet<Long>()
            for (c in connections) {
                active.add(c.connId)
                val io = connIo[c.connId] ?: continue
                val key = keys[c.connId]
                if (key == null || !key.isValid || key.channel() !== io.socket.channel) {
                    key?.cancel()
                    register(c.connId, io)
                }
            }
            val it = keys.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key !in active) {
                    e.value.cancel()
                    it.remove()
                }
            }
        }
    }

    private fun register(connId: Long, io: ConnIo) {
        val sel = selector ?: return
        try {
            keys[connId] = io.socket.channel.register(sel, SelectionKey.OP_READ, connId)
        } catch (e: IOException) {
            log.warning("failed to watch uplink socket: ${e.message}")
        }
    }

    /**
     * Run the sender until [stop]. Throws when the listener cannot bind or no
     * uplink can be created.
     */
    @Throws(IOException::class)
    fun run() {
        if (!running.compareAndSet(false, true)) throw IllegalStateException("sender already running")
        log.info("starting srtla_send: local_srt_port=$localSrtPort, receiver=$receiverHost:$receiverPort, ips_file=$ipsFile, mode=${config.mode()}")
        try {
            runInner()
        } finally {
            running.set(false)
            shutdown()
        }
    }

    private fun runInner() {
        // Bind the local SRT listener FIRST: an encoder is usually pointed at
        // this port the moment the process starts, with no readiness handshake,
        // and uplink setup (DNS per modem) would otherwise leave it closed.
        val l = bindListener(localSrtPort)
        listener = l
        boundLocalPort = (l.localAddress as InetSocketAddress).port
        log.info("listening for SRT on [::]:$boundLocalPort")

        val (ips, weights) = readWeightedIpList(ipsFile)
        log.fine { "uplink IPs loaded: ${describeWeights(ips, weights)}" }
        if (ips.isEmpty()) throw IOException("no IPs in list: $ipsFile")

        state.connections.addAll(
            createConnectionsFromIps(ips, weights, receiverHost, receiverPort, binder, state.connIo),
        )
        if (state.connections.isEmpty()) throw IOException("no uplinks available")

        val sel = Selector.open()
        selector = sel
        l.register(sel, SelectionKey.OP_READ, ListenerTag)

        for ((idx, pkt) in state.reg.startProbing(state.connections, nowMs())) {
            val c = state.connections.getOrNull(idx) ?: continue
            try {
                state.connIo[c.connId]?.socket?.send(pkt)
            } catch (_: IOException) {
            }
        }
        readers.syncReaders(state.connections, state.connIo)

        // One housekeeping pass before the loop, so it starts clean.
        try {
            handleHousekeeping(state, receiverHost, config.mode().isClassic(), nowMs(), readers)
        } catch (e: AllLinksFailedException) {
            log.warning("initial housekeeping failed: ${e.message}")
        }
        started = true

        var nextHousekeeping = nowMs() + HOUSEKEEPING_INTERVAL_MS
        var nextFlush = nowMs() + BATCH_FLUSH_INTERVAL_MS
        var statusElapsedMs = 0L

        while (running.get()) {
            val wait = maxOf(minOf(nextHousekeeping, nextFlush) - nowMs(), 1L)
            sel.select(wait)
            val it = sel.selectedKeys().iterator()
            while (it.hasNext()) {
                val key = it.next()
                it.remove()
                if (!key.isValid) continue
                when (val tag = key.attachment()) {
                    ListenerTag -> readListener(l)
                    is Long -> readUplink(tag)
                }
            }
            drainPendingClientSends()

            if (reloadRequested.getAndSet(false)) queueIpReload()

            val now = nowMs()
            if (now >= nextFlush) {
                flushAllBatches(state, now)
                nextFlush = now + BATCH_FLUSH_INTERVAL_MS
            }
            if (now >= nextHousekeeping) {
                housekeepingTick(now)
                statusElapsedMs += HOUSEKEEPING_INTERVAL_MS
                if (statusElapsedMs >= STATUS_LOG_INTERVAL_MS) {
                    logConnectionStatus(state.connections, state.lastSelectedIdx, config.snapshot(), nowMs())
                    statusElapsedMs -= STATUS_LOG_INTERVAL_MS
                }
                // Missed ticks are delayed, not burst (tokio MissedTickBehavior::Delay).
                nextHousekeeping = nowMs() + HOUSEKEEPING_INTERVAL_MS
            }
        }
        log.info("srtla_send event loop stopped")
    }

    private fun bindListener(port: Int): DatagramChannel {
        try {
            val ch = DatagramChannel.open(StandardProtocolFamily.INET6)
            try {
                ch.bind(InetSocketAddress(java.net.Inet6Address.getByName("::"), port))
                ch.configureBlocking(false)
                return ch
            } catch (e: IOException) {
                ch.close()
                if (e is java.net.BindException) throw IOException("bind local SRT UDP listener: ${e.message}", e)
            }
        } catch (_: UnsupportedOperationException) {
        } catch (e: IOException) {
            if (e.cause is java.net.BindException) throw e
        }
        // No IPv6 stack: fall back to the IPv4 wildcard.
        val ch = DatagramChannel.open(StandardProtocolFamily.INET)
        try {
            ch.bind(InetSocketAddress(port))
            ch.configureBlocking(false)
        } catch (e: IOException) {
            ch.close()
            throw IOException("bind local SRT UDP listener: ${e.message}", e)
        }
        return ch
    }

    private fun readListener(l: DatagramChannel) {
        repeat(MAX_READS_PER_WAKE) {
            recvBuf.clear()
            val src = try {
                l.receive(recvBuf)
            } catch (e: IOException) {
                log.warning("error reading local SRT: ${e.message}")
                return
            } ?: return
            handleSrtPacket(state, recvArr, recvBuf.position(), src, config.snapshot(), criticalWindow, nowMs())
        }
    }

    private fun readUplink(connId: Long) {
        repeat(MAX_READS_PER_WAKE) {
            val io = state.connIo[connId] ?: return
            recvBuf.clear()
            try {
                io.socket.receive(recvBuf) ?: return
            } catch (e: IOException) {
                log.fine { "uplink recv error: ${e.message}" }
                return
            }
            handleUplinkPacket(state, connId, recvArr, recvBuf.position(), clientSink, config.snapshot(), config, nowMs())
        }
    }

    private fun sendToClient(data: ByteArray, len: Int, addr: SocketAddress) {
        val l = listener ?: return
        if (pendingClient.isEmpty()) {
            try {
                if (l.send(ByteBuffer.wrap(data, 0, len), addr) > 0) return
            } catch (_: IOException) {
                return
            }
        }
        // Would block: keep order behind earlier queued sends.
        if (pendingClient.size < MAX_PENDING_CLIENT_SENDS) pendingClient.addLast(data.copyOf(len) to addr)
    }

    private fun drainPendingClientSends() {
        val l = listener ?: return
        while (pendingClient.isNotEmpty()) {
            val (data, addr) = pendingClient.first()
            val sent = try {
                l.send(ByteBuffer.wrap(data), addr)
            } catch (_: IOException) {
                pendingClient.removeFirst()
                continue
            }
            if (sent == 0) return
            pendingClient.removeFirst()
        }
    }

    private fun queueIpReload() {
        log.info("reload requested - evaluating uplink IP reload from $ipsFile")
        when (val r = analyzeIpReload(ipsFile)) {
            is IpReload.Apply -> {
                r.firstInvalidLine?.let { log.warning("ips file has an invalid entry starting at line $it; applying valid IPs only") }
                log.info("uplink IP changes queued for next processing cycle: ${describeWeights(r.ips, r.weights)}")
                state.pendingChanges = PendingConnectionChanges(r.ips, r.weights, receiverHost, receiverPort)
            }
            is IpReload.Refuse -> log.warning("refusing reload (${r.reason}); keeping current connections")
        }
    }

    private fun housekeepingTick(now: Long) {
        try {
            handleHousekeeping(state, receiverHost, config.mode().isClassic(), now, readers)
        } catch (e: AllLinksFailedException) {
            // Upstream logs and keeps running; the stream may still come back.
            log.warning("housekeeping failed: ${e.message}")
        }

        // Weak-link classifier and per-link CC; stamp results for selection.
        val snap = config.snapshot()
        val connections = state.connections
        val classification = state.weakLinkFilter.classify(connections, snap.negotiatedLatencyMs)
        val ccSnaps = state.linkCcController.tickAll(connections, nowMs())
        for (conn in connections) {
            val entry = classification.perLink.firstOrNull { it.connId == conn.connId }
            conn.weak = entry?.weak ?: false
            conn.weakReason = entry?.reason ?: WeakReason.HEALTHY
            val cc = ccSnaps[conn.connId]
            conn.ccBackingOff = cc?.state == CcState.BACKING_OFF
            conn.ccTargetBps = cc?.targetBps ?: 0L
            conn.lossDegraded = cc?.lossDegraded ?: false
        }
        stats.update(connections, snap, classification, ccSnaps)
        if (!hub.isEmpty()) hub.publish("stats", stats.get().toJsonValue())

        val changes = state.pendingChanges
        if (changes != null) {
            state.pendingChanges = null
            log.info("applying queued connection changes: ${changes.newIps.size} IPs")
            applyConnectionChanges(state, changes.newIps, changes.newWeights, changes.receiverHost, changes.receiverPort, binder)
            log.info("connection changes applied successfully")
        }
        readers.syncReaders(connections, state.connIo)
    }

    private fun shutdown() {
        try {
            selector?.close()
        } catch (_: IOException) {
        }
        selector = null
        for (io in state.connIo.values) io.socket.close()
        try {
            listener?.close()
        } catch (_: IOException) {
        }
        listener = null
    }
}
