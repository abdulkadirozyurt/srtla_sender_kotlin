// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/metrics.rs

package dev.abdulkadirozyurt.srtla.telemetry

import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

private val log = Logger.getLogger("srtla.metrics")

fun renderMetrics(stats: SharedStats, config: DynamicConfig, cw: CriticalWindow): String {
    val snap = stats.get()
    val out = StringBuilder(2048)

    // Link-level gauges. One series per link, labeled by local IP.
    out.append("# HELP srtla_send_link_up 1 if the link is connected and not timed out\n")
    out.append("# TYPE srtla_send_link_up gauge\n")
    for (link in snap.links) {
        val up = if (link.connected && !link.timedOut) 1 else 0
        out.append("srtla_send_link_up{ip=\"${link.ip}\"} $up\n")
    }

    out.append("# HELP srtla_send_link_rtt_ms smoothed RTT\n")
    out.append("# TYPE srtla_send_link_rtt_ms gauge\n")
    for (link in snap.links) {
        out.append("srtla_send_link_rtt_ms{ip=\"${link.ip}\"} ${link.rttMs}\n")
    }

    out.append("# HELP srtla_send_link_rtt_min_ms dual-window minimum RTT baseline\n")
    out.append("# TYPE srtla_send_link_rtt_min_ms gauge\n")
    for (link in snap.links) {
        out.append("srtla_send_link_rtt_min_ms{ip=\"${link.ip}\"} ${link.rttMinMs}\n")
    }

    out.append("# HELP srtla_send_link_rtt_velocity Kalman RTT velocity, ms/sample (positive = rising)\n")
    out.append("# TYPE srtla_send_link_rtt_velocity gauge\n")
    for (link in snap.links) {
        out.append("srtla_send_link_rtt_velocity{ip=\"${link.ip}\"} ${link.rttVelocity}\n")
    }

    out.append("# HELP srtla_send_link_window congestion window size (packets)\n")
    out.append("# TYPE srtla_send_link_window gauge\n")
    for (link in snap.links) {
        out.append("srtla_send_link_window{ip=\"${link.ip}\"} ${link.window}\n")
    }

    out.append("# HELP srtla_send_link_in_flight packets sent but not yet ACKed\n")
    out.append("# TYPE srtla_send_link_in_flight gauge\n")
    for (link in snap.links) {
        out.append("srtla_send_link_in_flight{ip=\"${link.ip}\"} ${link.inFlight}\n")
    }

    out.append("# HELP srtla_send_link_nak_total cumulative NAK count\n")
    out.append("# TYPE srtla_send_link_nak_total counter\n")
    for (link in snap.links) {
        out.append("srtla_send_link_nak_total{ip=\"${link.ip}\"} ${link.nakCount}\n")
    }

    out.append("# HELP srtla_send_link_bitrate_bytes_per_second measured send rate, bytes/sec\n")
    out.append("# TYPE srtla_send_link_bitrate_bytes_per_second gauge\n")
    for (link in snap.links) {
        out.append("srtla_send_link_bitrate_bytes_per_second{ip=\"${link.ip}\"} ${link.bitrateBytesPerSec}\n")
    }

    out.append("# HELP srtla_send_link_quality_multiplier scheduler quality multiplier in [0.35, 1.1]\n")
    out.append("# TYPE srtla_send_link_quality_multiplier gauge\n")
    for (link in snap.links) {
        out.append("srtla_send_link_quality_multiplier{ip=\"${link.ip}\"} ${link.qualityMultiplier}\n")
    }

    out.append("# HELP srtla_send_link_stall_gated link is stall-gated out of the payload rotation (0/1)\n")
    out.append("# TYPE srtla_send_link_stall_gated gauge\n")
    for (link in snap.links) {
        val gated = if (link.stallGated) 1 else 0
        out.append("srtla_send_link_stall_gated{ip=\"${link.ip}\"} $gated\n")
    }

    out.append("# HELP srtla_send_link_stall_gate_events cumulative stall-latch engagements\n")
    out.append("# TYPE srtla_send_link_stall_gate_events counter\n")
    for (link in snap.links) {
        out.append("srtla_send_link_stall_gate_events{ip=\"${link.ip}\"} ${link.stallGateEvents}\n")
    }

    out.append("# HELP srtla_send_link_sole_carrier link is the elected sole carrier while every link is quality gated (0/1)\n")
    out.append("# TYPE srtla_send_link_sole_carrier gauge\n")
    for (link in snap.links) {
        val carrier = if (link.soleCarrier) 1 else 0
        out.append("srtla_send_link_sole_carrier{ip=\"${link.ip}\"} $carrier\n")
    }

    out.append("# HELP srtla_send_link_sole_carrier_elections cumulative sole-carrier handovers taken from another link\n")
    out.append("# TYPE srtla_send_link_sole_carrier_elections counter\n")
    for (link in snap.links) {
        out.append("srtla_send_link_sole_carrier_elections{ip=\"${link.ip}\"} ${link.soleCarrierElections}\n")
    }

    out.append("# HELP srtla_send_link_silence_pulls cumulative fast silence-pull engagements\n")
    out.append("# TYPE srtla_send_link_silence_pulls counter\n")
    for (link in snap.links) {
        out.append("srtla_send_link_silence_pulls{ip=\"${link.ip}\"} ${link.silencePulls}\n")
    }

    out.append("# HELP srtla_send_link_weight operator link weight from the ips file, normalised so the lowest link is 1 (classic mode only)\n")
    out.append("# TYPE srtla_send_link_weight gauge\n")
    for (link in snap.links) {
        out.append("srtla_send_link_weight{ip=\"${link.ip}\"} ${link.weight}\n")
    }

    // Aggregate gauges.
    out.append("# HELP srtla_send_active_links links currently connected and live\n")
    out.append("# TYPE srtla_send_active_links gauge\n")
    out.append("srtla_send_active_links ${snap.activeLinks}\n")

    out.append("# HELP srtla_send_total_links configured link count\n")
    out.append("# TYPE srtla_send_total_links gauge\n")
    out.append("srtla_send_total_links ${snap.totalLinks}\n")

    out.append("# HELP srtla_send_total_window summed window across active links\n")
    out.append("# TYPE srtla_send_total_window gauge\n")
    out.append("srtla_send_total_window ${snap.totalWindow}\n")

    out.append("# HELP srtla_send_total_in_flight summed in-flight across active links\n")
    out.append("# TYPE srtla_send_total_in_flight gauge\n")
    out.append("srtla_send_total_in_flight ${snap.totalInFlight}\n")

    // Scheduler config surfaced as a gauge so Grafana can pivot on it.
    out.append("# HELP srtla_send_mode scheduling mode (0=classic,1=enhanced)\n")
    out.append("# TYPE srtla_send_mode gauge\n")
    val mode = if (config.mode().isClassic()) 0 else 1
    out.append("srtla_send_mode $mode\n")

    // Priority sidecar counters.
    out.append("# HELP srtla_send_critical_windows_total total keyframe-priority datagrams applied\n")
    out.append("# TYPE srtla_send_critical_windows_total counter\n")
    out.append("srtla_send_critical_windows_total ${cw.windowsReceived()}\n")

    out.append("# HELP srtla_send_critical_malformed_datagrams_total malformed priority-sidecar datagrams\n")
    out.append("# TYPE srtla_send_critical_malformed_datagrams_total counter\n")
    out.append("srtla_send_critical_malformed_datagrams_total ${cw.malformedDatagrams()}\n")

    return out.toString()
}

fun spawnMetricsServer(
    bind: InetSocketAddress,
    stats: SharedStats,
    config: DynamicConfig,
    cw: CriticalWindow
): Thread {
    val thread = Thread(
        {
            try {
                val serverSocket = ServerSocket()
                try {
                    serverSocket.bind(bind)
                    val local = serverSocket.localSocketAddress
                    log.info("prometheus /metrics endpoint listening on $local")

                    while (!Thread.currentThread().isInterrupted) {
                        try {
                            val socket = serverSocket.accept()
                            Thread(
                                {
                                    try {
                                        serveOne(socket, stats, config, cw)
                                    } catch (e: Exception) {
                                        log.fine("prometheus scrape error: ${e.message}")
                                    }
                                },
                                "srtla-metrics-handler"
                            ).apply { isDaemon = true }.start()
                        } catch (e: Exception) {
                            log.fine("prometheus accept error: ${e.message}")
                        }
                    }
                } finally {
                    try {
                        serverSocket.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            } catch (e: Exception) {
                log.warning("failed to bind prometheus endpoint at $bind: ${e.message}")
            }
        },
        "srtla-metrics"
    ).apply { isDaemon = true }

    thread.start()
    return thread
}

private fun serveOne(
    socket: java.net.Socket,
    stats: SharedStats,
    config: DynamicConfig,
    cw: CriticalWindow
) {
    try {
        socket.soTimeout = 5000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())

        // Read until we've seen the end of the request headers. One read
        // usually suffices for a scraper-originated GET; cap at 4 KiB to
        // prevent slowloris-style games.
        val buf = ByteArray(4096)
        var len = 0
        while (len < buf.size) {
            val n = input.read(buf, len, buf.size - len)
            if (n <= 0) break
            len += n
            // Check for end of headers (\r\n\r\n)
            if (len >= 4) {
                for (i in 0..(len - 4)) {
                    if (buf[i] == '\r'.code.toByte() &&
                        buf[i + 1] == '\n'.code.toByte() &&
                        buf[i + 2] == '\r'.code.toByte() &&
                        buf[i + 3] == '\n'.code.toByte()
                    ) {
                        len = i + 4
                        i.let { /* exit outer loop */ }
                        break
                    }
                }
                if (len != buf.size && buf[len - 4] == '\r'.code.toByte()) break
            }
        }

        val request = buf.sliceArray(0 until len)
        val path = requestPath(request, len)

        val body = when (path) {
            "/metrics", "/" -> renderMetrics(stats, config, cw)
            else -> {
                val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                output.write(resp.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                return
            }
        }

        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; version=0.0.4\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    } finally {
        try {
            socket.close()
        } catch (e: Exception) {
            // ignore
        }
    }
}

internal fun requestPath(request: ByteArray, len: Int): String? {
    // GET /metrics HTTP/1.1
    val firstLineEnd = request.indexOfFirst { it == '\r'.code.toByte() }
    if (firstLineEnd < 0) return null

    val line = try {
        String(request, 0, firstLineEnd, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        return null
    }

    val parts = line.split(' ')
    if (parts.isEmpty()) return null

    val method = parts.getOrNull(0) ?: return null
    if (!method.equals("GET", ignoreCase = true)) return null

    return parts.getOrNull(1)
}
