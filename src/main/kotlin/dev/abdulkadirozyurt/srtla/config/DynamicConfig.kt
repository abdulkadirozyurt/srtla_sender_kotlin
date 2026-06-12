// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/config.rs
//
// DynamicConfig — runtime-mutable configuration backed by atomic references.
// Mirrors Rust DynamicConfig (Arc<AtomicU8/AtomicBool/AtomicU32>).
//
// JVM DEVIATION — Control channel:
//   Rust uses a Unix domain socket (--control-socket path).
//   JDK 11 has no UnixDomainSocketAddress (added in JDK 16).
//   We use an optional localhost TCP server on --control-port N instead.
//   The control protocol is identical: line-oriented text commands, same parser.
//   Document this deviation here and in CLI help text.
//
// Thread-safety: All fields are AtomicReference / AtomicBoolean / AtomicInteger.
// snapshot() reads them all with plain Acquire semantics (Kotlin AtomicReference
// uses volatile reads under the hood), safe for any number of concurrent readers.
package dev.abdulkadirozyurt.srtla.config

import dev.abdulkadirozyurt.srtla.sender.selection.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.sender.selection.SchedulingMode
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

// src/config.rs :: DEFAULT_RTT_DELTA_MS = 30
const val DEFAULT_RTT_DELTA_MS: Int = 30

private val log: Logger = Logger.getLogger("srtla.config")

/**
 * Dynamic runtime configuration. Backed by atomics for lock-free hot-path access.
 * Mirrors Rust `struct DynamicConfig` in src/config.rs.
 *
 * Call [snapshot] once per select iteration — avoids multiple atomic reads per packet.
 */
class DynamicConfig(
    initialMode: SchedulingMode = SchedulingMode.ENHANCED,
    qualityEnabled: Boolean = true,
    explorationEnabled: Boolean = false,
    rttDeltaMs: Int = DEFAULT_RTT_DELTA_MS,
) {
    // src/config.rs: Arc<AtomicU8> for mode (0..3 → SchedulingMode)
    private val _mode = AtomicReference(initialMode)
    // src/config.rs: Arc<AtomicBool>
    private val _qualityEnabled = AtomicBoolean(qualityEnabled)
    // src/config.rs: Arc<AtomicBool>
    private val _explorationEnabled = AtomicBoolean(explorationEnabled)
    // src/config.rs: Arc<AtomicU32>  (u16 → Int per conventions)
    private val _rttDeltaMs = AtomicInteger(rttDeltaMs)

    // ── Snapshot ──────────────────────────────────────────────────────────────

    /**
     * Create an immutable snapshot of current config.
     * Call once per scheduling cycle to avoid repeated atomic loads in the hot path.
     * Mirrors Rust `DynamicConfig::snapshot`.
     */
    fun snapshot(): ConfigSnapshot = ConfigSnapshot(
        mode              = _mode.get(),
        qualityEnabled    = _qualityEnabled.get(),
        explorationEnabled = _explorationEnabled.get(),
        rttDeltaMs        = _rttDeltaMs.get(),
    )

    // ── Getters ───────────────────────────────────────────────────────────────

    fun mode(): SchedulingMode = _mode.get()

    // ── Setters ───────────────────────────────────────────────────────────────

    fun setMode(mode: SchedulingMode) { _mode.set(mode) }
    fun setQualityEnabled(v: Boolean) { _qualityEnabled.set(v) }
    fun setExplorationEnabled(v: Boolean) { _explorationEnabled.set(v) }
    fun setRttDeltaMs(v: Int) { _rttDeltaMs.set(v) }

    companion object {
        /**
         * Construct from CLI arguments.
         * Mirrors Rust `DynamicConfig::from_cli`.
         *
         * @param noQuality  If true, quality scoring is disabled (--no-quality flag).
         */
        fun fromCli(
            mode: SchedulingMode = SchedulingMode.ENHANCED,
            noQuality: Boolean = false,
            exploration: Boolean = false,
            rttDeltaMs: Int = DEFAULT_RTT_DELTA_MS,
        ): DynamicConfig = DynamicConfig(
            initialMode       = mode,
            qualityEnabled    = !noQuality,
            explorationEnabled = exploration,
            rttDeltaMs        = rttDeltaMs,
        )
    }
}

// ── Command processing ────────────────────────────────────────────────────────

/**
 * Response from [applyCmd]. Used for TCP control channel replies.
 * Mirrors Rust `enum CmdResponse` in src/config.rs.
 */
sealed class CmdResponse {
    /** No reply needed (command was logged). */
    object None : CmdResponse()
    /** Plain-text reply to send back to the control client. */
    data class Text(val text: String) : CmdResponse()
}

/**
 * Apply one runtime command to [config] and return a [CmdResponse].
 *
 * Commands (mirrors Rust `apply_cmd` in src/config.rs):
 *   mode classic|enhanced|rtt-threshold|edpf
 *   quality on|off
 *   explore on|off
 *   rtt-delta <ms>
 *   status
 *   reload      — handled by caller; passed through as Text("reload") sentinel
 *
 * Response text mirrors Rust log messages so external tooling can parse them.
 */
fun applyCmd(config: DynamicConfig, cmd: String, statsProvider: (() -> String)? = null): CmdResponse {
    val trimmed = cmd.trim()
    if (trimmed.isEmpty()) return CmdResponse.None

    val parts = trimmed.split(Regex("\\s+"))
    if (parts.isEmpty()) return CmdResponse.None

    return when (parts[0]) {
        "mode" -> {
            if (parts.size != 2) {
                log.warning("usage: mode classic|enhanced|rtt-threshold|edpf")
                return CmdResponse.None
            }
            when (parts[1]) {
                "classic"       -> { config.setMode(SchedulingMode.CLASSIC);        log.info("mode: classic");        CmdResponse.Text("mode: classic") }
                "enhanced"      -> { config.setMode(SchedulingMode.ENHANCED);       log.info("mode: enhanced");       CmdResponse.Text("mode: enhanced") }
                "rtt-threshold" -> { config.setMode(SchedulingMode.RTT_THRESHOLD);  log.info("mode: rtt-threshold");  CmdResponse.Text("mode: rtt-threshold") }
                "edpf"          -> { config.setMode(SchedulingMode.EDPF);           log.info("mode: edpf");           CmdResponse.Text("mode: edpf") }
                else -> {
                    log.warning("unknown mode '${parts[1]}': use classic, enhanced, rtt-threshold, or edpf")
                    CmdResponse.None
                }
            }
        }

        "quality" -> {
            if (parts.size != 2) {
                log.warning("usage: quality on|off")
                return CmdResponse.None
            }
            when (parts[1]) {
                "on"  -> { config.setQualityEnabled(true);  log.info("quality: on");  CmdResponse.Text("quality: on") }
                "off" -> { config.setQualityEnabled(false); log.info("quality: off"); CmdResponse.Text("quality: off") }
                else  -> { log.warning("invalid value '${parts[1]}': use on or off"); CmdResponse.None }
            }
        }

        "explore" -> {
            if (parts.size != 2) {
                log.warning("usage: explore on|off")
                return CmdResponse.None
            }
            when (parts[1]) {
                "on"  -> { config.setExplorationEnabled(true);  log.info("explore: on");  CmdResponse.Text("explore: on") }
                "off" -> { config.setExplorationEnabled(false); log.info("explore: off"); CmdResponse.Text("explore: off") }
                else  -> { log.warning("invalid value '${parts[1]}': use on or off"); CmdResponse.None }
            }
        }

        "rtt-delta" -> {
            if (parts.size != 2) {
                log.warning("usage: rtt-delta <ms>")
                return CmdResponse.None
            }
            val delta = parts[1].toLongOrNull()
            if (delta == null || delta < 0 || delta > Int.MAX_VALUE) {
                log.warning("invalid rtt-delta value: ${parts[1]}")
                CmdResponse.None
            } else {
                config.setRttDeltaMs(delta.toInt())
                log.info("rtt-delta: ${delta}ms")
                CmdResponse.Text("rtt-delta: ${delta}ms")
            }
        }

        // src/config.rs: "status" command — print current config
        "status" -> {
            val snap = config.snapshot()
            val sb = StringBuilder()
            sb.appendLine("mode: ${snap.mode.name.lowercase().replace('_', '-')}")
            sb.appendLine("  quality: ${if (snap.qualityEnabled) "on" else "off"}")
            sb.appendLine("  explore: ${if (snap.explorationEnabled) "on" else "off"}")
            sb.append("  rtt-delta: ${snap.rttDeltaMs}ms")
            val text = sb.toString()
            log.info(text)
            CmdResponse.Text(text)
        }

        // "reload" — sentinel, caller handles IP list reload
        "reload" -> CmdResponse.Text("reload")

        // src/config.rs: "stats" command — JSON telemetry
        "stats" -> {
            if (statsProvider != null) {
                val json = statsProvider()
                log.info("stats: ${json.length} bytes")
                CmdResponse.Text(json)
            } else {
                log.warning("stats not available")
                CmdResponse.None
            }
        }

        else -> {
            log.warning("unknown command: ${parts[0]}")
            CmdResponse.None
        }
    }
}

// ── Control channel spawners ──────────────────────────────────────────────────

/**
 * Spawn stdin reader thread and optional TCP control server.
 *
 * JVM DEVIATION: Rust uses a Unix domain socket (--control-socket /path).
 * JDK 11 lacks UnixDomainSocketAddress (added in JDK 16), so we use a
 * localhost-only TCP server on an explicit --control-port N instead.
 * The wire protocol is identical: newline-delimited text commands.
 *
 * Mirrors Rust `spawn_config_listener` in src/config.rs.
 */
fun spawnConfigListener(
    config: DynamicConfig,
    controlPort: Int?,
    statsProvider: (() -> String)? = null,
    reloadHandler: (() -> Unit)? = null,
) {
    // Stdin reader (always active — mirrors Rust fallback path)
    val stdinThread = Thread({
        try {
            val reader = BufferedReader(InputStreamReader(System.`in`))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val resp = applyCmd(config, line!!, statsProvider)
                if (resp is CmdResponse.Text && resp.text == "reload") {
                    reloadHandler?.invoke()
                }
                // Stdin responses: printed to stdout for interactive use
                if (resp is CmdResponse.Text && resp.text != "reload") {
                    println(resp.text)
                }
            }
        } catch (_: Exception) {}
    }, "srtla-ctrl-stdin")
    stdinThread.isDaemon = true
    stdinThread.start()

    // TCP control server (optional — JVM replacement for Unix socket)
    if (controlPort != null) {
        val tcpThread = Thread({
            try {
                val server = ServerSocket(controlPort, 5, java.net.InetAddress.getLoopbackAddress())
                log.info("control TCP server listening on 127.0.0.1:$controlPort")
                while (!Thread.currentThread().isInterrupted) {
                    val client = server.accept()
                    Thread({
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val writer = PrintWriter(client.getOutputStream(), true)
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val resp = applyCmd(config, line!!, statsProvider)
                                when (resp) {
                                    is CmdResponse.Text -> {
                                        if (resp.text == "reload") {
                                            reloadHandler?.invoke()
                                            writer.println("ok: reload triggered")
                                        } else {
                                            writer.println(resp.text)
                                        }
                                    }
                                    is CmdResponse.None -> {}
                                }
                            }
                        } catch (_: Exception) {}
                        finally { try { client.close() } catch (_: Exception) {} }
                    }, "srtla-ctrl-tcp-client").also { it.isDaemon = true }.start()
                }
            } catch (_: Exception) {
                log.warning("control TCP server failed on port $controlPort")
            }
        }, "srtla-ctrl-tcp")
        tcpThread.isDaemon = true
        tcpThread.start()
    }
}
