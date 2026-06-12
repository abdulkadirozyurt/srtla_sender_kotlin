// Copyright (c) 2025-2026 Abdulkadir Özyurt — Kotlin port of irlserver/srtla_send (MIT)
// MockSrtlaReceiver: E2E test helper simulating a SRTLA receiver over loopback UDP.
// Protocol behaviour mirrors irlserver/srtla_recv REG1→REG2→REG3 handshake.
// E2E test helper — MockSrtlaReceiver
//
// Simulates a minimal SRTLA receiver for end-to-end testing over real loopback UDP.
//
// Protocol behaviour (mirrors irlserver srtla_recv):
//   1. REG1 received → modify last 128 bytes of ID, reply REG2
//   2. REG2 received from each uplink → reply REG3 (connection group accept)
//   3. Keepalive received → echo back (RTT measurement support)
//   4. SRT data packets counted in stats
//   5. SRTLA ACK sent on demand via sendAck()
//   6. NAK injection via injectNak()
//
// Thread-safety: all mutable state protected by synchronized(lock).
// The receiver runs a single background thread reading from one UDP socket
// (all uplinks send to the same receiver port, identified by sender address).
package dev.abdulkadirozyurt.srtla.e2e

import dev.abdulkadirozyurt.srtla.protocol.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks one registered uplink connection group entry.
 * An "uplink" is identified by the sender's (address, port) tuple.
 */
data class UplinkEntry(
    val addr: SocketAddress,
    val groupId: ByteArray,          // 256-byte registration ID (receiver's copy)
    var reg3Sent: Boolean = false,
    val nakCount: AtomicInteger = AtomicInteger(0),
)

/**
 * Mock SRTLA receiver — lightweight UDP server for E2E tests.
 *
 * Binds to 127.0.0.1:0 (ephemeral port). Use [port] after construction.
 * Call [start] to begin serving, [stop] to shut down.
 */
class MockSrtlaReceiver {

    // ── Socket ────────────────────────────────────────────────────────────────
    private val socket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
    val port: Int get() = socket.localPort

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null

    // ── State lock ────────────────────────────────────────────────────────────
    private val lock = Any()

    // ── Connection group (senders that completed REG3) ────────────────────────
    /** Registered uplinks — address → UplinkEntry */
    private val registeredUplinks: MutableMap<SocketAddress, UplinkEntry> = LinkedHashMap()

    // ── Pending registrations (REG1 received, REG2 sent, awaiting REG2-reply) ─
    /** Uplinks that sent REG1; storing the modified ID we sent back as REG2. */
    private val pendingReg2: MutableMap<SocketAddress, ByteArray> = HashMap()

    // ── Statistics ────────────────────────────────────────────────────────────
    /** Total SRT data packets received (all uplinks). */
    val srtDataPacketsReceived = AtomicInteger(0)
    /** Total keepalives received. */
    val keepalivesReceived = AtomicInteger(0)
    /** Last keepalive sender address (for RTT echo tracking). */
    @Volatile var lastKeepaliveFrom: SocketAddress? = null
    /** Total REG1 packets received. */
    val reg1Received = AtomicInteger(0)
    /** Total REG3 confirmations sent. */
    val reg3Sent = AtomicInteger(0)

    // ── Configurable ACK generation ───────────────────────────────────────────
    /** Whether to auto-send SRTLA ACKs for every data packet. */
    @Volatile var autoAck: Boolean = false
    /** ACK accumulation window — send ACK every N data packets. */
    @Volatile var ackEveryN: Int = 0   // 0 = manual only

    private val ackCounter = AtomicInteger(0)

    // ── Latch helpers for test synchronisation ────────────────────────────────
    /** Latch released when [minUplinks] uplinks have completed REG3. */
    private var registrationLatch: CountDownLatch = CountDownLatch(Int.MAX_VALUE)
    private var registrationLatchTarget: Int = Int.MAX_VALUE

    /**
     * Set up a latch that is released once [count] uplinks have registered (REG3 confirmed).
     * Must be called before [start].
     */
    fun expectRegistrations(count: Int) {
        registrationLatchTarget = count
        registrationLatch = CountDownLatch(count)
    }

    /**
     * Wait until [expectRegistrations] count is reached (or [timeoutMs] elapses).
     * Returns true if condition was met.
     */
    fun awaitRegistrations(timeoutMs: Long = 3000): Boolean =
        registrationLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    // ── Start / stop ──────────────────────────────────────────────────────────

    fun start() {
        if (!running.compareAndSet(false, true)) return
        socket.soTimeout = 200   // 200 ms poll so we can check running flag
        readerThread = Thread(::readerLoop, "mock-receiver-${port}").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        socket.close()
        readerThread?.join(1000)
    }

    // ── Reader loop ───────────────────────────────────────────────────────────

    private fun readerLoop() {
        val buf = ByteArray(MTU + 100)
        val pkt = DatagramPacket(buf, buf.size)
        while (running.get()) {
            try {
                socket.receive(pkt)
                val data = buf.copyOf(pkt.length)
                val from = pkt.socketAddress
                handlePacket(data, from)
            } catch (_: java.net.SocketTimeoutException) {
                /* poll timeout — check running */
            } catch (_: java.net.SocketException) {
                break  // socket closed
            } catch (e: Exception) {
                /* ignore individual packet errors */
            }
        }
    }

    private fun handlePacket(data: ByteArray, from: SocketAddress) {
        val pt = getPacketType(data) ?: return

        when (pt) {
            SRTLA_TYPE_REG1 -> handleReg1(data, from)
            SRTLA_TYPE_REG2 -> handleReg2Client(data, from)
            SRTLA_TYPE_KEEPALIVE -> handleKeepalive(data, from)
            else -> {
                // SRT data or control packet
                if (isSrtData(data)) {
                    srtDataPacketsReceived.incrementAndGet()
                    maybeSendAutoAck(data, from)
                }
                // Other SRT control (ACK, NAK) — ignore for now
            }
        }
    }

    // ── REG1 → REG2 handshake ─────────────────────────────────────────────────

    private fun handleReg1(data: ByteArray, from: SocketAddress) {
        if (data.size < 2 + SRTLA_ID_LEN) return
        reg1Received.incrementAndGet()

        // Extract sender's ID (bytes 2..257)
        val senderId = data.copyOfRange(2, 2 + SRTLA_ID_LEN)

        // Modify last 128 bytes — mirrors irlserver receiver behaviour
        val modifiedId = senderId.copyOf()
        for (i in 128 until SRTLA_ID_LEN) {
            modifiedId[i] = (modifiedId[i].toInt() xor 0x55).toByte()
        }

        synchronized(lock) {
            pendingReg2[from] = modifiedId
        }

        // Send REG2 with modified ID
        val reg2 = createReg2Packet(modifiedId)
        send(reg2, from)
    }

    private fun handleReg2Client(data: ByteArray, from: SocketAddress) {
        // Sender broadcasts REG2 (with server-modified ID) to confirm group join
        if (data.size < 2 + SRTLA_ID_LEN) return
        val receivedId = data.copyOfRange(2, 2 + SRTLA_ID_LEN)

        synchronized(lock) {
            // Accept: store in registered uplinks
            val entry = UplinkEntry(addr = from, groupId = receivedId)
            registeredUplinks[from] = entry
        }

        // Reply with REG3 to confirm
        val reg3 = createReg3Packet()
        send(reg3, from)

        reg3Sent.incrementAndGet()

        // Release registration latch if target reached
        synchronized(lock) {
            if (registeredUplinks.size >= registrationLatchTarget) {
                // Count down remaining (idempotent — latch won't go negative)
                repeat(registrationLatch.count.toInt()) { registrationLatch.countDown() }
            }
        }
    }

    // ── Keepalive echo ────────────────────────────────────────────────────────

    private fun handleKeepalive(data: ByteArray, from: SocketAddress) {
        keepalivesReceived.incrementAndGet()
        lastKeepaliveFrom = from
        // Echo packet back verbatim — sender uses timestamp to measure RTT
        send(data, from)
    }

    // ── Auto-ACK ─────────────────────────────────────────────────────────────

    private fun maybeSendAutoAck(data: ByteArray, from: SocketAddress) {
        val seq = getSrtSequenceNumber(data) ?: return

        if (autoAck) {
            val ack = createAckPacket(listOf(seq))
            send(ack, from)
            return
        }

        if (ackEveryN > 0) {
            val n = ackCounter.incrementAndGet()
            if (n % ackEveryN == 0) {
                val ack = createAckPacket(listOf(seq))
                send(ack, from)
            }
        }
    }

    // ── Manual ACK / NAK injection ────────────────────────────────────────────

    /**
     * Send an SRTLA ACK for [seqs] to [targetAddr].
     * If [targetAddr] is null, broadcasts to all registered uplinks.
     */
    fun sendAck(seqs: List<Long>, targetAddr: SocketAddress? = null) {
        val pkt = createAckPacket(seqs)
        val targets = if (targetAddr != null) {
            listOf(targetAddr)
        } else {
            synchronized(lock) { registeredUplinks.keys.toList() }
        }
        for (addr in targets) send(pkt, addr)
    }

    /**
     * Inject a NAK for [seqs] to [targetAddr].
     * Builds a minimal SRT NAK packet and sends it.
     * If [targetAddr] is null, sends to all registered uplinks.
     */
    fun injectNak(seqs: List<Long>, targetAddr: SocketAddress? = null) {
        val nak = buildNakPacket(seqs)
        val targets = if (targetAddr != null) {
            listOf(targetAddr)
        } else {
            synchronized(lock) { registeredUplinks.keys.toList() }
        }
        for (addr in targets) {
            send(nak, addr)
            synchronized(lock) {
                registeredUplinks[addr]?.nakCount?.incrementAndGet()
            }
        }
    }

    /** Build a minimal SRT NAK packet for [seqs] (single-value entries only). */
    private fun buildNakPacket(seqs: List<Long>): ByteArray {
        val payload = ByteArray(4 + 4 * seqs.size)
        writeU16BE(payload, 0, SRT_TYPE_NAK)
        writeU16BE(payload, 2, 0)  // reserved
        for ((i, seq) in seqs.withIndex()) {
            writeU32BE(payload, 4 + i * 4, seq and 0x7FFF_FFFFL)  // clear range-bit
        }
        return payload
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** Return a snapshot of currently registered uplink addresses. */
    fun registeredUplinkAddresses(): List<SocketAddress> =
        synchronized(lock) { registeredUplinks.keys.toList() }

    /** How many uplinks are currently registered. */
    fun registeredCount(): Int =
        synchronized(lock) { registeredUplinks.size }

    /** Wait (polling) until [minCount] uplinks are registered or timeout. */
    fun pollUntilRegistered(minCount: Int, timeoutMs: Long = 3000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (registeredCount() >= minCount) return true
            Thread.sleep(20)
        }
        return false
    }

    // ── Send helper ───────────────────────────────────────────────────────────

    private fun send(data: ByteArray, to: SocketAddress) {
        try {
            if (socket.isClosed) return
            val pkt = DatagramPacket(data, data.size, to)
            socket.send(pkt)
        } catch (_: Exception) {}
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Returns true if [data] is an SRT data packet (MSB of first byte = 0). */
    private fun isSrtData(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return (data[0].toInt() and 0x80) == 0
    }
}
