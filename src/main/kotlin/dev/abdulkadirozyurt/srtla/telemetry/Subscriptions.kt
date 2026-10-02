// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/subscriptions.rs
//
// Server-push subscriptions over the JSON-RPC control socket. A client
// subscribes to a topic and receives push events as JSON-RPC notifications on
// the same connection:
//   {"jsonrpc":"2.0","method":"stats.update","params":{"subscription_id":"sub-0","data":{...}}}
// Topics: `stats` (once per second) and `priority.window` (each critical-window
// extension from the priority sidecar).
package dev.abdulkadirozyurt.srtla.telemetry

import dev.abdulkadirozyurt.srtla.json.Json
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.subscriptions")

enum class SendOutcome { SENT, FULL, CLOSED }

/** A connection's push channel. */
fun interface SubscriptionSink {
    fun trySend(line: String): SendOutcome
}

/** Bounded push channel for one control-socket connection (Rust mpsc::channel(128)). */
class QueueSubscriptionSink(capacity: Int = 128) : SubscriptionSink {
    val queue: BlockingQueue<String> = ArrayBlockingQueue(capacity)

    @Volatile var isClosed: Boolean = false
        private set

    fun close() {
        isClosed = true
    }

    override fun trySend(line: String): SendOutcome = when {
        isClosed -> SendOutcome.CLOSED
        queue.offer(line) -> SendOutcome.SENT
        else -> SendOutcome.FULL
    }
}

private class Entry(val id: String, val topic: String, val sink: SubscriptionSink)

/** Thread-safe fan-out hub. */
class SubscriptionHub {
    private val lock = Any()
    private var nextId = 0L
    private val entries = ArrayList<Entry>()

    /** Register a subscription; returns the id the client unsubscribes with. */
    fun subscribe(topic: String, sink: SubscriptionSink): String = synchronized(lock) {
        val id = "sub-${nextId++}"
        entries.add(Entry(id, topic, sink))
        id
    }

    /** Remove a subscription by id; true if it was present. */
    fun unsubscribe(id: String): Boolean = synchronized(lock) { entries.removeIf { it.id == id } }

    /**
     * Fan out an event to every subscription of [topic]. A full channel drops the
     * event (a backed-up subscriber never blocks the producer); a closed one is
     * pruned.
     */
    fun publish(topic: String, data: Any?) {
        val targets = synchronized(lock) { entries.filter { it.topic == topic } }
        if (targets.isEmpty()) return
        val prune = ArrayList<String>()
        for (e in targets) {
            val envelope = linkedMapOf(
                "jsonrpc" to "2.0",
                "method" to "$topic.update",
                "params" to linkedMapOf("subscription_id" to e.id, "data" to data),
            )
            when (e.sink.trySend(Json.write(envelope))) {
                SendOutcome.SENT -> {}
                SendOutcome.FULL -> log.fine { "subscription channel full, dropped event (id=${e.id}, topic=$topic)" }
                SendOutcome.CLOSED -> prune.add(e.id)
            }
        }
        if (prune.isNotEmpty()) synchronized(lock) { entries.removeIf { it.id in prune } }
    }

    /** Active subscriptions, all topics. */
    fun len(): Int = synchronized(lock) { entries.size }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }
}
