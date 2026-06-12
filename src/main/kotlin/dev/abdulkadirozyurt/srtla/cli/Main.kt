// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/main.rs
//
// Main — CLI entry point for srtla-sender-kotlin.
//
// Arg parsing is done by hand (no clap equivalent available without external deps).
// Positional args: SRT_LISTEN_PORT SRTLA_HOST SRTLA_PORT BIND_IPS_FILE
// Options:
//   --mode classic|enhanced|rtt-threshold|edpf  (default: enhanced)
//   --no-quality                                 (disable quality scoring)
//   --exploration                                (enable exploration, enhanced only)
//   --rtt-delta-ms N                             (default: 30)
//   --control-port N                             (TCP control server port; JVM replacement for
//                                                 Rust's --control-socket Unix domain socket)
//   -v / --version                               (print version and exit)
//
// JVM DEVIATIONS:
//   1. --control-socket (Unix domain socket) → --control-port N (localhost TCP).
//      JDK 11 lacks UnixDomainSocketAddress (added JDK 16). Protocol identical.
//   2. SIGHUP reload → WatchService on IP file + `reload` control command.
//   3. RUST_LOG → SRTLA_LOG for log level control.
//   4. Tokio async runtime → blocking JVM threads (documented in SrtlaSender).
//
// Graceful shutdown: SIGINT (Ctrl-C) JVM shutdown hook calls sender.stop().
package dev.abdulkadirozyurt.srtla.cli

import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.config.spawnConfigListener
import dev.abdulkadirozyurt.srtla.sender.Housekeeping
import dev.abdulkadirozyurt.srtla.sender.SrtlaSender
import dev.abdulkadirozyurt.srtla.sender.selection.SchedulingMode
import dev.abdulkadirozyurt.srtla.stats.SharedStats
import java.util.logging.Logger
import kotlin.system.exitProcess

private val log: Logger = Logger.getLogger("srtla.main")

// Version string (mirrors Rust CARGO_PKG_VERSION + GIT_HASH pattern)
private const val VERSION = "0.1.0-kotlin"
private const val APP_NAME = "srtla_send_kotlin"

fun main(args: Array<String>) {
    // Configure logging first (reads SRTLA_LOG env)
    configureLogging()

    // ── Arg parsing ───────────────────────────────────────────────────────────
    val parsed = parseArgs(args) ?: exitProcess(1)

    if (parsed.printVersion) {
        println("$VERSION [$APP_NAME]")
        return
    }

    val localSrtPort = parsed.localSrtPort!!
    val receiverHost = parsed.receiverHost!!
    val receiverPort = parsed.receiverPort!!
    val ipsFile      = parsed.ipsFile!!

    // ── Load IP list ──────────────────────────────────────────────────────────
    val initialIps = loadIpFile(ipsFile)
    if (initialIps.isEmpty()) {
        log.severe("No valid IPs found in $ipsFile — exiting")
        exitProcess(1)
    }
    log.info("Loaded ${initialIps.size} IPs from $ipsFile")

    // ── Build DynamicConfig from CLI args ─────────────────────────────────────
    // Mirrors Rust `DynamicConfig::from_cli` in src/main.rs
    val config = DynamicConfig.fromCli(
        mode        = parsed.mode,
        noQuality   = parsed.noQuality,
        exploration = parsed.exploration,
        rttDeltaMs  = parsed.rttDeltaMs,
    )
    log.info("Mode: ${parsed.mode.name.lowercase()}, quality=${!parsed.noQuality}, " +
        "exploration=${parsed.exploration}, rtt-delta=${parsed.rttDeltaMs}ms")

    // ── Shared stats ──────────────────────────────────────────────────────────
    val stats = SharedStats()

    // ── Build sender ──────────────────────────────────────────────────────────
    // SrtlaSender takes ConfigSnapshot at construction; Faz D wires DynamicConfig
    // so the snapshot is refreshed each housekeeping tick via Housekeeping.tick().
    val sender = SrtlaSender(
        localSrtPort = localSrtPort,
        receiverHost = receiverHost,
        receiverPort = receiverPort,
        sourceIps    = initialIps,
        config       = config.snapshot(),
    )

    // ── IP reload callback ─────────────────────────────────────────────────────
    // Called on WatchService event or `reload` control command.
    // JVM DEVIATION: Rust uses SIGHUP; JVM uses WatchService + control command.
    val reloadIpList: () -> Unit = {
        val newIps = loadIpFile(ipsFile)
        log.info("IP reload: ${newIps.size} IPs from $ipsFile")
        // Note: live add/remove requires sender to support dynamic connections.
        // For Faz D we log the change; Faz E will wire live uplink add/remove.
        // This is acceptable because the sender reconnects timed-out links automatically.
        log.info("  IPs: ${newIps.map { it.hostAddress }}")
    }

    // ── Control channels ──────────────────────────────────────────────────────
    // Mirrors Rust `spawn_config_listener` in src/main.rs.
    // JVM DEVIATION: --control-port TCP instead of --control-socket Unix socket.
    spawnConfigListener(
        config        = config,
        controlPort   = parsed.controlPort,
        statsProvider = { stats.toJson() },
        reloadHandler = reloadIpList,
    )

    // ── IP file watcher (SIGHUP equivalent) ───────────────────────────────────
    spawnIpFileWatcher(ipsFile, reloadIpList)

    // ── Graceful shutdown hook (SIGINT / Ctrl-C) ──────────────────────────────
    // Mirrors Rust tokio signal handling in src/main.rs.
    Runtime.getRuntime().addShutdownHook(Thread({
        log.info("Shutdown signal received — stopping sender")
        sender.stop()
    }, "srtla-shutdown"))

    // ── Housekeeping extension thread ─────────────────────────────────────────
    // Augments SrtlaSender's internal housekeeping with stats updates and
    // 30s status logging (DynamicConfig-aware).
    var lastStatusLogMs = System.currentTimeMillis()
    val hkThread = Thread({
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(dev.abdulkadirozyurt.srtla.sender.HOUSEKEEPING_INTERVAL_MS)
                lastStatusLogMs = Housekeeping.tick(sender, config, stats, lastStatusLogMs)
            } catch (_: InterruptedException) { break }
            catch (e: Exception) { log.warning("housekeeping tick error: ${e.message}") }
        }
    }, "srtla-hk-ext")
    hkThread.isDaemon = true
    hkThread.start()

    // ── Start sender ──────────────────────────────────────────────────────────
    log.info("Starting srtla sender: [$localSrtPort] → $receiverHost:$receiverPort")
    sender.start()

    // Block main thread until sender stops (via stop() from shutdown hook or error)
    // Poll running state — sender.stop() sets running=false.
    try {
        while (true) {
            Thread.sleep(500)
            // If sender's internal running flag is false (stop() was called), exit.
            // We detect this via connectionCount still being valid; actual termination
            // happens when the JVM exits via exitProcess in the shutdown hook.
        }
    } catch (_: InterruptedException) {}
}

// ── Arg parsing ───────────────────────────────────────────────────────────────

/**
 * Parsed CLI arguments.
 * Mirrors Rust `struct Cli` (clap) in src/main.rs.
 */
data class ParsedArgs(
    val printVersion:  Boolean       = false,
    val localSrtPort:  Int?          = null,
    val receiverHost:  String?       = null,
    val receiverPort:  Int?          = null,
    val ipsFile:       String?       = null,
    val mode:          SchedulingMode = SchedulingMode.ENHANCED,
    val noQuality:     Boolean       = false,
    val exploration:   Boolean       = false,
    val rttDeltaMs:    Int           = 30,
    val controlPort:   Int?          = null,
)

/**
 * Parse command-line arguments by hand (no clap/JCommander — zero deps).
 * Returns null on error (help or parse failure).
 *
 * Mirrors Rust clap definitions in src/main.rs::Cli.
 */
fun parseArgs(args: Array<String>): ParsedArgs? {
    var printVersion = false
    var mode = SchedulingMode.ENHANCED
    var noQuality = false
    var exploration = false
    var rttDeltaMs = 30
    var controlPort: Int? = null
    val positional = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "-v", "--version" -> printVersion = true
            "--no-quality"    -> noQuality = true
            "--exploration"   -> exploration = true
            "-h", "--help"    -> { printUsage(); return null }
            "--mode" -> {
                i++
                if (i >= args.size) { System.err.println("--mode requires a value"); return null }
                mode = when (args[i].lowercase()) {
                    "classic"       -> SchedulingMode.CLASSIC
                    "enhanced"      -> SchedulingMode.ENHANCED
                    "rtt-threshold" -> SchedulingMode.RTT_THRESHOLD
                    "edpf"          -> SchedulingMode.EDPF
                    else -> {
                        System.err.println("Unknown mode '${args[i]}': use classic, enhanced, rtt-threshold, or edpf")
                        return null
                    }
                }
            }
            "--rtt-delta-ms" -> {
                i++
                if (i >= args.size) { System.err.println("--rtt-delta-ms requires a value"); return null }
                rttDeltaMs = args[i].toIntOrNull()?.also {
                    if (it < 0) { System.err.println("--rtt-delta-ms must be >= 0"); return null }
                } ?: run { System.err.println("--rtt-delta-ms: invalid number '${args[i]}'"); return null }
            }
            "--control-port" -> {
                // JVM DEVIATION: Rust uses --control-socket (Unix socket path).
                // JDK 11 lacks UnixDomainSocketAddress; we use localhost TCP instead.
                i++
                if (i >= args.size) { System.err.println("--control-port requires a port number"); return null }
                controlPort = args[i].toIntOrNull()?.also {
                    if (it !in 1..65535) { System.err.println("--control-port must be 1–65535"); return null }
                } ?: run { System.err.println("--control-port: invalid port '${args[i]}'"); return null }
            }
            else -> {
                if (arg.startsWith("--")) {
                    System.err.println("Unknown option: $arg")
                    printUsage()
                    return null
                }
                positional += arg
            }
        }
        i++
    }

    if (printVersion) return ParsedArgs(printVersion = true)

    if (positional.size < 4) {
        System.err.println("Error: expected 4 positional args, got ${positional.size}")
        printUsage()
        return null
    }

    val localSrtPort = positional[0].toIntOrNull()?.also {
        if (it !in 1..65535) { System.err.println("SRT_LISTEN_PORT must be 1–65535"); return null }
    } ?: run { System.err.println("SRT_LISTEN_PORT: invalid port '${positional[0]}'"); return null }

    val receiverPort = positional[2].toIntOrNull()?.also {
        if (it !in 1..65535) { System.err.println("SRTLA_PORT must be 1–65535"); return null }
    } ?: run { System.err.println("SRTLA_PORT: invalid port '${positional[2]}'"); return null }

    return ParsedArgs(
        printVersion = false,
        localSrtPort = localSrtPort,
        receiverHost = positional[1],
        receiverPort = receiverPort,
        ipsFile      = positional[3],
        mode         = mode,
        noQuality    = noQuality,
        exploration  = exploration,
        rttDeltaMs   = rttDeltaMs,
        controlPort  = controlPort,
    )
}

private fun printUsage() {
    println("""
Usage: $APP_NAME [OPTIONS] SRT_LISTEN_PORT SRTLA_HOST SRTLA_PORT BIND_IPS_FILE

  SRT_LISTEN_PORT   Local UDP port to receive SRT packets
  SRTLA_HOST        Receiver host (srtla_rec or SRT listener)
  SRTLA_PORT        Receiver UDP port
  BIND_IPS_FILE     File with newline-separated local source IPs for uplinks

Options:
  --mode <MODE>         Scheduling mode: classic, enhanced (default), rtt-threshold, edpf
  --no-quality          Disable quality scoring (enhanced/rtt-threshold only)
  --exploration         Enable connection exploration (enhanced only)
  --rtt-delta-ms <N>    RTT delta threshold ms for rtt-threshold mode (default: 30)
  --control-port <N>    Localhost TCP control server port (JVM alternative to Unix socket)
                        Protocol: newline-delimited commands (mode, quality, explore,
                        rtt-delta, status, stats, reload)
  -v, --version         Print version and exit
  -h, --help            Show this help

JVM deviations from Rust reference:
  --control-socket → --control-port  (JDK 11 lacks UnixDomainSocketAddress)
  SIGHUP reload    → WatchService on IP file + 'reload' control command
  RUST_LOG         → SRTLA_LOG env var (debug|info|warn|error|off)
""".trimIndent())
}
