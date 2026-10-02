// Copyright (c) 2025-2026 Abdulkadir Özyurt — Kotlin port of irlserver/srtla_send (MIT)
// MockSrtlaReceiver: E2E test helper simulating an SRTLA receiver over loopback UDP.
//
// Protocol behaviour (mirrors srtla_rec):
//   1. REG1 → keep the sender's first id half, mint the second half, reply REG2.
//   2. REG2 carrying an id we minted → join the group, reply REG3.
//      REG2 carrying an unknown id (the sender's RTT probe) → reply REG_NGP.
//   3. KEEPALIVE → echoed verbatim (RTT measurement).
//   4. SRT data → counted (distinct sequences and retransmit-flagged copies);
//      optional per-packet SRTLA ACK back on the arrival address.
//   5. SRT ACK / NAK injection toward one or every registered uplink.
//
// Thread-safety: one reader thread; shared state guarded by `lock`.
package dev.abdulkadirozyurt.srtla.e2e

import dev.abdulkadirozyurt.srtla.protocol.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MockSrtlaReceiver {
    private val socket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
    val port: Int get() = socket.localPort

    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private val lock = Any()

    /** Group ids this receiver minted (REG2 replies). */
    private val mintedIds = ArrayList<ByteArray>()
    /** Uplink source addresses that completed REG3. */
    private val registered = LinkedHashSet<SocketAddress>()
    /** Distinct SRT data sequences received. */
    private val seenSeqs = HashSet<Int>()

    val srtDataPacketsReceived = AtomicInteger(0)
    val retransmitFlaggedReceived = AtomicInteger(0)
    val keepalivesReceived = AtomicInteger(0)
    val reg1Received = AtomicInteger(0)
    val reg3Sent = AtomicInteger(0)
    val regNgpSent = AtomicInteger(0)

    /** Send an SRTLA ACK for every data packet, on the arrival address. */
    @Volatile var autoAck: Boolean = true

    /** When false, drop every inbound packet (simulates a dead receiver). */
    @Volatile var answering: Boolean = true

    fun start() {
        if (!running.compareAndSet(false, true)) return
        socket.soTimeout = 200
        readerThread = Thread(::readerLoop, "mock-receiver-$port").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        socket.close()
        readerThread?.join(1000)
    }

    private fun readerLoop() {
        val buf = ByteArray(MTU + 100)
        val pkt = DatagramPacket(buf, buf.size)
        while (running.get()) {
            try {
                socket.receive(pkt)
                if (!answering) continue
                handlePacket(buf.copyOf(pkt.length), pkt.socketAddress)
            } catch (_: java.net.SocketTimeoutException) {
            } catch (_: java.net.SocketException) {
                break
            } catch (_: Exception) {
            }
        }
    }

    private fun handlePacket(data: ByteArray, from: SocketAddress) {
        when (getPacketType(data) ?: return) {
            SRTLA_TYPE_REG1 -> handleReg1(data, from)
            SRTLA_TYPE_REG2 -> handleReg2(data, from)
            SRTLA_TYPE_KEEPALIVE -> {
                keepalivesReceived.incrementAndGet()
                send(data, from)
            }
            else -> {
                val seq = getSrtSequenceNumber(data) ?: return
                srtDataPacketsReceived.incrementAndGet()
                if (isSrtDataRetransmit(data)) retransmitFlaggedReceived.incrementAndGet()
                synchronized(lock) { seenSeqs.add(seq) }
                if (autoAck) send(createAckPacket(intArrayOf(seq)), from)
            }
        }
    }

    private fun handleReg1(data: ByteArray, from: SocketAddress) {
        if (data.size < 2 + SRTLA_ID_LEN) return
        reg1Received.incrementAndGet()
        val id = data.copyOfRange(2, 2 + SRTLA_ID_LEN)
        for (i in SRTLA_ID_LEN / 2 until SRTLA_ID_LEN) id[i] = (id[i].toInt() xor 0x55).toByte()
        synchronized(lock) { mintedIds.add(id) }
        send(createReg2Packet(id), from)
    }

    private fun handleReg2(data: ByteArray, from: SocketAddress) {
        if (data.size < 2 + SRTLA_ID_LEN) return
        val id = data.copyOfRange(2, 2 + SRTLA_ID_LEN)
        val known = synchronized(lock) { mintedIds.any { it.contentEquals(id) } }
        if (!known) {
            regNgpSent.incrementAndGet()
            send(byteArrayOf(0x92.toByte(), 0x11), from) // REG_NGP
            return
        }
        synchronized(lock) { registered.add(from) }
        reg3Sent.incrementAndGet()
        send(createReg3Packet(), from)
    }

    /** SRT ACK (cumulative) with [ackSeq] at bytes 16..19, to one or every uplink. */
    fun sendSrtAck(ackSeq: Int, timestamp: Int = 1, to: SocketAddress? = null) {
        val pkt = ByteArray(44)
        writeU16BE(pkt, 0, SRT_TYPE_ACK)
        writeI32BE(pkt, 8, timestamp)
        writeI32BE(pkt, 16, ackSeq)
        for (addr in targets(to)) send(pkt, addr)
    }

    /** SRT NAK whose loss list (after the 16-byte control header) holds [seqs]. */
    fun injectNak(seqs: IntArray, to: SocketAddress? = null) {
        val pkt = ByteArray(SRT_CONTROL_HEADER_LEN + 4 * seqs.size)
        writeU16BE(pkt, 0, SRT_TYPE_NAK)
        for ((i, s) in seqs.withIndex()) writeI32BE(pkt, SRT_CONTROL_HEADER_LEN + 4 * i, s and 0x7FFF_FFFF)
        for (addr in targets(to)) send(pkt, addr)
    }

    private fun targets(to: SocketAddress?): List<SocketAddress> =
        if (to != null) listOf(to) else registeredUplinkAddresses()

    fun registeredUplinkAddresses(): List<SocketAddress> = synchronized(lock) { registered.toList() }

    fun registeredCount(): Int = synchronized(lock) { registered.size }

    fun distinctSeqCount(): Int = synchronized(lock) { seenSeqs.size }

    fun pollUntil(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    fun pollUntilRegistered(minCount: Int, timeoutMs: Long = 8000): Boolean =
        pollUntil(timeoutMs) { registeredCount() >= minCount }

    private fun send(data: ByteArray, to: SocketAddress) {
        try {
            if (!socket.isClosed) socket.send(DatagramPacket(data, data.size, to))
        } catch (_: Exception) {
        }
    }
}
