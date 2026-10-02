// Copyright (c) 2025-2026 Abdulkadir Özyurt — Kotlin port of irlserver/srtla_send v4.1.0 (MIT)
//
// End-to-end scenarios over real loopback UDP: the full SrtlaSender event loop
// against MockSrtlaReceiver, plus the control, metrics and priority sidecars.
// Upstream covers the same ground with netns tests (tests/netns_*.rs,
// tests/signal_shutdown.rs, tests/startup_bind_ordering.rs); those need Linux
// network namespaces, so this suite exercises the shell on loopback instead.
package dev.abdulkadirozyurt.srtla.e2e

import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.config.spawnTcpControlServer
import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import dev.abdulkadirozyurt.srtla.json.Json
import dev.abdulkadirozyurt.srtla.json.jsonObject
import dev.abdulkadirozyurt.srtla.net.spawnPriorityListener
import dev.abdulkadirozyurt.srtla.sender.SrtlaSender
import dev.abdulkadirozyurt.srtla.telemetry.SharedStats
import dev.abdulkadirozyurt.srtla.telemetry.SubscriptionHub
import dev.abdulkadirozyurt.srtla.telemetry.spawnMetricsServer
import dev.abdulkadirozyurt.srtla.testkit.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

private fun e2eFreeTcpPort(): Int = ServerSocket(0).use { it.localPort }

private fun e2eFreeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

/** Write an IPs file for the given uplink addresses. */
private fun e2eIpsFile(vararg lines: String): String {
    val f = Files.createTempFile("srtla-e2e-ips", ".txt")
    Files.write(f, lines.joinToString("\n").toByteArray())
    f.toFile().deleteOnExit()
    return f.toString()
}

/** An SRT data packet with sequence [seq] and a 1316-byte payload. */
private fun e2eSrtData(seq: Int): ByteArray {
    val pkt = ByteArray(16 + 1316)
    pkt[0] = ((seq ushr 24) and 0x7f).toByte()
    pkt[1] = (seq ushr 16).toByte()
    pkt[2] = (seq ushr 8).toByte()
    pkt[3] = seq.toByte()
    return pkt
}

/** A running sender against a mock receiver, torn down by [close]. */
private class E2EHarness(
    uplinks: List<String> = listOf("127.0.0.1", "127.0.0.2"),
    mode: SchedulingMode = SchedulingMode.ENHANCED,
) : AutoCloseable {
    val receiver = MockSrtlaReceiver().also { it.start() }
    val config = DynamicConfig(mode = mode)
    val stats = SharedStats()
    val hub = SubscriptionHub()
    val cw = CriticalWindow()
    val sender = SrtlaSender(0, "127.0.0.1", receiver.port, e2eIpsFile(*uplinks.toTypedArray()), config, stats, cw, hub)
    @Volatile var failure: Throwable? = null
    private val thread = Thread({
        try {
            sender.run()
        } catch (t: Throwable) {
            failure = t
        }
    }, "e2e-sender").also {
        it.isDaemon = true
        it.start()
    }
    val client = DatagramSocket(InetSocketAddress("127.0.0.1", 0)).also { it.soTimeout = 300 }

    fun awaitStarted(): Boolean {
        val deadline = System.currentTimeMillis() + 5000
        while (!sender.started && failure == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return sender.started
    }

    fun sendFromClient(data: ByteArray) {
        client.send(DatagramPacket(data, data.size, InetSocketAddress("127.0.0.1", sender.boundLocalPort)))
    }

    /** Datagrams the sender relayed to the client within [windowMs]. */
    fun drainClient(windowMs: Long): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val deadline = System.currentTimeMillis() + windowMs
        val buf = ByteArray(2048)
        while (System.currentTimeMillis() < deadline) {
            val p = DatagramPacket(buf, buf.size)
            try {
                client.receive(p)
                out.add(buf.copyOf(p.length))
            } catch (_: java.net.SocketTimeoutException) {
            }
        }
        return out
    }

    fun join(timeoutMs: Long): Boolean {
        thread.join(timeoutMs)
        return !thread.isAlive
    }

    override fun close() {
        sender.stop()
        thread.join(3000)
        client.close()
        receiver.stop()
    }
}

/** 127.0.0.2 is not bindable on every OS (macOS); fall back to one uplink there. */
private fun e2eSecondLoopbackUsable(): Boolean = try {
    DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0)).close()
    true
} catch (_: Exception) {
    false
}

fun registerE2ETests() {
    val uplinks = if (e2eSecondLoopbackUsable()) listOf("127.0.0.1", "127.0.0.2") else listOf("127.0.0.1")

    suite("E2E: registration and forwarding") {
        test("listener binds and every uplink registers via probe, REG1, REG2, REG3") {
            E2EHarness(uplinks).use { h ->
                assertTrue(h.awaitStarted(), "sender did not start: ${h.failure}")
                assertTrue(h.sender.boundLocalPort > 0, "local SRT listener must be bound")
                assertTrue(h.receiver.pollUntilRegistered(uplinks.size), "registered=${h.receiver.registeredCount()}")
                assertTrue(h.receiver.regNgpSent.get() >= 1, "the RTT probe must be answered with REG_NGP")
                assertEquals(1, h.receiver.reg1Received.get(), "exactly one REG1 starts the group")
            }
        }

        test("SRT data reaches the receiver and stats report active links") {
            E2EHarness(uplinks).use { h ->
                assertTrue(h.awaitStarted())
                assertTrue(h.receiver.pollUntilRegistered(uplinks.size))
                for (seq in 1..200) {
                    h.sendFromClient(e2eSrtData(seq))
                    if (seq % 20 == 0) Thread.sleep(5)
                }
                assertTrue(h.receiver.pollUntil { h.receiver.distinctSeqCount() >= 200 }, "distinct=${h.receiver.distinctSeqCount()}")
                assertTrue(h.receiver.pollUntil(4000) { h.stats.get().activeLinks == uplinks.size }, "active=${h.stats.get().activeLinks}")
                assertEquals(uplinks.size, h.stats.get().totalLinks)
            }
        }

        test("an SRT ACK sent down every uplink reaches the client once") {
            E2EHarness(uplinks).use { h ->
                assertTrue(h.awaitStarted())
                assertTrue(h.receiver.pollUntilRegistered(uplinks.size))
                h.sendFromClient(e2eSrtData(7)) // teaches the sender the client address
                assertTrue(h.receiver.pollUntil { h.receiver.distinctSeqCount() >= 1 })
                h.drainClient(200)
                h.receiver.sendSrtAck(7, timestamp = 1234)
                val acks = h.drainClient(600).filter { getType(it) == 0x8002 }
                assertEquals(1, acks.size, "copies of one ACK must be relayed once, got ${acks.size}")
            }
        }

        test("stop() ends the event loop promptly") {
            val h = E2EHarness(uplinks)
            assertTrue(h.awaitStarted())
            h.sender.stop()
            assertTrue(h.join(3000), "run() must return after stop()")
            assertNull(h.failure, "a clean stop is not a failure")
            h.close()
        }

        test("unreadable IPs file fails startup after the listener was bound") {
            val receiver = MockSrtlaReceiver().also { it.start() }
            val port = e2eFreeUdpPort()
            val sender = SrtlaSender(port, "127.0.0.1", receiver.port, "/nonexistent/srtla-ips.txt", DynamicConfig())
            val err = assertFailsWith<java.io.IOException> { sender.run() }
            assertTrue(err.message!!.contains("IPs file"), "message: ${err.message}")
            assertEquals(port, sender.boundLocalPort, "the listener binds before the IPs file is read")
            receiver.stop()
        }
    }

    suite("E2E: sidecars") {
        test("JSON-RPC over the TCP control socket: status, set_mode and stats subscription") {
            E2EHarness(uplinks).use { h ->
                assertTrue(h.awaitStarted())
                val port = e2eFreeTcpPort()
                spawnTcpControlServer(port, h.config, h.stats, h.cw, h.hub)
                var sock: Socket? = null
                val deadline = System.currentTimeMillis() + 3000
                while (sock == null && System.currentTimeMillis() < deadline) {
                    sock = try { Socket("127.0.0.1", port) } catch (_: Exception) { Thread.sleep(20); null }
                }
                assertNotNull(sock, "control socket must accept")
                sock!!.use { s ->
                    s.soTimeout = 4000
                    val out = PrintWriter(s.getOutputStream(), true)
                    val inp = BufferedReader(InputStreamReader(s.getInputStream()))
                    out.println("""{"jsonrpc":"2.0","id":1,"method":"set_mode","params":{"mode":"classic"}}""")
                    val r1 = Json.parse(inp.readLine()).jsonObject()!!
                    assertEquals("classic", r1["result"].jsonObject()!!["mode"])
                    assertEquals(SchedulingMode.CLASSIC, h.config.mode())

                    out.println("""{"jsonrpc":"2.0","id":2,"method":"subscribe","params":{"topic":"stats"}}""")
                    val r2 = Json.parse(inp.readLine()).jsonObject()!!
                    val subId = r2["result"].jsonObject()!!["subscription_id"] as String
                    val push = Json.parse(inp.readLine()).jsonObject()!!
                    assertEquals("stats.update", push["method"])
                    assertEquals(subId, push["params"].jsonObject()!!["subscription_id"])
                }
            }
        }

        test("Prometheus /metrics answers 200 and unknown paths 404") {
            val stats = SharedStats()
            val port = e2eFreeTcpPort()
            spawnMetricsServer(InetSocketAddress("127.0.0.1", port), stats, DynamicConfig(), CriticalWindow())
            fun get(path: String): String {
                val deadline = System.currentTimeMillis() + 3000
                while (true) {
                    try {
                        Socket("127.0.0.1", port).use { s ->
                            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
                        }
                    } catch (e: java.net.ConnectException) {
                        if (System.currentTimeMillis() > deadline) throw e
                        Thread.sleep(20)
                    }
                }
            }
            val ok = get("/metrics")
            assertTrue(ok.startsWith("HTTP/1.1 200 OK"), ok.take(40))
            assertTrue(ok.contains("srtla_send_active_links 0"))
            assertTrue(get("/nope").startsWith("HTTP/1.1 404"))
        }

        test("priority sidecar extends the critical window and counts malformed datagrams") {
            val cw = CriticalWindow()
            val port = e2eFreeUdpPort()
            spawnPriorityListener(InetSocketAddress("127.0.0.1", port), cw, null)
            DatagramSocket().use { s ->
                val to = InetSocketAddress("127.0.0.1", port)
                val deadline = System.currentTimeMillis() + 3000
                while (cw.windowsReceived() == 0L && System.currentTimeMillis() < deadline) {
                    s.send(DatagramPacket(byteArrayOf(0xc1.toByte(), 0, 0, 0x27, 0x10), 5, to)) // 10 s
                    Thread.sleep(50)
                }
                s.send(DatagramPacket(byteArrayOf(0x00, 1, 2), 3, to))
                val d2 = System.currentTimeMillis() + 2000
                while (cw.malformedDatagrams() == 0L && System.currentTimeMillis() < d2) Thread.sleep(20)
            }
            assertTrue(cw.windowsReceived() >= 1)
            assertTrue(cw.isCriticalNow(dev.abdulkadirozyurt.srtla.core.nowMs()))
            assertEquals(1L, cw.malformedDatagrams())
        }
    }
}

private fun getType(b: ByteArray): Int =
    if (b.size < 2) -1 else ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
