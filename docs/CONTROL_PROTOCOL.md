Ported from irlserver/srtla_send v4.1.0 docs (MIT); adapted for the Kotlin/JVM port.

# srtla_send control protocol

srtla_send_kotlin exposes runtime control over standard input, a Unix domain socket (JDK 16+), or a loopback TCP socket (JVM extension). The wire format is JSON-RPC 2.0, one message per line. The socket paths are set with `--control-socket <path>` (Unix socket) and `--control-port <port>` (TCP loopback). Standard input only supports requests; subscriptions work on both socket transports.

## Request/response

One request per line, UTF-8 JSON. Responses end with a newline.

Request:

```json
{"jsonrpc": "2.0", "id": 1, "method": "set_mode", "params": {"mode": "enhanced"}}
```

Success:

```json
{"jsonrpc": "2.0", "result": {"mode": "enhanced"}, "id": 1}
```

Error (standard JSON-RPC codes):

```json
{"jsonrpc": "2.0", "error": {"code": -32602, "message": "expected params.mode: string"}, "id": 1}
```

## Notifications

Requests without `id` are notifications. srtla_send processes them and sends no response. Useful for one-way config pokes when round-tripping a reply would be wasteful.

```json
{"jsonrpc": "2.0", "method": "set_mode", "params": {"mode": "classic"}}
```

## Methods

### `set_mode`

Switch the link scheduler.

| param | type | values |
| --- | --- | --- |
| `mode` | string | `"classic"`, `"enhanced"` |

Result: `{ "mode": "<current>" }`.

### `set_quality`

Toggle quality scoring (enhanced mode).

Params: `{ "enabled": bool }`. Result: `{ "enabled": bool }`.

### `set_stall_deselect`

Toggle the stalled-link deselect guard (defaults on).

Params: `{ "enabled": bool }`. Result: `{ "enabled": bool }`.

### `set_conn_timeout`

Set the per-link liveness timeout. Clamped to the valid range (the reply echoes the applied value).

Params: `{ "ms": u64 }`. Result: `{ "ms": <applied> }`.

### `get_status`

Return the full runtime configuration plus priority-sidecar telemetry.

Result:

```json
{
  "mode": "enhanced",
  "quality_enabled": true,
  "stall_deselect": true,
  "stall_min_in_flight": 3,
  "stall_ack_stale_ms": 200,
  "conn_timeout_ms": 5000,
  "critical_windows_received": 142,
  "critical_malformed_datagrams": 0
}
```

### `get_stats`

Return per-link telemetry (extended statistics snapshot).

```json
{
  "mode": "enhanced",
  "quality_enabled": true,
  "active_links": 2,
  "total_links": 3,
  "total_window": 1024,
  "total_in_flight": 45,
  "weak_link_estimated_max_delay_ms": 50,
  "weak_link_selected_delay_ms": 30,
  "negotiated_latency_ms": 100,
  "links": [
    {
      "ip": "192.168.1.1",
      "label": "192.168.1.1 via 10.0.0.2",
      "connected": true,
      "timed_out": false,
      "window": 512,
      "in_flight": 23,
      "rtt_ms": 45,
      "nak_count": 2,
      "bitrate_bytes_per_sec": 1250000,
      "rtt_min_ms": 40.5,
      "rtt_velocity": 0.1,
      "base_score": 22,
      "quality_multiplier": 0.95,
      "weak": false,
      "weak_reason": "ok",
      "weak_share_permille": 500,
      "weak_threshold_permille": 800,
      "cc_state": "holding",
      "cc_climb_mode": "normal",
      "cc_target_bps": 10000000,
      "cc_rtt_ewma_ms": 45.2,
      "cc_rtt_var_ms": 2.5,
      "cc_rtt_min_ms": 40.0,
      "cc_loss_permille": 5,
      "cc_loss_ewma": 0.002,
      "cc_loss_degraded": false,
      "batch_regime": "normal",
      "stall_gated": false,
      "stall_gate_events": 0,
      "weight": 1,
      "silence_pulls": 0,
      "sole_carrier": true,
      "sole_carrier_excluded": false,
      "sole_carrier_elections": 1,
      "in_flight_cap_packets": 64,
      "in_flight_cap_active": false
    }
  ]
}
```

### `get_subscription_count`

Query the active subscription count on this connection (socket only).

Result: `{ "count": <N> }`.

## Subscriptions

The Unix and TCP control sockets support server-push subscriptions. Polling `get_stats` at 1 Hz misses sub-second link-state changes (NAK bursts, quality drops, reconnects); subscriptions let clients receive push events on the same socket they already use for requests.

### `subscribe`

Params: `{ "topic": "stats" | "priority.window" }`. Result: `{ "subscription_id": string }`.

### `unsubscribe`

Params: `{ "subscription_id": string }`. Result: `{ "removed": bool }`.

### Push events

Server-originated notifications are sent on the same connection:

```json
{
  "jsonrpc": "2.0",
  "method": "stats.update",
  "params": {
    "subscription_id": "sub-0",
    "data": { /* StatsSnapshot */ }
  }
}
```

Topics currently implemented:

| Topic | Data | Cadence |
| --- | --- | --- |
| `stats` | Full `StatsSnapshot` (same shape as `get_stats`) | Once per second, aligned with housekeeping |
| `priority.window` | `{ at_ms, window_ms, deadline_ms }` | Once per keyframe window from the priority sidecar |

Subscriptions live for the life of the connection. Closing the socket cancels every subscription it owns.

Standard input is request-only — subscriptions only work on Unix and TCP sockets.

## Error codes

| code | meaning |
| --- | --- |
| `-32700` | parse error (invalid JSON) |
| `-32600` | invalid request (missing / wrong `jsonrpc` field) |
| `-32601` | method not found |
| `-32602` | invalid params |
| `-32603` | internal error |

## Examples

### Unix socket (with socat, JDK 16+)

```
$ echo '{"jsonrpc":"2.0","id":1,"method":"get_status"}' \
    | socat - UNIX-CONNECT:/tmp/srtla.sock
{"jsonrpc":"2.0","result":{"mode":"enhanced",...},"id":1}
```

Start the sender with:

```
java -jar srtla_send_kotlin.jar --control-socket /tmp/srtla.sock \
  6000 rec.example.com 5000 /tmp/uplinks
```

### TCP loopback (JVM extension)

```
$ echo '{"jsonrpc":"2.0","id":1,"method":"get_status"}' | nc 127.0.0.1 9090
{"jsonrpc":"2.0","result":{"mode":"enhanced",...},"id":1}
```

Or using gradle to run:

```
./gradlew run --args='--control-port 9090 6000 rec.example.com 5000 /tmp/uplinks'
```

Then connect with netcat:

```
nc 127.0.0.1 9090
```

### Subscription example (TCP)

```
$ nc 127.0.0.1 9090
{"jsonrpc":"2.0","id":1,"method":"subscribe","params":{"topic":"stats"}}
{"jsonrpc":"2.0","result":{"subscription_id":"sub-0"},"id":1}
{"jsonrpc":"2.0","method":"stats.update","params":{"subscription_id":"sub-0","data":{...}}}
{"jsonrpc":"2.0","method":"stats.update","params":{"subscription_id":"sub-0","data":{...}}}
```

### Switching mode at runtime

```
$ echo '{"jsonrpc":"2.0","id":1,"method":"set_mode","params":{"mode":"classic"}}' \
    | nc 127.0.0.1 9090
```

### Standard input (no subscriptions)

```
$ java -jar srtla_send_kotlin.jar 6000 rec.example.com 5000 /tmp/uplinks
{"jsonrpc":"2.0","id":1,"method":"get_status"}
{"jsonrpc":"2.0","result":{"mode":"enhanced",...},"id":1}
```
