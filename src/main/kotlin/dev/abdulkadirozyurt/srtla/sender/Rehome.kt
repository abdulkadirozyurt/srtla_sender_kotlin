// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/rehome.rs
//
// Whole-bond re-home: move every uplink to a newly-resolved receiver address
// and register from scratch.
//
// All or nothing: SRTLA registration binds the bond to a receiver-generated id
// that only means something to the instance that minted it, so repointing one
// uplink would split the bond across two receivers. This is the one place
// io.remote changes, and it changes for every uplink in one pass.
//
// Fires only from the dead-bond branch of housekeeping, when all hold:
//   1. every uplink timed out, and stayed so past GLOBAL_TIMEOUT_MS;
//   2. a fresh lookup succeeds and lists none of the pinned addresses
//      (a failed, empty or merely reordered answer is not drift);
//   3. the per-process rate limit allows one attempt per REHOME_MIN_INTERVAL_MS
//      (it covers the DNS probe too).
// On the wire the move is a plain RTT probe and REG1/REG2/REG3, like a fresh start.
package dev.abdulkadirozyurt.srtla.sender

import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.net.resolveRemoteAll
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.rehome")

/** At most one re-home attempt (probe included) per minute, process-wide. */
const val REHOME_MIN_INTERVAL_MS: Long = 60_000L

/** Ceiling on the re-home lookup; housekeeping must not hang on a resolver. */
private const val RESOLVE_TIMEOUT_MS: Long = 3_000L

/** Re-resolution of the receiver hostname, behind a seam for tests. */
fun interface ReceiverResolver {
    /** Null = failed, timed out or empty: never drift. */
    fun resolve(host: String, port: Int): List<InetSocketAddress>?
}

/** Production resolver: getaddrinfo under a timeout. */
object DnsResolver : ReceiverResolver {
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "srtla-rehome-dns").also { it.isDaemon = true }
    }

    override fun resolve(host: String, port: Int): List<InetSocketAddress>? {
        val future = executor.submit(Callable { resolveRemoteAll(host, port) })
        return try {
            val addrs = future.get(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (addrs.isEmpty()) {
                log.fine { "re-home lookup of $host returned no addresses; staying put" }
                null
            } else {
                addrs
            }
        } catch (_: TimeoutException) {
            future.cancel(true)
            log.warning("re-home lookup of $host timed out after ${RESOLVE_TIMEOUT_MS}ms; staying put")
            null
        } catch (e: Exception) {
            log.fine { "re-home lookup of $host failed (${e.cause?.message ?: e.message}); staying put" }
            null
        }
    }
}

/** Opt-out switch, rate limit and resolver seam. */
class RehomeGate(private val enabled: Boolean, internal val resolver: ReceiverResolver = DnsResolver) {
    /** When the last probe slot was spent; null = never. */
    private var lastProbeMs: Long? = null

    /** Completed migrations. Log-only telemetry. */
    var rehomeCount: Long = 0L
        internal set

    /** Claim this tick's probe slot, or report that the rate limit/opt-out forbids one. */
    internal fun claimProbe(now: Long): Boolean {
        if (!enabled) return false
        val last = lastProbeMs
        if (last != null && now.satSub(last) < REHOME_MIN_INTERVAL_MS) return false
        lastProbeMs = now
        return true
    }
}

/** Scripted resolver for tests: answers in order and counts calls. */
class StubResolver(answers: List<List<InetSocketAddress>?>) : ReceiverResolver {
    private val queue = ArrayDeque(answers)
    private val calls = AtomicInteger(0)

    override fun resolve(host: String, port: Int): List<InetSocketAddress>? {
        calls.incrementAndGet()
        return synchronized(queue) { queue.removeFirstOrNull() }
    }

    fun calls(): Int = calls.get()
}

/**
 * Try to migrate the whole bond. Returns true only when it was repointed; the
 * caller then re-arms the all-failed timer.
 */
internal fun tryRehome(
    state: SenderState,
    readers: ReaderRegistry,
    receiverHost: String,
    deadForMs: Long,
    now: Long,
): Boolean {
    val gate = state.rehome
    val current = ArrayList<InetSocketAddress>()
    for (c in state.connections) {
        val io = state.connIo[c.connId] ?: continue
        if (io.remote !in current) current.add(io.remote)
    }
    val pinned = current.firstOrNull() ?: return false
    if (!gate.claimProbe(now)) return false
    val fresh = gate.resolver.resolve(receiverHost, pinned.port) ?: return false
    if (!receiverMoved(current, fresh)) {
        log.fine { "bond down for ${deadForMs}ms but $receiverHost still resolves to it; not re-homing, normal reconnects continue" }
        return false
    }
    val newRemote = fresh[0]
    log.warning(
        "re-homing the whole bond: $receiverHost no longer resolves to $pinned and every uplink has been down " +
            "for ${deadForMs}ms; moving ${state.connections.size} uplink(s) from $pinned to $newRemote and re-registering from scratch",
    )
    rehomeBond(state, readers, newRemote, now)
    gate.rehomeCount++
    return true
}

/**
 * Repoint every uplink, then rebuild sockets. The remote swap happens for the
 * whole bond before any rebuild, so a link whose rebuild fails still reconnects
 * to the new address later.
 */
private fun rehomeBond(state: SenderState, readers: ReaderRegistry, newRemote: InetSocketAddress, now: Long) {
    for (c in state.connections) state.connIo[c.connId]?.remote = newRemote
    for (c in state.connections) {
        val io = state.connIo[c.connId]
        if (io == null) {
            log.warning("${c.label}: no I/O entry to re-home; marking for recovery")
            recoverConnection(c, state.seqTracker)
            continue
        }
        try {
            rebuildUplinkSocket(c, io, state.seqTracker, now)
            readers.restartReaderFor(c, io)
        } catch (e: IOException) {
            log.warning("${c.label}: failed to rebuild socket for re-home (${e.message}); leaving it to the reconnect path, which will now dial $newRemote")
            recoverConnection(c, state.seqTracker)
        }
    }
    // Keep our half of the id, wipe the rest, and re-probe like a fresh start.
    state.reg.resetForRehome()
    for ((idx, pkt) in state.reg.startProbing(state.connections, now)) {
        val c = state.connections.getOrNull(idx) ?: continue
        try {
            state.connIo[c.connId]?.socket?.send(pkt)
        } catch (_: IOException) {
        }
    }
}
