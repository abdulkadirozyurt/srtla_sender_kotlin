// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/reload.rs
//
// IP-list parsing and the reload guard. A reload that resolves to zero usable
// source IPs (missing, empty or all-garbage file) is REFUSED so the stream keeps
// running on the existing links. A mixed file still applies; bad lines are
// skipped with a warning.
//
// Each line is `<ip>[ <weight>]`. The optional weight is an operator link weight
// (Moblin's connection priorities): integer 1..10, missing = 1, larger values
// clamped to 10, a 0 or unparsable weight warned about and read as 1. The IP on
// such a line still counts. Weights are normalised so the lowest is 1.
//
// A '#' comment line is not special: like upstream, it is an invalid line that
// is skipped with a warning.
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.connection.LINK_WEIGHT_MAX
import dev.abdulkadirozyurt.srtla.connection.LINK_WEIGHT_MIN
import dev.abdulkadirozyurt.srtla.connection.normaliseLinkWeights
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.reload")

/** Why a reload was refused; the existing connections are kept in every case. */
sealed class ReloadRefusal {
    /** The file could not be opened or read. */
    object NotFound : ReloadRefusal() {
        override fun toString() = "NotFound"
    }

    /** The file has no non-blank lines. */
    object Empty : ReloadRefusal() {
        override fun toString() = "Empty"
    }

    /** Content, but no line parses as an IP. 1-based first invalid line. */
    data class NoValidIps(val firstInvalidLine: Int) : ReloadRefusal()
}

/** Outcome of analyzing an IP file. */
sealed class IpReload {
    /** Apply this non-empty list; weights parallel ips, all 1 when unweighted. */
    data class Apply(val ips: List<InetAddress>, val weights: List<Int>, val firstInvalidLine: Int?) : IpReload()

    data class Refuse(val reason: ReloadRefusal) : IpReload()
}

/** Pure analysis of IP-file text (unit-testable without the filesystem). */
fun analyzeIpReloadText(text: String): IpReload {
    val ips = ArrayList<InetAddress>()
    val weights = ArrayList<Int>()
    var firstInvalidLine: Int? = null
    var sawContent = false
    for ((idx, line) in text.lines().withIndex()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        sawContent = true
        val parsed = parseIpLine(trimmed, idx + 1)
        if (parsed != null) {
            ips.add(parsed.first)
            weights.add(parsed.second)
        } else if (firstInvalidLine == null) {
            firstInvalidLine = idx + 1
        }
    }
    val arr = weights.toIntArray()
    normaliseLinkWeights(arr)
    if (ips.isEmpty()) {
        return if (sawContent) {
            IpReload.Refuse(ReloadRefusal.NoValidIps(firstInvalidLine ?: 1))
        } else {
            IpReload.Refuse(ReloadRefusal.Empty)
        }
    }
    return IpReload.Apply(ips, arr.toList(), firstInvalidLine)
}

/**
 * Parse `<ip>[ <weight>]`. Null when the line has no valid IP literal or more
 * than two fields. A bad weight never invalidates the line.
 */
internal fun parseIpLine(line: String, lineNo: Int): Pair<InetAddress, Int>? {
    val fields = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (fields.isEmpty() || fields.size > 2) return null
    val ip = parseIpLiteral(fields[0]) ?: return null
    val weight = if (fields.size == 1) {
        LINK_WEIGHT_MIN
    } else {
        val raw = fields[1]
        val w = raw.toULongOrNull()
        when {
            w == null || w == 0UL -> {
                log.warning("ips file line $lineNo: weight \"$raw\" for ${ip.hostAddress} is not an integer $LINK_WEIGHT_MIN..$LINK_WEIGHT_MAX; using $LINK_WEIGHT_MIN")
                LINK_WEIGHT_MIN
            }
            w > LINK_WEIGHT_MAX.toULong() -> {
                log.warning("ips file line $lineNo: weight $w for ${ip.hostAddress} is above $LINK_WEIGHT_MAX; clamping to $LINK_WEIGHT_MAX")
                LINK_WEIGHT_MAX
            }
            else -> w.toInt()
        }
    }
    return ip to weight
}

private val IPV4_OCTET = Regex("^(0|[1-9][0-9]{0,2})$")
private val IPV6_CHARS = Regex("^[0-9a-fA-F:.]+$")

/**
 * Parse an IP literal without ever touching DNS (Rust `IpAddr::from_str`).
 * InetAddress.getByName resolves hostnames, so it is only called on input that
 * is already shaped like a literal.
 */
fun parseIpLiteral(s: String): InetAddress? {
    if (s.contains(':')) {
        if (!IPV6_CHARS.matches(s)) return null
        return try {
            InetAddress.getByName(s)
        } catch (_: Exception) {
            null
        }
    }
    val parts = s.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((i, p) in parts.withIndex()) {
        if (!IPV4_OCTET.matches(p)) return null
        val v = p.toInt()
        if (v > 255) return null
        bytes[i] = v.toByte()
    }
    return InetAddress.getByAddress(bytes)
}

/** Read [path] and analyze it; an unreadable file is refused as NotFound. */
fun analyzeIpReload(path: String): IpReload {
    val text = try {
        String(Files.readAllBytes(Paths.get(path)), Charsets.UTF_8)
    } catch (_: IOException) {
        return IpReload.Refuse(ReloadRefusal.NotFound)
    } catch (_: Exception) {
        return IpReload.Refuse(ReloadRefusal.NotFound)
    }
    return analyzeIpReloadText(text)
}

/**
 * Startup read of the IP file. An empty or all-invalid file yields an empty list
 * here (the zero-IP refusal only matters for a reload); an unreadable file throws.
 */
@Throws(IOException::class)
fun readWeightedIpList(path: String): Pair<List<InetAddress>, List<Int>> {
    val text = try {
        String(Files.readAllBytes(Paths.get(path)), Charsets.UTF_8)
    } catch (e: Exception) {
        throw IOException("read IPs file: ${e.message}", e)
    }
    return when (val r = analyzeIpReloadText(text)) {
        is IpReload.Apply -> {
            r.firstInvalidLine?.let { log.warning("ips file has an invalid entry starting at line $it; skipping it") }
            r.ips to r.weights
        }
        is IpReload.Refuse -> emptyList<InetAddress>() to emptyList()
    }
}

/** `ip (weight n), ...` for a log line. */
fun describeWeights(ips: List<InetAddress>, weights: List<Int>): String =
    ips.joinToString(", ") { "${it.hostAddress} (weight ${weightFor(ips, weights, it)})" }
