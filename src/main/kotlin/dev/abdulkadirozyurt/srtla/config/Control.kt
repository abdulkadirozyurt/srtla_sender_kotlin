// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/control.rs
//
// JSON-RPC 2.0 control protocol, one request per line, over stdin and the
// control socket. A request without `id` is a notification and gets no reply.
//
// Methods:
//   set_mode { mode: "classic"|"enhanced" }
//   set_quality { enabled: bool }
//   set_stall_deselect { enabled: bool }
//   set_conn_timeout { ms: u64 }   (clamped; the reply echoes the applied value)
//   get_status                      current config + priority-sidecar counters
//   get_stats                       per-link telemetry
//   subscribe / unsubscribe / get_subscription_count  (socket connections only)
//
// Error codes: -32700 parse, -32600 invalid request, -32601 method not found,
// -32602 invalid params, -32603 internal.
package dev.abdulkadirozyurt.srtla.config

import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import dev.abdulkadirozyurt.srtla.json.Json
import dev.abdulkadirozyurt.srtla.json.JsonParseException
import dev.abdulkadirozyurt.srtla.json.jsonBool
import dev.abdulkadirozyurt.srtla.json.jsonObject
import dev.abdulkadirozyurt.srtla.json.jsonString
import dev.abdulkadirozyurt.srtla.json.jsonU64
import dev.abdulkadirozyurt.srtla.telemetry.SharedStats
import dev.abdulkadirozyurt.srtla.telemetry.SubscriptionHub
import dev.abdulkadirozyurt.srtla.telemetry.SubscriptionSink

private const val JSONRPC_VERSION = "2.0"

const val PARSE_ERROR: Int = -32700
const val INVALID_REQUEST: Int = -32600
const val METHOD_NOT_FOUND: Int = -32601
const val INVALID_PARAMS: Int = -32602
const val INTERNAL_ERROR: Int = -32603

class RpcError(val code: Int, override val message: String, val data: Any? = null) : Exception(message)

/** A JSON-RPC response: exactly one of result / error is set. */
class RpcResponse private constructor(val id: Any?, val result: Any?, val error: RpcError?) {
    fun toJson(): String {
        val m = LinkedHashMap<String, Any?>()
        m["jsonrpc"] = JSONRPC_VERSION
        if (error == null) {
            m["result"] = result
        } else {
            val e = LinkedHashMap<String, Any?>()
            e["code"] = error.code
            e["message"] = error.message
            if (error.data != null) e["data"] = error.data
            m["error"] = e
        }
        m["id"] = id
        return Json.write(m)
    }

    companion object {
        fun ok(id: Any?, result: Any?) = RpcResponse(id, result, null)
        fun err(id: Any?, error: RpcError) = RpcResponse(id, null, error)
    }
}

/**
 * Per-connection context for subscribe/unsubscribe. Only socket connections
 * have a push channel; stdin answers those methods with method-not-found.
 */
class SubscriptionContext(
    val hub: SubscriptionHub,
    val sink: SubscriptionSink,
    /** Subscription ids owned by this connection, removed when it closes. */
    val ownedIds: MutableList<String>,
)

/** Dispatch one request line; null for blank lines and notifications. */
fun dispatch(
    config: DynamicConfig,
    stats: SharedStats?,
    criticalWindow: CriticalWindow?,
    line: String,
    subscriptionCtx: SubscriptionContext? = null,
): RpcResponse? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    val req = try {
        Json.parse(trimmed)
    } catch (e: JsonParseException) {
        return RpcResponse.err(null, RpcError(PARSE_ERROR, "parse error", e.message))
    }
    val obj = req.jsonObject()
    val jsonrpc = obj?.get("jsonrpc").jsonString()
    val method = obj?.get("method").jsonString()
    if (obj == null || jsonrpc == null || method == null) {
        // serde rejects a request missing required fields as a parse error.
        return RpcResponse.err(null, RpcError(PARSE_ERROR, "parse error", "missing field `jsonrpc` or `method`"))
    }
    val hasId = obj.containsKey("id") && obj["id"] != null
    val id = obj["id"]
    if (jsonrpc != JSONRPC_VERSION) {
        return if (hasId) RpcResponse.err(id, RpcError(INVALID_REQUEST, "jsonrpc version must be \"2.0\"")) else null
    }
    val params = obj["params"]
    val result: Result<Any?> = try {
        Result.success(
            when {
                subscriptionCtx != null && method == "subscribe" -> handleSubscribe(subscriptionCtx, params)
                subscriptionCtx != null && method == "unsubscribe" -> handleUnsubscribe(subscriptionCtx, params)
                subscriptionCtx != null && method == "get_subscription_count" ->
                    linkedMapOf("count" to subscriptionCtx.hub.len())
                else -> handleMethod(config, stats, criticalWindow, method, params)
            },
        )
    } catch (e: RpcError) {
        Result.failure(e)
    }
    if (!hasId) return null
    return result.fold({ RpcResponse.ok(id, it) }, { RpcResponse.err(id, it as RpcError) })
}

private fun isKnownTopic(topic: String): Boolean = topic == "stats" || topic == "priority.window"

private fun handleSubscribe(ctx: SubscriptionContext, params: Any?): Any {
    val topic = params.jsonObject()?.get("topic").jsonString()
        ?: throw RpcError(INVALID_PARAMS, "expected params.topic: string")
    if (!isKnownTopic(topic)) throw RpcError(INVALID_PARAMS, "unknown topic: $topic")
    val id = ctx.hub.subscribe(topic, ctx.sink)
    ctx.ownedIds.add(id)
    return linkedMapOf("subscription_id" to id)
}

private fun handleUnsubscribe(ctx: SubscriptionContext, params: Any?): Any {
    val id = params.jsonObject()?.get("subscription_id").jsonString()
        ?: throw RpcError(INVALID_PARAMS, "expected params.subscription_id: string")
    val removed = ctx.hub.unsubscribe(id)
    ctx.ownedIds.remove(id)
    return linkedMapOf("removed" to removed)
}

private fun handleMethod(
    config: DynamicConfig,
    stats: SharedStats?,
    criticalWindow: CriticalWindow?,
    method: String,
    params: Any?,
): Any? {
    val p = params.jsonObject()
    return when (method) {
        "set_mode" -> {
            val modeStr = p?.get("mode").jsonString()
                ?: throw RpcError(INVALID_PARAMS, "expected params.mode: string")
            val mode = SchedulingMode.parseOrNull(modeStr)
                ?: throw RpcError(INVALID_PARAMS, "unknown mode '$modeStr': use classic or enhanced")
            config.setMode(mode)
            linkedMapOf("mode" to mode.toString())
        }
        "set_quality" -> {
            val enabled = p?.get("enabled").jsonBool()
                ?: throw RpcError(INVALID_PARAMS, "expected params.enabled: bool")
            config.setQualityEnabled(enabled)
            linkedMapOf("enabled" to enabled)
        }
        "set_stall_deselect" -> {
            val enabled = p?.get("enabled").jsonBool()
                ?: throw RpcError(INVALID_PARAMS, "expected params.enabled: bool")
            config.setStallDeselect(enabled)
            linkedMapOf("enabled" to enabled)
        }
        "set_conn_timeout" -> {
            val ms = p?.get("ms").jsonU64() ?: throw RpcError(INVALID_PARAMS, "expected params.ms: u64")
            // Echo the clamped value: the client should trust the reply, not its input.
            linkedMapOf("ms" to config.setConnTimeoutMs(ms))
        }
        "get_status" -> {
            val snap = config.snapshot()
            linkedMapOf(
                "mode" to snap.mode.toString(),
                "quality_enabled" to snap.qualityEnabled,
                "stall_deselect" to snap.stallDeselect,
                "stall_min_in_flight" to snap.stallMinInFlight,
                "stall_ack_stale_ms" to snap.stallAckStaleMs,
                "conn_timeout_ms" to snap.connTimeoutMs,
                "critical_windows_received" to (criticalWindow?.windowsReceived() ?: 0L),
                "critical_malformed_datagrams" to (criticalWindow?.malformedDatagrams() ?: 0L),
            )
        }
        "get_stats" -> {
            val s = stats ?: throw RpcError(INTERNAL_ERROR, "stats provider not registered")
            s.get().toJsonValue()
        }
        "subscribe", "unsubscribe" -> throw RpcError(
            METHOD_NOT_FOUND,
            "$method is reserved for a future streaming protocol, not yet implemented",
        )
        else -> throw RpcError(METHOD_NOT_FOUND, "unknown method: $method")
    }
}
