// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/main.rs
//
// Thin CLI over the library: parse arguments, start the optional sidecars, and
// run the sender. JVM deviations:
//   - SIGHUP (IP reload) via sun.misc.Signal when the runtime offers it; else a
//     WatchService on the IP file triggers the same reload.
//   - SIGTERM/SIGINT: a shutdown hook stops the event loop so it exits cleanly.
//   - Log level from SRTLA_LOG (upstream: RUST_LOG).
package dev.abdulkadirozyurt.srtla.cli

import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.config.TomlConfig
import dev.abdulkadirozyurt.srtla.config.TomlConfigException
import dev.abdulkadirozyurt.srtla.config.spawnStdinListener
import dev.abdulkadirozyurt.srtla.config.spawnTcpControlServer
import dev.abdulkadirozyurt.srtla.config.spawnUnixControlServer
import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import dev.abdulkadirozyurt.srtla.net.SourceIpBinder
import dev.abdulkadirozyurt.srtla.net.spawnPriorityListener
import dev.abdulkadirozyurt.srtla.sender.SrtlaSender
import dev.abdulkadirozyurt.srtla.telemetry.SharedStats
import dev.abdulkadirozyurt.srtla.telemetry.SubscriptionHub
import dev.abdulkadirozyurt.srtla.telemetry.spawnMetricsServer
import java.net.InetSocketAddress
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.util.logging.Logger
import kotlin.system.exitProcess

private val log: Logger = Logger.getLogger("srtla.main")

fun main(args: Array<String>) {
    configureLogging()
    var parsed = try {
        parseArgs(args)
    } catch (e: ArgsException) {
        System.err.println("error: ${e.message}\n")
        System.err.println(usage())
        exitProcess(2)
    }
    if (parsed.printHelp) {
        println(usage())
        return
    }
    if (parsed.printVersion) {
        println(versionLine())
        return
    }
    parsed.configFile?.let { path ->
        val file = try {
            TomlConfig.load(Paths.get(path))
        } catch (e: TomlConfigException) {
            System.err.println("Error: ${e.message}")
            exitProcess(1)
        }
        log.info("loaded config from $path")
        parsed = parsed.applyConfigFile(file)
    }

    val config = DynamicConfig.fromCli(
        parsed.mode,
        parsed.noQuality,
        parsed.noStallDeselect,
        parsed.stallMinInFlight,
        parsed.stallAckStaleMs,
        parsed.connTimeoutMs,
        parsed.noRehome,
    )
    val stats = SharedStats()
    val hub = SubscriptionHub()
    val criticalWindow = CriticalWindow()

    parsed.priorityBind?.let {
        warnIfNotLoopback("priority sidecar (--priority-bind)", it)
        spawnPriorityListener(it, criticalWindow, hub)
    }
    parsed.metricsBind?.let {
        warnIfNotLoopback("metrics endpoint (--metrics-bind)", it)
        spawnMetricsServer(it, stats, config, criticalWindow)
    }
    spawnStdinListener(config, stats, criticalWindow)
    parsed.controlSocket?.let { spawnUnixControlServer(it, config, stats, criticalWindow, hub) }
    parsed.controlPort?.let { spawnTcpControlServer(it, config, stats, criticalWindow, hub) }

    val ipsFile = parsed.ipsFile!!
    val sender = SrtlaSender(
        localSrtPort = parsed.localSrtPort!!,
        receiverHost = parsed.receiverHost!!,
        receiverPort = parsed.receiverPort!!,
        ipsFile = ipsFile,
        config = config,
        stats = stats,
        criticalWindow = criticalWindow,
        hub = hub,
        binder = SourceIpBinder,
    )

    if (!installSighup { sender.requestIpReload() }) {
        spawnIpFileWatcher(ipsFile) { sender.requestIpReload() }
    }
    val mainThread = Thread.currentThread()
    Runtime.getRuntime().addShutdownHook(
        Thread({
            if (sender.isRunning()) {
                log.info("received shutdown signal - shutting down")
                sender.stop()
                mainThread.join(3000)
            }
        }, "srtla-shutdown"),
    )

    try {
        sender.run()
    } catch (e: Exception) {
        System.err.println("Error: srtla_send failed: ${e.message}")
        exitProcess(1)
    }
}

/**
 * Warn when a sidecar binds a non-loopback address: they are unauthenticated
 * same-device IPC. Warn rather than refuse, so a trusted network still works.
 */
private fun warnIfNotLoopback(what: String, addr: InetSocketAddress) {
    if (!addr.address.isLoopbackAddress) {
        log.warning("$what bound to a non-loopback address $addr; it is unauthenticated and should normally bind 127.0.0.1 / ::1")
    }
}

/**
 * Install a SIGHUP handler through sun.misc.Signal (reflection: not on every
 * runtime, absent on Windows and Android). Returns false when unavailable.
 */
private fun installSighup(onHup: () -> Unit): Boolean = try {
    val signalCls = Class.forName("sun.misc.Signal")
    val handlerCls = Class.forName("sun.misc.SignalHandler")
    val signal = signalCls.getConstructor(String::class.java).newInstance("HUP")
    val handler = java.lang.reflect.Proxy.newProxyInstance(handlerCls.classLoader, arrayOf(handlerCls)) { _, m, _ ->
        if (m.name == "handle") {
            log.info("received SIGHUP - evaluating uplink IP reload")
            onHup()
        }
        null
    }
    signalCls.getMethod("handle", signalCls, handlerCls).invoke(null, signal, handler)
    true
} catch (_: Throwable) {
    false
}

/**
 * Fallback reload trigger where SIGHUP is unavailable: watch the IP file's
 * directory and request a reload when the file changes.
 */
private fun spawnIpFileWatcher(filePath: String, onChange: () -> Unit) {
    val path = Paths.get(filePath).toAbsolutePath()
    val dir = path.parent ?: return
    val name = path.fileName
    val t = Thread({
        try {
            val watcher = FileSystems.getDefault().newWatchService()
            dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)
            log.info("SIGHUP unavailable; watching $filePath for changes to trigger IP reload")
            while (true) {
                val key = watcher.take()
                if (key.pollEvents().any { it.context() == name }) onChange()
                if (!key.reset()) break
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            log.warning("IP file watcher failed for $filePath: ${e.message}")
        }
    }, "srtla-ip-watcher")
    t.isDaemon = true
    t.start()
}
