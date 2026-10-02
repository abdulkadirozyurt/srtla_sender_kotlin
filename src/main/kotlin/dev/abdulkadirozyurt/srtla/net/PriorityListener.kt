// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-send/src/priority_listener.rs
//
// Priority-sidecar UDP listener (I/O shell for CriticalWindow).
//
// Consumes the 5-byte critical-window datagrams described in
// dev.abdulkadirozyurt.srtla.core.Priority off a dedicated loopback UDP socket
// and pushes the derived deadlines into the shared CriticalWindow.

package dev.abdulkadirozyurt.srtla.net

import dev.abdulkadirozyurt.srtla.core.PRIORITY_DATAGRAM_LEN
import dev.abdulkadirozyurt.srtla.core.PRIORITY_PROTO_MAGIC
import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.telemetry.SubscriptionHub
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.priority_listener")

/**
 * Starts a daemon thread named "srtla-priority-sidecar" that listens for
 * priority datagrams on [bindAddr] and pushes the derived deadlines into [state].
 *
 * If [hub] is provided, also publishes a "priority.window" event to subscribers
 * on each accepted datagram so downstream consumers can correlate priority
 * events with video keyframes in real time.
 *
 * On bind failure, logs a warning and the thread ends. Returns the spawned thread.
 */
fun spawnPriorityListener(
    bindAddr: InetSocketAddress,
    state: CriticalWindow,
    hub: SubscriptionHub?
): Thread {
    val thread = Thread({
        val sock = try {
            DatagramSocket(bindAddr)
        } catch (e: Exception) {
            log.warning("failed to bind priority sidecar: $bindAddr, error: ${e.message}")
            return@Thread
        }

        val localAddr = try {
            sock.localSocketAddress
        } catch (e: Exception) {
            null
        }
        log.info("priority sidecar listening on $localAddr")

        val buf = ByteArray(16)
        try {
            while (true) {
                try {
                    val packet = java.net.DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val n = packet.length

                    if (n != PRIORITY_DATAGRAM_LEN || (buf[0].toInt() and 0xFF) != PRIORITY_PROTO_MAGIC) {
                        state.recordMalformed()
                        continue
                    }

                    // Extract window_ms as u32 big-endian from bytes 1..4
                    val windowMs = ((buf[1].toInt() and 0xFF) shl 24) or
                                  ((buf[2].toInt() and 0xFF) shl 16) or
                                  ((buf[3].toInt() and 0xFF) shl 8) or
                                  (buf[4].toInt() and 0xFF)
                    val windowMsLong = windowMs.toLong() and 0xFFFF_FFFFL

                    val now = nowMs()
                    state.extendTo(now + windowMsLong)

                    hub?.publish("priority.window", linkedMapOf(
                        "at_ms" to now,
                        "window_ms" to windowMsLong,
                        "deadline_ms" to now + windowMsLong
                    ))
                } catch (e: Exception) {
                    log.warning("priority sidecar recv error: ${e.message}")
                }
            }
        } finally {
            sock.close()
        }
    }, "srtla-priority-sidecar")

    thread.isDaemon = true
    thread.start()
    return thread
}
