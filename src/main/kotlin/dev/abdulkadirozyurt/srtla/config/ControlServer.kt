// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/control_socket.rs, src/config.rs (spawn_stdin_listener)
//
// JVM deviations:
//   - Blocking threads instead of tokio tasks: per connection, one reader thread
//     dispatches requests and one pusher thread drains subscription events.
//   - Unix domain sockets need JDK 16+ and are opened via reflection, since the
//     build targets JDK 11 (and Android has no UNIX ServerSocketChannel).
//   - --control-port: a loopback TCP control socket with the same protocol, for
//     runtimes without Unix sockets.
package dev.abdulkadirozyurt.srtla.config

import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import dev.abdulkadirozyurt.srtla.telemetry.QueueSubscriptionSink
import dev.abdulkadirozyurt.srtla.telemetry.SharedStats
import dev.abdulkadirozyurt.srtla.telemetry.SubscriptionHub
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.control")

private fun daemon(name: String, body: () -> Unit): Thread =
    Thread(body, name).also {
        it.isDaemon = true
        it.start()
    }

/**
 * stdin reader: each line is a JSON-RPC request; replies go to stdout so
 * scripts can pipe. No subscriptions (no push channel).
 */
fun spawnStdinListener(config: DynamicConfig, stats: SharedStats, cw: CriticalWindow): Thread =
    daemon("srtla-ctrl-stdin") {
        try {
            val reader = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                dispatch(config, stats, cw, line)?.let { println(it.toJson()) }
            }
        } catch (e: Exception) {
            log.fine { "stdin listener exited: ${e.message}" }
        }
    }

/** JVM extension: loopback TCP control socket (127.0.0.1:[port]) with subscriptions. */
fun spawnTcpControlServer(
    port: Int,
    config: DynamicConfig,
    stats: SharedStats,
    cw: CriticalWindow,
    hub: SubscriptionHub,
): Thread = daemon("srtla-ctrl-tcp") {
    val server = try {
        ServerSocket(port, 50, InetAddress.getLoopbackAddress())
    } catch (e: Exception) {
        log.warning("control TCP server failed to bind 127.0.0.1:$port: ${e.message}")
        return@daemon
    }
    log.info("tcp control socket listening on 127.0.0.1:${server.localPort}")
    while (true) {
        val s = try {
            server.accept()
        } catch (e: Exception) {
            log.fine { "accept failed: ${e.message}" }
            if (server.isClosed) break else continue
        }
        daemon("srtla-ctrl-conn") { serveConnection(s.getInputStream(), s.getOutputStream(), s, config, stats, cw, hub) }
    }
}

/**
 * Unix domain control socket at [path] (JDK 16+). Returns null with a warning
 * when the runtime has no Unix-domain support.
 */
fun spawnUnixControlServer(
    path: String,
    config: DynamicConfig,
    stats: SharedStats,
    cw: CriticalWindow,
    hub: SubscriptionHub,
): Thread? {
    val server: ServerSocketChannel = try {
        val addrCls = Class.forName("java.net.UnixDomainSocketAddress")
        val addr = addrCls.getMethod("of", String::class.java).invoke(null, path) as SocketAddress
        val unix = java.net.StandardProtocolFamily.valueOf("UNIX")
        // Remove a stale socket file from a previous run.
        Files.deleteIfExists(Paths.get(path))
        val ch = ServerSocketChannel::class.java.getMethod("open", java.net.ProtocolFamily::class.java)
            .invoke(null, unix) as ServerSocketChannel
        ch.bind(addr)
        ch
    } catch (e: ClassNotFoundException) {
        log.warning("--control-socket needs JDK 16+; use --control-port")
        return null
    } catch (e: IllegalArgumentException) {
        log.warning("--control-socket needs JDK 16+; use --control-port")
        return null
    } catch (e: Exception) {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        log.warning("control socket listener exited: ${cause.message}")
        return null
    }
    log.info("unix control socket listening at $path")
    return daemon("srtla-ctrl-unix") {
        while (true) {
            val ch: SocketChannel = try {
                server.accept()
            } catch (e: Exception) {
                log.fine { "accept failed: ${e.message}" }
                if (!server.isOpen) break else continue
            }
            daemon("srtla-ctrl-conn") {
                serveConnection(Channels.newInputStream(ch), Channels.newOutputStream(ch), ch, config, stats, cw, hub)
            }
        }
    }
}

/**
 * Serve one control connection: requests are read line by line and answered,
 * subscription pushes are written by a pusher thread. Writes are serialized.
 * On EOF or error every subscription this connection owns is removed.
 */
private fun serveConnection(
    input: InputStream,
    output: OutputStream,
    closer: Closeable,
    config: DynamicConfig,
    stats: SharedStats,
    cw: CriticalWindow,
    hub: SubscriptionHub,
) {
    val sink = QueueSubscriptionSink(128)
    val ownedIds = java.util.Collections.synchronizedList(ArrayList<String>())
    val out = output.buffered()
    val writeLock = Any()

    fun writeLine(s: String): Boolean = try {
        synchronized(writeLock) {
            out.write(s.toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
            out.flush()
        }
        true
    } catch (_: Exception) {
        false
    }

    val pusher = daemon("srtla-ctrl-push") {
        try {
            while (!sink.isClosed) {
                val line = sink.queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (!writeLine(line)) break
            }
        } catch (_: InterruptedException) {
        }
        sink.close()
    }

    try {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        while (!sink.isClosed) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val resp = dispatch(config, stats, cw, trimmed, SubscriptionContext(hub, sink, ownedIds))
            if (resp != null && !writeLine(resp.toJson())) break
        }
    } catch (e: Exception) {
        log.fine { "control connection read failed: ${e.message}" }
    } finally {
        sink.close()
        synchronized(ownedIds) { for (id in ownedIds) hub.unsubscribe(id) }
        pusher.interrupt()
        try {
            closer.close()
        } catch (_: Exception) {
        }
    }
}
