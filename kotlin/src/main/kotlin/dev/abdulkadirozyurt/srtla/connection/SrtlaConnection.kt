// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/connection/mod.rs, ack_nak.rs, packet_io.rs
//
// SrtlaConnection holds all per-uplink state: socket, window management,
// in-flight packet tracking, RTT, congestion control, bitrate, reconnection.
//
// LOCKING: This class is NOT thread-safe on its own. The caller (SrtlaSender)
// holds a single ReentrantLock while reading/writing connections. Per-uplink
// reader threads deliver packets via a LinkedBlockingQueue; the sender thread
// drains that queue under the lock. See SrtlaSender for the locking strategy.
//
// JVM DEVIATION: batch_send/batch_recv → plain socket send/recv loops.
package dev.abdulkadirozyurt.srtla.connection

import dev.abdulkadirozyurt.srtla.connection.congestion.CongestionControl
import dev.abdulkadirozyurt.srtla.connection.congestion.IntRef
import dev.abdulkadirozyurt.srtla.protocol.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

// src/connection/mod.rs
internal const val STARTUP_GRACE_MS: Long = 5_000L

/**
 * All state for one SRTLA uplink connection.
 * Mirrors Rust `struct SrtlaConnection` in src/connection/mod.rs.
 */
class SrtlaConnection(
    val connId: Long,
    val socket: UplinkSocket,
    val remoteAddr: InetSocketAddress,
    val localIp: InetAddress,
    val label: String,
    factory: UplinkSocketFactory = DefaultUplinkSocketFactory,
) {
    // Window held as plain Int; mutations go through IntRef wrappers passed to CongestionControl
    var window: Int = WINDOW_DEF * WINDOW_MULT
    var inFlightPackets: Int = 0

    // Packet log: seq (Long) → sendTimeMs (Long). O(1) insert/remove via HashMap.
    // PKT_LOG_SIZE = 256 is the initial capacity hint (src/protocol/constants.rs).
    val packetLog: HashMap<Long, Long> = HashMap(PKT_LOG_SIZE)
    var highestAckedSeq: Long = Long.MIN_VALUE

    var connected: Boolean = false
    var lastReceivedMs: Long = 0L   // 0 = never received
    var lastSentMs: Long = 0L
    var lastKeepaliveSentMs: Long = 0L

    val rtt: RttTracker = RttTracker()
    val congestion: CongestionControl = CongestionControl()
    val bitrate: BitrateTracker = BitrateTracker()
    val reconnection: ReconnectionState = ReconnectionState()

    // Stored for reconnection
    private var _factory: UplinkSocketFactory = factory
    private var _socket: UplinkSocket = socket

    // ---------------------------------------------------------------------------
    // Score calculation (src/connection/mod.rs::get_score)
    // ---------------------------------------------------------------------------

    /**
     * Classic capacity score = window / (in_flight + 1).
     * Returns -1 if disconnected.
     * Mirrors Rust `SrtlaConnection::get_score`.
     */
    fun getScore(): Int {
        if (!connected) return -1
        val denom = (inFlightPackets + 1).coerceAtLeast(1)
        return window / denom
    }

    // ---------------------------------------------------------------------------
    // Packet send (src/connection/packet_io.rs → socket.rs)
    // ---------------------------------------------------------------------------

    /** Send raw bytes on the uplink socket. */
    fun sendPacket(data: ByteArray) {
        _socket.send(data)
        lastSentMs = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------------------
    // Packet register / ACK / NAK (src/connection/ack_nak.rs)
    // ---------------------------------------------------------------------------

    /**
     * Register a packet as in-flight.
     * Mirrors Rust `SrtlaConnection::register_packet`.
     */
    fun registerPacket(seq: Long, sendTimeMs: Long) {
        packetLog[seq] = sendTimeMs
        inFlightPackets = packetLog.size
    }

    /**
     * Handle SRT cumulative ACK — clears all packets with seq <= ack.
     * Mirrors Rust `SrtlaConnection::handle_srt_ack`.
     */
    fun handleSrtAck(ack: Long) {
        if (ack <= highestAckedSeq) return

        val ackSendTimeMs = packetLog[ack]
        val oldHighest = highestAckedSeq
        highestAckedSeq = ack

        val rangeSize = ack - oldHighest
        if (rangeSize <= 64L && oldHighest != Long.MIN_VALUE) {
            var seq = oldHighest + 1L
            while (seq <= ack) { packetLog.remove(seq); seq++ }
        } else {
            packetLog.entries.removeIf { it.key <= ack }
        }
        inFlightPackets = packetLog.size

        // RTT estimate from ACK send time
        if (ackSendTimeMs != null) {
            val now = System.currentTimeMillis()
            val rttMs = now - ackSendTimeMs
            if (rttMs in 1L..10_000L) rtt.updateEstimate(rttMs)
        }
    }

    /**
     * Handle NAK for a specific sequence. Returns true if the packet was found.
     * Mirrors Rust `SrtlaConnection::handle_nak`.
     */
    fun handleNak(seq: Long): Boolean {
        val found = packetLog.remove(seq) != null
        if (found) {
            inFlightPackets = packetLog.size
            val windowRef = IntRef(window)
            congestion.handleNak(windowRef, seq.toInt(), label)
            window = windowRef.value
        }
        return found
    }

    /**
     * Handle SRTLA specific ACK for a sequence.
     * Returns true if the packet was found.
     * Mirrors Rust `SrtlaConnection::handle_srtla_ack_specific`.
     */
    fun handleSrtlaAckSpecific(seq: Long, classicMode: Boolean): Boolean {
        val found = packetLog.remove(seq) != null
        if (found) {
            inFlightPackets = packetLog.size
            val windowRef = IntRef(window)
            if (classicMode) {
                congestion.handleSrtlaAckClassic(windowRef, inFlightPackets, seq.toInt(), label)
            } else {
                congestion.handleSrtlaAckEnhanced(windowRef, inFlightPackets, label)
            }
            window = windowRef.value
        }
        return found
    }

    /**
     * Global SRTLA ACK: +1 window increase for all connected connections that
     * have received data. Mirrors Rust `SrtlaConnection::handle_srtla_ack_global`.
     */
    fun handleSrtlaAckGlobal() {
        if (connected && lastReceivedMs > 0L) {
            window = minOf(window + 1, WINDOW_MAX * WINDOW_MULT)
        }
    }

    // ---------------------------------------------------------------------------
    // Keepalive (src/connection/mod.rs)
    // ---------------------------------------------------------------------------

    /** Build and send an extended keepalive packet. */
    fun sendKeepalive() {
        val info = ConnectionInfo(
            connId          = connId and 0xFFFFFFFFL,
            window          = window,
            inFlight        = inFlightPackets,
            rttMs           = rtt.kalmanRtt.value.toLong().coerceAtLeast(0L),
            nakCount        = congestion.nakCount.toLong(),
            bitrateBytesSec = (bitrate.currentBitrateBps / 8.0).toLong().coerceAtLeast(0L),
        )
        val pkt = createKeepalivePacketExt(info)
        sendPacket(pkt)
        val now = System.currentTimeMillis()
        lastKeepaliveSentMs = now
        // Only set waiting flag when we intend to measure RTT
        if (!rtt.waitingForKeepaliveResponse
            && (rtt.lastRttMeasurementMs == 0L || now - rtt.lastRttMeasurementMs > 3000L)) {
            rtt.recordKeepaliveSent()
        }
    }

    /** Whether a keepalive should be sent (idle > IDLE_TIME seconds). */
    fun needsKeepalive(): Boolean {
        if (!connected) return false
        if (lastKeepaliveSentMs == 0L) return true
        return (System.currentTimeMillis() - lastKeepaliveSentMs) >= IDLE_TIME * 1000L
    }

    /** Whether we should send a keepalive to measure RTT. */
    fun needsRttMeasurement(): Boolean =
        rtt.needsMeasurement(connected, reconnection.connectionEstablishedMs)

    // ---------------------------------------------------------------------------
    // Window recovery (enhanced mode, src/connection/mod.rs)
    // ---------------------------------------------------------------------------

    fun performWindowRecovery() {
        val windowRef = IntRef(window)
        congestion.performWindowRecovery(windowRef, connected, label)
        window = windowRef.value
    }

    // ---------------------------------------------------------------------------
    // Timeout / timed-out detection (src/connection/mod.rs)
    // ---------------------------------------------------------------------------

    /** Whether this connection is considered timed out. */
    fun isTimedOut(): Boolean {
        if (!connected) {
            if (reconnection.connectionEstablishedMs == 0L) {
                val now = System.currentTimeMillis()
                if (now < reconnection.startupGraceDeadlineMs) return false
            }
            return lastReceivedMs == 0L
                || (System.currentTimeMillis() - lastReceivedMs) >= CONN_TIMEOUT * 1000L
        }
        if (lastReceivedMs == 0L) return false
        return (System.currentTimeMillis() - lastReceivedMs) >= CONN_TIMEOUT * 1000L
    }

    // ---------------------------------------------------------------------------
    // Reconnection helpers (src/connection/mod.rs)
    // ---------------------------------------------------------------------------

    fun shouldAttemptReconnect(): Boolean = reconnection.shouldAttemptReconnect()
    fun recordReconnectAttempt() = reconnection.recordAttempt(label)
    fun markReconnectSuccess() = reconnection.markSuccess(label)

    /**
     * Mark connection for recovery (soft reset, C-style).
     * Mirrors Rust `SrtlaConnection::mark_for_recovery`.
     */
    fun markForRecovery() {
        lastReceivedMs = 0L
        lastKeepaliveSentMs = 0L
        rtt.lastKeepaliveSentMs = 0L
        rtt.waitingForKeepaliveResponse = false
        connected = false
        window = WINDOW_DEF * WINDOW_MULT
        inFlightPackets = 0
        packetLog.clear()
        highestAckedSeq = Long.MIN_VALUE
        reconnection.startupGraceDeadlineMs = 0L
    }

    /**
     * Clear state accumulated during pre-registration phase.
     * Called when REG3 is received.
     * Mirrors Rust `SrtlaConnection::clear_pre_registration_state`.
     */
    fun clearPreRegistrationState() {
        packetLog.clear()
        inFlightPackets = 0
        highestAckedSeq = Long.MIN_VALUE
        congestion.reset()
    }

    /**
     * Reconnect: replace socket with a new one bound from the same source IP.
     * Mirrors Rust `SrtlaConnection::reconnect`.
     */
    fun reconnect(factory: UplinkSocketFactory = _factory) {
        try { _socket.close() } catch (_: Exception) {}
        val newCh = factory.createAndBind(localIp, remoteAddr)
        _socket = UplinkSocket(newCh)

        lastReceivedMs = 0L
        connected = false
        window = WINDOW_DEF * WINDOW_MULT
        inFlightPackets = 0
        packetLog.clear()
        highestAckedSeq = Long.MIN_VALUE
        congestion.reset()
        rtt.reset()
        bitrate.reset()

        reconnection.lastReconnectAttemptMs = System.currentTimeMillis()
        reconnection.reconnectFailureCount = 0
        markReconnectSuccess()
        reconnection.resetStartupGrace()
    }

    /** Access the current socket for reading. */
    val activeSocket: UplinkSocket get() = _socket

    // Convenience delegation for bitrate
    fun calculateBitrate() = bitrate.calculate()
    fun currentBitrateMbps(): Double = bitrate.mbps()

    // RTT accessors
    fun getSmoothRttMs(): Double = rtt.kalmanRtt.value
    fun getRttVelocity(): Double = rtt.kalmanRtt.velocity
    fun getRttMinMs(): Double = rtt.rttMinMs
    fun getRttJitterMs(): Double = rtt.rttJitterMs
    fun isRttStable(): Boolean = rtt.isStable()

    fun timeSinceLastNakMs(): Long? = congestion.timeSinceLastNakMs()
    fun totalNakCount(): Int = congestion.nakCount
    fun nakBurstCount(): Int = congestion.nakBurstCount
    fun connectionEstablishedMs(): Long = reconnection.connectionEstablishedMs

    companion object {
        /**
         * Create a connection bound to [sourceIp] targeting [host]:[port].
         * Mirrors Rust `SrtlaConnection::connect_from_ip`.
         */
        fun create(
            sourceIp: InetAddress,
            host: String,
            port: Int,
            factory: UplinkSocketFactory = DefaultUplinkSocketFactory,
        ): SrtlaConnection {
            val remote = InetSocketAddress(InetAddress.getByName(host), port)
            val ch = factory.createAndBind(sourceIp, remote)
            val socket = UplinkSocket(ch)
            val connId = SecureRandom().nextLong()
            val conn = SrtlaConnection(
                connId    = connId,
                socket    = socket,
                remoteAddr = remote,
                localIp   = sourceIp,
                label     = "$host:$port via ${sourceIp.hostAddress}",
                factory   = factory,
            )
            conn.reconnection.startupGraceDeadlineMs = System.currentTimeMillis() + STARTUP_GRACE_MS
            return conn
        }
    }
}
