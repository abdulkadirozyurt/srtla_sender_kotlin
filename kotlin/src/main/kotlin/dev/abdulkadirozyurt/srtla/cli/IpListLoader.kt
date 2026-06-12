// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/main.rs (IP file loading) + src/sender/mod.rs (live add/remove)
//
// IpListLoader — reads a newline-separated file of source IPs and manages
// live add/remove of uplinks via WatchService on file change.
//
// JVM DEVIATION — Reload trigger:
//   Rust registers a SIGHUP handler (Unix-only) to re-read the IP file.
//   JVM has no portable SIGHUP. Instead we use:
//     a) java.nio.file.WatchService on the file's parent directory (cross-platform).
//     b) `reload` command via stdin / TCP control channel (see DynamicConfig).
//   Both paths call the same reloadIpList() callback provided by Main.
//   On Linux the WatchService backend uses inotify (near-instant).
//   On macOS it uses polling (≤10s latency). On Windows it uses ReadDirectoryChanges.
package dev.abdulkadirozyurt.srtla.cli

import java.net.InetAddress
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds.*
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.ip-loader")

/**
 * Read IP addresses from [filePath], one per line.
 * Blank lines and lines starting with '#' are ignored.
 * Returns the parsed list (may be empty on error — logged).
 *
 * Mirrors Rust IP file reading in src/main.rs.
 */
fun loadIpFile(filePath: String): List<InetAddress> {
    return try {
        Files.readAllLines(Paths.get(filePath))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .mapNotNull { line ->
                try {
                    InetAddress.getByName(line)
                } catch (e: Exception) {
                    log.warning("invalid IP '$line' in $filePath: ${e.message}")
                    null
                }
            }
    } catch (e: Exception) {
        log.warning("failed to read IP file $filePath: ${e.message}")
        emptyList()
    }
}

/**
 * Spawn a daemon thread that watches [filePath] for modifications and calls
 * [onReload] whenever the file changes.
 *
 * JVM DEVIATION — SIGHUP replacement:
 *   Rust registers a SIGHUP handler to trigger re-reading the IP file.
 *   JVM uses WatchService (inotify on Linux, polling on macOS, ReadDirectoryChanges on Windows).
 *   Latency: Linux ~instant, macOS ≤10s, Windows ~instant.
 *   The `reload` command via control channel provides an immediate alternative on all platforms.
 *
 * Thread name: "srtla-ip-watcher".
 */
fun spawnIpFileWatcher(filePath: String, onReload: () -> Unit) {
    val path = Paths.get(filePath).toAbsolutePath()
    val dir = path.parent ?: run {
        log.warning("cannot watch IP file $filePath — no parent directory")
        return
    }
    val fileName = path.fileName

    val thread = Thread({
        try {
            val watcher = FileSystems.getDefault().newWatchService()
            dir.register(watcher, ENTRY_MODIFY, ENTRY_CREATE)
            log.info("watching $filePath for changes (SIGHUP equivalent via WatchService)")

            while (!Thread.currentThread().isInterrupted) {
                val key = try {
                    watcher.take()  // blocks until event
                } catch (_: InterruptedException) { break }

                val triggered = key.pollEvents().any { event ->
                    val changed = event.context() as? java.nio.file.Path
                    changed != null && changed == fileName
                }

                if (triggered) {
                    log.info("IP file $filePath changed — reloading")
                    try { onReload() } catch (e: Exception) {
                        log.warning("reload callback failed: ${e.message}")
                    }
                }

                if (!key.reset()) break
            }
        } catch (e: Exception) {
            log.warning("IP file watcher failed for $filePath: ${e.message}")
        }
    }, "srtla-ip-watcher")
    thread.isDaemon = true
    thread.start()
}
