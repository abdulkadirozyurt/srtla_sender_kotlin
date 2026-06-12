// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/mod.rs, connections.rs, packet_handler.rs, uplink.rs
//
// SrtlaSender — core bonding sender.
//
// THREAD MODEL (mirrors Rust tokio task structure):
//   1. SRT listener thread   — reads from local SRT UDP socket, forwards to selected uplink.
//   2. Per-uplink recv thread — one thread per SrtlaConnection; delivers packets via a queue.
//   3. Housekeeping thread   — sends keepalives, drives registration, triggers window recovery.
//
// LOCKING STRATEGY (documented centrally here per spec):
//   - A single ReentrantLock (`stateLock`) protects:
//       • connections list
//       • registrationManager
//       • lastSelectedIdx / lastSwitchMs
//       • seqTracker
//   - Per-uplink recv threads enqueue raw bytes into `inboundQueue` (LinkedBlockingQueue,
//     lock-free producer). The SRT listener thread and housekeeping thread drain the queue
//     under `stateLock` before processing.
//   - AtomicBoolean `running` controls lifecycle without needing the state lock.
//
// JVM DEVIATION:
//   Rust uses tokio select! macro for async I/O multiplexing; JVM uses blocking threads.
//   batch_send / batch_recv → plain send/receive loops per JVM DEVIATION NOTE in UplinkSocket.
//
// Source: src/sender/housekeeping.rs :: GLOBAL_TIMEOUT_MS
// Source: src/sender/mod.rs :: HOUSEKEEPING_INTERVAL_MS
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection
import dev.abdulkadirozyurt.srtla.connection.UplinkSocketFactory
import dev.abdulkadirozyurt.srtla.connection.DefaultUplinkSocketFactory
import dev.abdulkadirozyurt.srtla.protocol.*
import dev.abdulkadirozyurt.srtla.registration.RegistrationManager
import dev.abdulkadirozyurt.srtla.sender.selection.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.sender.selection.SchedulingMode
import dev.abdulkadirozyurt.srtla.sender.selection.SelectionOrchestrator
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// src/sender/housekeeping.rs :: GLOBAL_TIMEOUT_MS
const val GLOBAL_TIMEOUT_MS: Long = 10_000L
// src/sender/mod.rs :: HOUSEKEEPING_INTERVAL_MS
const val HOUSEKEEPING_INTERVAL_MS: Long = 1_000L

/**
 * Inbound packet from one uplink, tagged with connection ID.
 * Mirrors Rust `UplinkPacket` in src/sender/uplink.rs.
 */
data class UplinkPacket(val connId: Long, val bytes: ByteArray)

/**
 * SrtlaSender — main SRTLA bonding sender.
 *
 * Usage:
 *   val sender = SrtlaSender(localSrtPort, receiverHost, receiverPort, sourceIps)
 *   sender.start()
 *   // ...
 *   sender.stop()
 */
class SrtlaSender(
    private val localSrtPort: Int,
    private val receiverHost: String,
    private val receiverPort: Int,
    private val sourceIps: List<InetAddress>,
    private val config: ConfigSnapshot = ConfigSnapshot(),
    private val socketFactory: UplinkSocketFactory = DefaultUplinkSocketFactory,
) {
    // ── Shared state (protected by stateLock) ────────────────────────────────
    private val stateLock = ReentrantLock()
    private val connections: MutableList<SrtlaConnection> = mutableListOf()
    private val reg = RegistrationManager()
    private var lastSelectedIdx: Int? = null
    private var lastSwitchMs: Long = 0L
    private val seqTracker = SequenceTracker()
    private val selectionOrchestrator = SelectionOrchestrator()

    // ── Lifecycle ────────────────────────────────────────────────────────────
    private val running = AtomicBoolean(false)
    private val threads = mutableListOf<Thread>()

    // ── Inbound queue (lock-free produce, drained under stateLock) ──────────
    private val inboundQueue: LinkedBlockingQueue<UplinkPacket> = LinkedBlockingQueue(4096)

    // ── SRT listener socket (set on start) ──────────────────────────────────
    private var srtSocket: DatagramSocket? = null
    private var lastClientAddr: java.net.SocketAddress? = null

    // ── All-failed tracking (housekeeping.rs) ────────────────────────────────
    private var allFailedAtMs: Long = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** Start all threads. Mirrors Rust `run_sender_with_config`. */
    fun start() {
        if (!running.compareAndSet(false, true)) return

        // Build connections from source IPs
        stateLock.withLock {
            for (ip in sourceIps) {
                try {
                    val conn = SrtlaConnection.create(ip, receiverHost, receiverPort, socketFactory)
                    connections += conn
                } catch (e: Exception) {
                    // log: failed to create uplink for $ip
                }
            }

            // Start probing (picks best uplink for initial REG1)
            reg.startProbing(connections)

            // Spawn per-uplink reader threads
            for (conn in connections) spawnReaderThread(conn)
        }

        // SRT listener thread
        val srtSock = DatagramSocket(localSrtPort)
        srtSocket = srtSock
        spawnThread("srt-listener") { srtListenerLoop(srtSock) }

        // Housekeeping thread
        spawnThread("housekeeping") { housekeepingLoop() }
    }

    /** Stop all threads and close sockets. */
    fun stop() {
        running.set(false)
        srtSocket?.close()
        stateLock.withLock {
            for (conn in connections) {
                try { conn.activeSocket.close() } catch (_: Exception) {}
            }
        }
        for (t in threads) t.interrupt()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SRT listener thread (src/sender/packet_handler.rs::handle_srt_packet)
    // ─────────────────────────────────────────────────────────────────────────

    private fun srtListenerLoop(sock: DatagramSocket) {
        val buf = ByteArray(MTU)
        val pkt = DatagramPacket(buf, buf.size)
        while (running.get()) {
            try {
                sock.receive(pkt)
                val data = buf.copyOf(pkt.length)
                val src = pkt.socketAddress

                stateLock.withLock {
                    lastClientAddr = src
                    drainInbound()
                    forwardSrtPacket(data)
                }
            } catch (_: java.net.SocketException) { break }
            catch (_: Exception) {}
        }
    }

    private fun forwardSrtPacket(data: ByteArray) {
        // stateLock must be held
        val now = System.currentTimeMillis()
        val seq = getSrtSequenceNumber(data)

        val selIdx = if (!reg.hasConnected) {
            // Pre-registration: pick any non-timed-out connection
            selectPreRegistration()
        } else {
            selectionOrchestrator.select(connections, lastSelectedIdx, lastSwitchMs, now, config)
        }

        if (selIdx == null) return

        if (lastSelectedIdx != selIdx) {
            lastSelectedIdx = selIdx
            lastSwitchMs = now
        }

        val conn = connections[selIdx]
        conn.sendPacket(data)
        conn.bitrate.updateOnSend(data.size.toLong())
        if (seq != null) {
            conn.registerPacket(seq, now)
            seqTracker.insert(seq, conn.connId, now)
        }
        conn.lastSentMs = now
    }

    private fun selectPreRegistration(): Int? {
        val last = lastSelectedIdx
        if (last != null) {
            val c = connections.getOrNull(last)
            if (c != null && !c.isTimedOut()) return last
        }
        return connections.indexOfFirst { !it.isTimedOut() }.takeIf { it >= 0 }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-uplink reader threads (src/sender/uplink.rs::spawn_reader)
    // ─────────────────────────────────────────────────────────────────────────

    private fun spawnReaderThread(conn: SrtlaConnection) {
        val connId = conn.connId
        val sock = conn.activeSocket
        spawnThread("uplink-${conn.label}") {
            while (running.get()) {
                val bytes: ByteArray = sock.recv() ?: run {
                    Thread.sleep(10)
                    return@spawnThread
                }
                inboundQueue.offer(UplinkPacket(connId, bytes), 100, TimeUnit.MILLISECONDS)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inbound packet processing (src/sender/packet_handler.rs)
    // ─────────────────────────────────────────────────────────────────────────

    /** Drain inbound queue under stateLock. */
    private fun drainInbound(maxPackets: Int = 64) {
        var count = 0
        while (count < maxPackets) {
            val pkt = inboundQueue.poll() ?: break
            processUplinkPacket(pkt)
            count++
        }
    }

    private fun processUplinkPacket(pkt: UplinkPacket) {
        // stateLock must be held
        val connIdx = connections.indexOfFirst { it.connId == pkt.connId }
        if (connIdx < 0) return
        val conn = connections[connIdx]

        val buf = pkt.bytes
        val now = System.currentTimeMillis()
        val pt = getPacketType(buf) ?: return

        // Registration packets
        val event = reg.processRegistrationPacket(connIdx, buf)
        if (event != null) {
            when (event) {
                is RegistrationManager.RegistrationEvent.RegNgp ->
                    reg.trySendReg1Immediately(connIdx, conn)
                is RegistrationManager.RegistrationEvent.Reg3 -> {
                    conn.clearPreRegistrationState()
                    conn.connected = true
                    conn.lastReceivedMs = now
                    if (conn.reconnection.connectionEstablishedMs == 0L)
                        conn.reconnection.connectionEstablishedMs = now
                    conn.reconnection.markSuccess(conn.label)
                }
                is RegistrationManager.RegistrationEvent.RegErr -> {
                    conn.connected = false
                    conn.lastReceivedMs = 0L
                }
                else -> {}
            }
            return
        }

        conn.lastReceivedMs = now

        when (pt) {
            SRT_TYPE_ACK -> {
                val ack = parseSrtAck(buf)
                if (ack != null) {
                    // ACK applies to ALL uplinks (src/sender/packet_handler.rs)
                    for (c in connections) c.handleSrtAck(ack)
                }
                forwardToClient(buf)
            }
            SRT_TYPE_NAK -> {
                val naks = parseSrtNak(buf)
                val nowMs = System.currentTimeMillis()
                for (seq in naks) {
                    // NAK attributed via seqTracker; fallback to current uplink
                    val ownerConnId = seqTracker.get(seq, nowMs)
                    val target = if (ownerConnId != null)
                        connections.find { it.connId == ownerConnId }
                    else conn
                    target?.handleNak(seq)
                        ?: connections.forEach { it.handleNak(seq) }
                }
                forwardToClient(buf)
            }
            SRTLA_TYPE_ACK -> {
                val acks = parseSrtlaAck(buf)
                for (seq in acks) {
                    for (c in connections) {
                        if (c.handleSrtlaAckSpecific(seq, config.mode.isClassic())) break
                    }
                    for (c in connections) c.handleSrtlaAckGlobal()
                }
            }
            SRTLA_TYPE_KEEPALIVE -> {
                conn.rtt.handleKeepaliveResponse(buf, conn.label)
            }
            else -> forwardToClient(buf)
        }
    }

    private fun forwardToClient(data: ByteArray) {
        val addr = lastClientAddr ?: return
        val sock = srtSocket ?: return
        try {
            val pkt = DatagramPacket(data, data.size, addr)
            sock.send(pkt)
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Housekeeping thread (src/sender/housekeeping.rs)
    // ─────────────────────────────────────────────────────────────────────────

    private fun housekeepingLoop() {
        while (running.get()) {
            try {
                Thread.sleep(HOUSEKEEPING_INTERVAL_MS)
                stateLock.withLock { doHousekeeping() }
            } catch (_: InterruptedException) { break }
            catch (_: Exception) {}
        }
    }

    private fun doHousekeeping() {
        // stateLock must be held
        drainInbound()

        val now = System.currentTimeMillis()
        reg.clearPendingIfTimedOut(now)

        if (reg.isProbing()) reg.checkProbingComplete()

        for ((i, conn) in connections.withIndex()) {
            if (conn.isTimedOut()) {
                if (conn.shouldAttemptReconnect()) {
                    conn.recordReconnectAttempt()
                    try {
                        conn.reconnect(socketFactory)
                        // Re-register after reconnect
                        when {
                            reg.pendingReg2Idx() == i -> reg.sendReg1To(i, conn)
                            else                       -> reg.sendReg2To(conn)
                        }
                    } catch (e: Exception) {
                        conn.markForRecovery()
                    }
                }
                continue
            }

            if (conn.needsKeepalive()) conn.sendKeepalive()
            if (conn.needsRttMeasurement()) conn.sendKeepalive()
            if (!config.mode.isClassic()) conn.performWindowRecovery()
            conn.calculateBitrate()
        }

        reg.updateActiveConnections(connections)
        reg.regDriverSendIfNeeded(connections)

        // Global timeout check (src/sender/housekeeping.rs::GLOBAL_TIMEOUT_MS)
        val activeCount = connections.count { !it.isTimedOut() }
        if (activeCount == 0) {
            if (allFailedAtMs == 0L) allFailedAtMs = now
            if (reg.hasConnected && (now - allFailedAtMs) > GLOBAL_TIMEOUT_MS) {
                running.set(false)
            }
        } else {
            allFailedAtMs = 0L
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Thread helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun spawnThread(name: String, block: () -> Unit): Thread {
        val t = Thread(block, "srtla-$name")
        t.isDaemon = true
        t.start()
        threads += t
        return t
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test / inspection accessors
    // ─────────────────────────────────────────────────────────────────────────

    fun connectionCount(): Int = stateLock.withLock { connections.size }
    fun activeConnectionCount(): Int = stateLock.withLock { connections.count { it.connected } }
    fun getConnections(): List<SrtlaConnection> = stateLock.withLock { connections.toList() }
    fun getRegistrationManager(): RegistrationManager = reg
}
