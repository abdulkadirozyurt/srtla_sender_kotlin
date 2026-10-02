# srtla-sender-kotlin

A JVM (Kotlin/JDK 11) port of [irlserver/srtla_send](https://github.com/irlserver/srtla_send)
v4.1.0 — a multi-uplink SRTLA bonding sender.

---

## Quick Start

### Build

```bash
# With Gradle (requires JDK 11+)
./gradlew build
```

### Run the sender

```bash
# Via Gradle
./gradlew run --args="6000 receiver.example.com 5000 /etc/srtla/uplinks.txt"

# Or directly with the built JAR (JDK 11+)
java -cp build/libs/srtla_sender_kotlin-all.jar \
  dev.abdulkadirozyurt.srtla.cli.MainKt \
  6000 receiver.example.com 5000 /etc/srtla/uplinks.txt
```

### `uplinks.txt` format

One IP per line, with optional weight (1..10, classic mode only). Comments (`#`) and blank lines are ignored.

```
192.168.1.10
10.0.0.5 2
# cellular modem, weight 3
172.16.0.3 3
```

---

## Using as a Library

The sender is a zero-dependency Kotlin/JVM library (Kotlin stdlib + JDK 11+ only),
so it can be embedded in any JVM or Android project.

### Add the dependency (JitPack)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.abdulkadirozyurt:srtla_sender_kotlin:<tag-or-commit>")
}
```

Any git tag, branch (`main-SNAPSHOT`) or commit hash works as the version.
Alternatively, add this repo as a git submodule / included build — there are no
transitive dependencies to manage.

### Embedding example

```kotlin
import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import dev.abdulkadirozyurt.srtla.net.UplinkBinder
import dev.abdulkadirozyurt.srtla.sender.SrtlaSender
import dev.abdulkadirozyurt.srtla.telemetry.SharedStats
import dev.abdulkadirozyurt.srtla.telemetry.SubscriptionHub

val config = DynamicConfig(mode = SchedulingMode.ENHANCED)
val stats = SharedStats()
val sender = SrtlaSender(
    localSrtPort = 6000,
    receiverHost = "rec.example.com",
    receiverPort = 5000,
    ipsFile = "/data/uplinks.txt",
    config = config,
    stats = stats,
    criticalWindow = CriticalWindow(),
    hub = SubscriptionHub(),
    binder = UplinkBinder { channel, ip ->
        // Android: network.bindSocket(channel.socket())
        channel.bind(java.net.InetSocketAddress(ip, 0))
    }
)
val t = Thread { sender.run() }.apply { start() }   // blocks until stop()
config.setMode(SchedulingMode.CLASSIC)               // runtime switch
println(stats.toJson())                              // per-link telemetry, refreshed every second
sender.stop()                                         // from any thread
```

Point your SRT encoder (FFmpeg, OBS, RootEncoder, srt-live-transmit, ...) at
`srt://127.0.0.1:6000` in caller mode; the sender bonds the traffic across all
uplinks towards the SRTLA receiver.

### Using from an Android app

Everything above works on Android (no desugaring needed —
the library uses only `java.nio` and `java.util.concurrent`). Two Android-specific
concerns are covered in [Android Integration](#android-integration):

- bind each uplink to a specific `Network` (Wi-Fi + cellular simultaneously) by
  passing a custom `UplinkBinder` backed by `ConnectivityManager`
- request the cellular network with `requestNetwork` while Wi-Fi is up, and run
  the sender inside a foreground service for IRL streaming use cases

## CLI Arguments

Positional arguments (required in order):
- `SRT_LISTEN_PORT`: Local UDP port for SRT encoder input
- `SRTLA_HOST`: Receiver hostname or IP
- `SRTLA_PORT`: Receiver UDP port
- `BIND_IPS_FILE`: Path to uplinks file (`<ip> [weight]` per line)

Options (in any order):
| Option | Default | Description |
|---|---|---|
| `--mode <MODE>` | `enhanced` | Scheduling: `classic` or `enhanced` |
| `--no-quality` | off | Disable quality scoring (enhanced mode) |
| `--no-stall-deselect` | off | Disable stalled-link deselect guard (on by default) |
| `--stall-min-in-flight <N>` | `32` | In-flight backlog threshold for stall candidate |
| `--stall-ack-stale-ms <MS>` | `3000` | Delivery-proof staleness window |
| `--conn-timeout-ms <MS>` | `5000` | Per-link liveness timeout (1000..60000) |
| `--no-rehome` | off | Disable whole-bond re-home on DNS change |
| `--config <PATH>` | *(none)* | TOML config file (flags override file) |
| `--control-socket <PATH>` | *(none)* | Unix domain socket for JSON-RPC (JDK 16+) |
| `--control-port <PORT>` | *(none)* | Loopback TCP port for JSON-RPC |
| `--priority-bind <ADDR:PORT>` | *(none)* | UDP sidecar for keyframe priority hints |
| `--metrics-bind <ADDR:PORT>` | *(none)* | Prometheus `/metrics` endpoint |
| `-v, --version` | *(none)* | Print version and exit |
| `-h, --help` | *(none)* | Print help |

### Examples

```bash
# Classic mode (original behaviour)
./gradlew run --args="--mode classic 6000 rec.example.com 5000 uplinks.txt"

# Enhanced mode (default)
./gradlew run --args="6000 rec.example.com 5000 uplinks.txt"

# With TCP control on port 9090
./gradlew run --args="--control-port 9090 6000 rec.example.com 5000 uplinks.txt"

# With Prometheus metrics and priority hints
./gradlew run --args="--metrics-bind 127.0.0.1:9099 --priority-bind 127.0.0.1:7000 \
  6000 rec.example.com 5000 uplinks.txt"

# From TOML config + CLI override
./gradlew run --args="--config srtla.toml --mode enhanced 6000 rec.example.com 5000 uplinks.txt"
```

---

## Runtime Control Protocol

Commands use **JSON-RPC 2.0**, one request per line, over **stdin**, a loopback **TCP socket** (`--control-port`)
or a **Unix domain socket** (`--control-socket`, JDK 16+).
Full reference: [docs/CONTROL_PROTOCOL.md](docs/CONTROL_PROTOCOL.md)

Methods:
- `set_mode { "mode": "classic"|"enhanced" }` — switch scheduling mode
- `set_quality { "enabled": true|false }` — enable/disable quality scoring
- `set_stall_deselect { "enabled": true|false }` — stalled-link guard
- `set_conn_timeout { "ms": <1000..60000> }` — per-link liveness timeout (the reply echoes the clamped value)
- `get_status` — current config and priority-window counters
- `get_stats` — per-link telemetry JSON
- `subscribe { "topic": "stats"|"priority.window" }`, `unsubscribe { "subscription_id": "..." }`,
  `get_subscription_count` — push notifications (socket connections only, not stdin)

To reload the IPs file, send `SIGHUP` to the process. Where the runtime has no
`sun.misc.Signal`, the sender watches the IPs file and reloads it when it changes.

### Example (TCP control, JSON-RPC 2.0)

```bash
# Start with control port
./gradlew run --args="--control-port 9090 6000 rec.example.com 5000 uplinks.txt"

# Query status
echo '{"jsonrpc":"2.0","id":1,"method":"get_status"}' | nc 127.0.0.1 9090

# Switch to classic mode
echo '{"jsonrpc":"2.0","id":2,"method":"set_mode","params":{"mode":"classic"}}' | nc 127.0.0.1 9090

# Get stats
echo '{"jsonrpc":"2.0","id":3,"method":"get_stats"}' | nc 127.0.0.1 9090
```

---

## Architecture — Thread Model

```
┌─────────────────────────────────────────────────────┐
│                    SrtlaSender                      │
│                                                     │
│  event-loop thread  (one NIO Selector)              │
│    owns ALL connection state, no locks              │
│    → SRT listener: encoder packets → select uplink  │
│      (classic / enhanced) → batch queue             │
│    → uplink sockets: SRTLA ACK/NAK/keepalive,       │
│      registration, SRT ACK/NAK relay to encoder     │
│    → every 15 ms: flush batch queues                │
│    → every 1 s: housekeeping — keepalives,          │
│      registration driver, timeouts, reconnects      │
│      (1 s ×4 then 5 s), link CC, stats, re-home     │
│                                                     │
│  [optional] control threads (stdin / TCP / Unix)    │
│  [optional] metrics HTTP thread (/metrics)          │
│  [optional] priority sidecar thread (UDP)           │
│    talk to the loop only through atomics            │
│    (DynamicConfig, SharedStats, CriticalWindow)     │
└─────────────────────────────────────────────────────┘
```

**Concurrency:**
- The event-loop thread is the single owner of the connections, registration
  manager, sequence tracker and batch queues, like upstream's single tokio task.
- `DynamicConfig` fields are atomics; the loop reads one `ConfigSnapshot` per tick.
- `SharedStats` is replaced as a whole each second; readers never see a torn update.
- `stop()` and `requestIpReload()` are safe to call from any thread; they wake the selector.

---

## Scheduling Modes

| Mode | Algorithm | Notes |
|---|---|---|
| `classic` | Capacity-only, with link weights (`window / (in_flight + 1)`) | Matches original C implementation; weights from IP file |
| `enhanced` | Quality-aware (NAK decay, burst detection, RTT bonus) + hysteresis + stall detection | Default; applies weak-link classifier, link CC, sole-carrier sticky routing |

---

## Android Integration

The sender runs on Android (minSdk 24+, or with JDK-11 desugaring). The `UplinkBinder` 
functional interface lets you bind uplink sockets to specific `Network` objects via 
`ConnectivityManager`:

```kotlin
import android.net.ConnectivityManager
import android.net.Network
import dev.abdulkadirozyurt.srtla.net.UplinkBinder
import java.net.InetAddress

val cm: ConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

val sender = SrtlaSender(
    localSrtPort = 6000,
    receiverHost = "rec.example.com",
    receiverPort = 5000,
    ipsFile = "/data/srtla/uplinks.txt",  // or load IPs dynamically
    config = config,
    stats = stats,
    criticalWindow = CriticalWindow(),
    hub = SubscriptionHub(),
    binder = UplinkBinder { channel, ip ->
        // Find the Network bound to this IP
        val network: Network = cm.allNetworks.firstOrNull { net ->
            cm.getLinkProperties(net)?.linkAddresses?.any { la -> la.address == ip } ?: false
        } ?: error("No network for $ip")
        // Bind the socket to that network
        network.bindSocket(channel.socket())
        channel.bind(java.net.InetSocketAddress(ip, 0))
    }
)
Thread { sender.run() }.start()
```

For dynamic uplink discovery, enumerate networks from `ConnectivityManager.allNetworks` 
+ `getLinkProperties(net).linkAddresses` instead of a static IP file. Wrap the sender 
in a foreground service for long-running IRL streams.

### Logging on Android

The sender uses `java.util.logging` (JUL). Redirect to Android Logcat with a custom handler:

```kotlin
java.util.logging.Logger.getLogger("").apply {
    addHandler(object : java.util.logging.Handler() {
        override fun publish(r: java.util.logging.LogRecord) {
            android.util.Log.d("SRTLA/${r.loggerName}", r.message)
        }
        override fun flush() {}
        override fun close() {}
    })
}
```

The `SRTLA_LOG` environment variable is not available on Android; use the JUL handler above.

---

## Running Tests

Zero-dependency testkit (no JUnit, no external frameworks).

```bash
# With Gradle (JDK 11–21, Gradle 8.10.2)
./gradlew test

# Run a specific test suite
./gradlew runTests --args="<suite substring>"
```

The testkit covers unit tests, integration tests, and end-to-end scenarios. 
Expected: **all tests pass** with JDK 11+.

---

## Deviations from Rust Reference

| Area | Rust (`irlserver/srtla_send` v4.1.0) | This Kotlin port |
|---|---|---|
| **Batch I/O** | `sendmmsg` / `recvmmsg` Linux syscalls | Per-packet `DatagramChannel` (NIO Selector event loop). JVM has no batch syscall. Semantics and scoring preserved; overhead differs. |
| **Unix socket control** | `--control-socket /path` (any OS) | `--control-socket` via reflection (JDK 16+, Unix only). Fallback: `--control-port` (loopback TCP, JVM extension for JDK 11). |
| **SIGHUP reload** | Direct signal handler | `sun.misc.Signal` (reflection, when available); fallback to Java `WatchService` on IP file directory. JVM lacks portable SIGHUP. |
| **Monotonic clock** | `Instant` (nanosecond precision) | `System.nanoTime()` (nanosecond, non-wall-clock). No NTP step-back issues. |
| **TOML parsing** | Upstream `toml` crate | Hand-rolled mini TOML parser (zero dependencies). Supports all v4.1.0 keys. |
| **JSON serialization** | `serde_json` | Mini JSON serializer (zero dependencies). Emits compact output. |
| **Thread model** | Tokio async runtime (`select!`) | One NIO Selector event-loop thread + per-uplink reader threads + housekeeping tick (1 s) + optional control threads. No virtual threads. |
| **`SRTLA_LOG` env var** | Controls `tracing` filter | Environment variable read at startup; configures `java.util.logging` level globally. No dynamic filter support. |
