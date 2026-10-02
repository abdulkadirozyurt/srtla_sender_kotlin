// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/main.rs (struct Cli, apply_config_file)
//
// Hand-rolled argument parser (zero dependencies; upstream uses clap).
// JVM deviations: --control-port (loopback TCP control socket) in addition to
// --control-socket (Unix socket, JDK 16+).
package dev.abdulkadirozyurt.srtla.cli

import dev.abdulkadirozyurt.srtla.config.TomlConfig
import dev.abdulkadirozyurt.srtla.core.CONN_TIMEOUT_MS
import dev.abdulkadirozyurt.srtla.core.STALL_ACK_STALE_MS
import dev.abdulkadirozyurt.srtla.core.STALL_MIN_IN_FLIGHT_PACKETS
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import java.net.InetAddress
import java.net.InetSocketAddress

const val APP_NAME: String = "srtla_send_kotlin"

class ArgsException(message: String) : Exception(message)

/** Parsed command line. Option names mirror upstream's long flags. */
data class ParsedArgs(
    val printVersion: Boolean = false,
    val printHelp: Boolean = false,
    val localSrtPort: Int? = null,
    val receiverHost: String? = null,
    val receiverPort: Int? = null,
    val ipsFile: String? = null,
    val controlSocket: String? = null,
    val controlPort: Int? = null,
    val configFile: String? = null,
    val mode: SchedulingMode = SchedulingMode.ENHANCED,
    val noQuality: Boolean = false,
    val noStallDeselect: Boolean = false,
    val stallMinInFlight: Int = STALL_MIN_IN_FLIGHT_PACKETS,
    val stallAckStaleMs: Long = STALL_ACK_STALE_MS,
    val connTimeoutMs: Long = CONN_TIMEOUT_MS,
    val noRehome: Boolean = false,
    val priorityBind: InetSocketAddress? = null,
    val metricsBind: InetSocketAddress? = null,
    /** Option ids (TOML key spelling) typed on the command line. */
    val typed: Set<String> = emptySet(),
) {
    /**
     * Replace each option not typed on the command line with its value from the
     * config file. Precedence: command line, then file, then default.
     */
    fun applyConfigFile(file: TomlConfig): ParsedArgs {
        fun <T> pick(id: String, cli: T, fromFile: T?): T = if (fromFile != null && id !in typed) fromFile else cli
        return copy(
            mode = pick("mode", mode, file.mode),
            noQuality = pick("no_quality", noQuality, file.noQuality),
            noStallDeselect = pick("no_stall_deselect", noStallDeselect, file.noStallDeselect),
            stallMinInFlight = pick("stall_min_in_flight", stallMinInFlight, file.stallMinInFlight),
            stallAckStaleMs = pick("stall_ack_stale_ms", stallAckStaleMs, file.stallAckStaleMs),
            connTimeoutMs = pick("conn_timeout_ms", connTimeoutMs, file.connTimeoutMs),
        )
    }
}

@Throws(ArgsException::class)
fun parseArgs(args: Array<String>): ParsedArgs {
    var p = ParsedArgs()
    val typed = HashSet<String>()
    val positional = ArrayList<String>()
    var i = 0

    fun value(flag: String): String {
        i++
        if (i >= args.size) throw ArgsException("a value is required for '$flag'")
        return args[i]
    }

    while (i < args.size) {
        val raw = args[i]
        // Accept --flag=value as well as --flag value.
        val (arg, inline) = if (raw.startsWith("--") && raw.contains('=')) {
            raw.substringBefore('=') to raw.substringAfter('=')
        } else {
            raw to null
        }
        fun v(): String = inline ?: value(arg)
        when (arg) {
            "-v", "--version" -> p = p.copy(printVersion = true)
            "-h", "--help" -> p = p.copy(printHelp = true)
            "--no-quality" -> { p = p.copy(noQuality = true); typed += "no_quality" }
            "--no-stall-deselect" -> { p = p.copy(noStallDeselect = true); typed += "no_stall_deselect" }
            "--no-rehome" -> p = p.copy(noRehome = true)
            "--mode" -> {
                val s = v()
                p = p.copy(mode = SchedulingMode.parseOrNull(s)
                    ?: throw ArgsException("invalid value '$s' for '--mode <MODE>': possible values: classic, enhanced"))
                typed += "mode"
            }
            "--stall-min-in-flight" -> {
                p = p.copy(stallMinInFlight = v().toIntOrNull() ?: throw ArgsException("invalid value for '--stall-min-in-flight'"))
                typed += "stall_min_in_flight"
            }
            "--stall-ack-stale-ms" -> {
                p = p.copy(stallAckStaleMs = v().toLongOrNull()?.takeIf { it >= 0 } ?: throw ArgsException("invalid value for '--stall-ack-stale-ms'"))
                typed += "stall_ack_stale_ms"
            }
            "--conn-timeout-ms" -> {
                p = p.copy(connTimeoutMs = v().toLongOrNull()?.takeIf { it >= 0 } ?: throw ArgsException("invalid value for '--conn-timeout-ms'"))
                typed += "conn_timeout_ms"
            }
            "--config" -> p = p.copy(configFile = v())
            "--control-socket" -> p = p.copy(controlSocket = v())
            "--control-port" -> p = p.copy(controlPort = parsePort(v(), "--control-port"))
            "--priority-bind" -> p = p.copy(priorityBind = parseSocketAddr(v(), "--priority-bind"))
            "--metrics-bind" -> p = p.copy(metricsBind = parseSocketAddr(v(), "--metrics-bind"))
            else -> {
                if (raw.startsWith("-") && raw.length > 1) throw ArgsException("unexpected argument '$raw' found")
                positional += raw
            }
        }
        i++
    }
    p = p.copy(typed = typed)
    if (p.printVersion || p.printHelp) return p
    if (positional.size != 4) {
        throw ArgsException("expected 4 positional arguments (SRT_LISTEN_PORT SRTLA_HOST SRTLA_PORT BIND_IPS_FILE), got ${positional.size}")
    }
    return p.copy(
        localSrtPort = parsePort(positional[0], "SRT_LISTEN_PORT", allowZero = true),
        receiverHost = positional[1],
        receiverPort = parsePort(positional[2], "SRTLA_PORT"),
        ipsFile = positional[3],
    )
}

private fun parsePort(s: String, what: String, allowZero: Boolean = false): Int {
    val n = s.toIntOrNull() ?: throw ArgsException("invalid value '$s' for '$what': not a port number")
    val min = if (allowZero) 0 else 1
    if (n !in min..65535) throw ArgsException("invalid value '$s' for '$what': must be $min..65535")
    return n
}

/** `ADDR:PORT`, with IPv6 as `[::1]:PORT`. Literal addresses only (no DNS). */
internal fun parseSocketAddr(s: String, what: String): InetSocketAddress {
    val (host, port) = if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end < 0 || end + 1 >= s.length || s[end + 1] != ':') throw ArgsException("invalid socket address '$s' for '$what'")
        s.substring(1, end) to s.substring(end + 2)
    } else {
        val idx = s.lastIndexOf(':')
        if (idx <= 0) throw ArgsException("invalid socket address '$s' for '$what'")
        s.substring(0, idx) to s.substring(idx + 1)
    }
    val addr: InetAddress = dev.abdulkadirozyurt.srtla.sender.parseIpLiteral(host)
        ?: throw ArgsException("invalid socket address '$s' for '$what'")
    return InetSocketAddress(addr, parsePort(port, what, allowZero = true))
}

fun usage(): String = """
Usage: $APP_NAME [OPTIONS] SRT_LISTEN_PORT SRTLA_HOST SRTLA_PORT BIND_IPS_FILE

Arguments:
  SRT_LISTEN_PORT   Local UDP port to listen for SRT packets
  SRTLA_HOST        Receiver host (srtla_rec or SRT listener)
  SRTLA_PORT        Receiver UDP port to send SRTLA packets to
  BIND_IPS_FILE     File with one `<ip>[ <weight>]` per line (weight 1..10, classic mode only)

Options:
  --mode <MODE>                Scheduling mode: classic, enhanced (default)
  --no-quality                 Disable quality scoring (enhanced only)
  --no-stall-deselect          Disable the stalled-link deselect guard (on by default)
  --stall-min-in-flight <N>    In-flight backlog for a stall candidate (default $STALL_MIN_IN_FLIGHT_PACKETS)
  --stall-ack-stale-ms <MS>    Ceiling of the delivery-proof staleness window (default $STALL_ACK_STALE_MS)
  --conn-timeout-ms <MS>       Per-link liveness timeout, clamped 1000..60000 (default $CONN_TIMEOUT_MS)
  --no-rehome                  Disable whole-bond re-home when the receiver's DNS moves
  --config <PATH>              TOML config file; a typed flag wins over the file
  --control-socket <PATH>      Unix domain socket for JSON-RPC control (JDK 16+)
  --control-port <PORT>        Loopback TCP port for JSON-RPC control (JVM extension)
  --priority-bind <ADDR:PORT>  UDP sidecar for encoder keyframe priority hints
  --metrics-bind <ADDR:PORT>   Prometheus /metrics endpoint
  -v, --version                Print version and exit
  -h, --help                   Print help

Environment:
  SRTLA_LOG   debug | info (default) | warn | error | off   (upstream: RUST_LOG)
""".trimIndent()
