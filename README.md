# srtla-sender-kotlin

A JVM (Kotlin/JDK 11) port of [irlserver/srtla_send](https://github.com/irlserver/srtla_send)
v3.0.0 — a multi-uplink SRTLA bonding sender.

---

## Quick Start

### Build (requires Kotlin compiler)

```bash
# Compile all sources
kotlinc $(find src -name "*.kt") -d srtla.jar

# Or with Gradle (on a machine with internet / Gradle cache)
./gradlew build
```

### Run the sender

```bash
java -cp srtla.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar \
  dev.abdulkadirozyurt.srtla.cli.MainKt \
  --receiver-host live.example.com \
  --receiver-port 5000 \
  --ip-file /etc/srtla/uplinks.txt \
  --srt-port 1935
```

### `uplinks.txt` format

One IP address per line. Comments (`#`) and blank lines are ignored.

```
192.168.1.10
10.0.0.5
# cellular modem
172.16.0.3
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
import dev.abdulkadirozyurt.srtla.config.applyCmd
import dev.abdulkadirozyurt.srtla.sender.Housekeeping
import dev.abdulkadirozyurt.srtla.sender.SrtlaSender
import dev.abdulkadirozyurt.srtla.sender.selection.SchedulingMode
import dev.abdulkadirozyurt.srtla.stats.SharedStats
import java.net.InetAddress

// 1. Runtime-tunable configuration (thread-safe, atomics-based)
val config = DynamicConfig.fromCli(mode = SchedulingMode.ENHANCED)
val stats  = SharedStats()

// 2. Create the sender: local SRT ingest port -> SRTLA receiver, over N uplinks
val sender = SrtlaSender(
    localSrtPort = 6000,                       // your SRT encoder sends to udp://127.0.0.1:6000
    receiverHost = "receiver.example.com",     // srtla_rec host
    receiverPort = 5000,
    sourceIps    = listOf(
        InetAddress.getByName("192.168.0.2"),  // uplink 1 (e.g. modem A)
        InetAddress.getByName("192.168.1.2"),  // uplink 2 (e.g. modem B)
    ),
    config = config.snapshot(),
    // socketFactory = ...                     // see Android Integration below
)
sender.start()

// 3. Housekeeping loop (keepalives, timeouts, window recovery, stats refresh).
//    Run on your own scheduler/thread; tick every ~100 ms.
var lastStatusLogMs = 0L
val housekeeper = Thread {
    while (!Thread.currentThread().isInterrupted) {
        lastStatusLogMs = Housekeeping.tick(sender, config, stats, lastStatusLogMs)
        Thread.sleep(100)
    }
}.apply { isDaemon = true; start() }

// 4. Observe per-link telemetry (bitrate, RTT, window, in-flight, quality)
val snapshot = stats.get()        // typed snapshot
val json     = stats.toJson()     // or JSON for UI/IPC

// 5. Change behaviour at runtime — same commands as the CLI control channel
applyCmd(config, "mode rtt-threshold")
applyCmd(config, "rtt-delta 50")
applyCmd(config, "quality off")

// 6. Shutdown
housekeeper.interrupt()
sender.stop()
```

Point your SRT encoder (FFmpeg, OBS, RootEncoder, srt-live-transmit, ...) at
`srt://127.0.0.1:6000` in caller mode; the sender bonds the traffic across all
uplinks towards the SRTLA receiver.

### Using from an Android app

Everything above works on Android (minSdk with JDK-11 desugaring not required —
the library uses only `java.nio` and `java.util.concurrent`). Two Android-specific
concerns are covered in [Android Integration](#android-integration):

- bind each uplink to a specific `Network` (Wi-Fi + cellular simultaneously) by
  injecting a custom `UplinkSocketFactory` backed by `ConnectivityManager`
- request the cellular network with `requestNetwork` while Wi-Fi is up, and run
  the sender inside a foreground service for IRL streaming use cases

## CLI Arguments

| Argument | Default | Description |
|---|---|---|
| `--receiver-host <host>` | *(required)* | SRTLA receiver hostname or IP |
| `--receiver-port <port>` | *(required)* | SRTLA receiver UDP port |
| `--ip-file <path>` | *(required)* | Path to IP list file |
| `--srt-port <port>` | `1935` | Local UDP port where the SRT encoder connects |
| `--mode <mode>` | `enhanced` | Scheduling mode: `classic`, `enhanced`, `rtt-threshold`, `edpf` |
| `--no-quality` | off | Disable quality scoring (enhanced/rtt-threshold modes) |
| `--exploration` | off | Enable smart link exploration (enhanced mode only) |
| `--rtt-delta-ms <ms>` | `30` | RTT grouping threshold for rtt-threshold mode |
| `--control-port <port>` | *(none)* | Enable TCP control server on `127.0.0.1:<port>` |

### Examples

```bash
# Classic mode (original C-compatible behaviour)
java -jar srtla.jar --receiver-host relay.isp.net --receiver-port 5000 \
  --ip-file uplinks.txt --mode classic

# Enhanced mode with exploration, RTT delta 50ms
java -jar srtla.jar --receiver-host relay.isp.net --receiver-port 5000 \
  --ip-file uplinks.txt --mode enhanced --exploration --rtt-delta-ms 50

# With TCP control server on port 9090
java -jar srtla.jar --receiver-host relay.isp.net --receiver-port 5000 \
  --ip-file uplinks.txt --control-port 9090
```

---

## Runtime Control Commands

Commands are sent line-by-line via **stdin** or via a **TCP control connection** to
`127.0.0.1:<control-port>`.

| Command | Description |
|---|---|
| `mode classic` | Switch to classic (capacity-only) selection |
| `mode enhanced` | Switch to enhanced (quality-aware) selection |
| `mode rtt-threshold` | Switch to RTT-grouped selection |
| `mode edpf` | Switch to BLEST→IoDS→EDPF pipeline |
| `quality on\|off` | Enable/disable quality scoring |
| `explore on\|off` | Enable/disable link exploration |
| `rtt-delta <ms>` | Set RTT grouping threshold |
| `status` | Print current mode and configuration |
| `stats` | Print per-link JSON telemetry |
| `reload` | Reload IP list from file |

### Example (TCP control)

```bash
echo "status" | nc 127.0.0.1 9090
echo "mode enhanced" | nc 127.0.0.1 9090
echo "stats" | nc 127.0.0.1 9090
```

---

## Architecture — Thread Model

```
┌─────────────────────────────────────────────────────┐
│                    SrtlaSender                      │
│                                                     │
│  srt-listener thread                                │
│    Reads SRT UDP packets from encoder               │
│    → acquires stateLock                             │
│    → drains inboundQueue (up to 64 packets)         │
│    → selects uplink via SelectionOrchestrator       │
│    → sends on selected uplink socket                │
│                                                     │
│  uplink-<label> thread  (one per uplink)            │
│    Blocking recv on DatagramChannel                 │
│    → enqueues into inboundQueue (lock-free)         │
│                                                     │
│  housekeeping thread  (1 Hz)                        │
│    → sends keepalives (IDLE_TIME = 1s)              │
│    → drives RegistrationManager state machine       │
│    → detects timeouts (CONN_TIMEOUT = 5s)           │
│    → triggers reconnects (exponential backoff)      │
│    → updates SharedStats, logs status every 30s     │
│                                                     │
│  [optional] ctrl-stdin thread                       │
│  [optional] ctrl-tcp-server thread                  │
│    Both call applyCmd() — lock-free via atomics     │
└─────────────────────────────────────────────────────┘
```

**Locking strategy:**
- A single `ReentrantLock` (`stateLock`) protects the connections list, registration
  manager, last-selected index, and sequence tracker.
- Per-uplink reader threads enqueue via `LinkedBlockingQueue` (lock-free produce).
- `DynamicConfig` fields are all `AtomicReference`/`AtomicBoolean`/`AtomicInteger` —
  no lock needed for config reads.

---

## Scheduling Modes

| Mode | Algorithm | Notes |
|---|---|---|
| `classic` | Capacity-only (`window / (in_flight + 1)`) | Matches original C implementation |
| `enhanced` | Quality-aware + hysteresis + optional exploration | Default |
| `rtt-threshold` | RTT group buckets + quality within group | Good for mixed-latency links |
| `edpf` | BLEST → IoDS → EDPF argmin pipeline | Latency-optimised |

---

## Android Integration

### UplinkSocketFactory

The `UplinkSocketFactory` interface abstracts socket creation, allowing Android's
`ConnectivityManager` / `Network.bindSocket` pattern to be injected:

```kotlin
import android.net.ConnectivityManager
import android.net.Network
import dev.abdulkadirozyurt.srtla.connection.UplinkSocketFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

class AndroidUplinkSocketFactory(
    private val cm: ConnectivityManager,
) : UplinkSocketFactory {

    override fun createAndBind(
        sourceIp: InetAddress,
        remoteAddr: InetSocketAddress,
    ): DatagramChannel {
        // Find the Network whose link-local address matches sourceIp
        val network: Network = cm.allNetworks
            .firstOrNull { net ->
                cm.getLinkProperties(net)
                    ?.linkAddresses
                    ?.any { la -> la.address == sourceIp }
                    ?: false
            }
            ?: error("No network found for $sourceIp")

        val ch = DatagramChannel.open()
        // Bind the socket to this specific network interface
        network.bindSocket(ch.socket())
        ch.socket().bind(InetSocketAddress(sourceIp, 0))
        ch.connect(remoteAddr)
        ch.configureBlocking(false)
        return ch
    }
}
```

Then pass it to `SrtlaSender`:

```kotlin
val factory = AndroidUplinkSocketFactory(connectivityManager)
val sender = SrtlaSender(
    localSrtPort  = 1935,
    receiverHost  = "relay.example.com",
    receiverPort  = 5000,
    sourceIps     = listOf(wifiIp, cellIp),
    socketFactory = factory,
)
sender.start()
```

> **Note:** On Android, enumerate source IPs from `ConnectivityManager.allNetworks` +
> `getLinkProperties(net).linkAddresses` rather than from a static IP file.

### Logging on Android

The JVM sender uses `java.util.logging` (JUL). To redirect logs to Android Logcat,
install a custom `Handler` at startup:

```kotlin
val root = java.util.logging.Logger.getLogger("")
root.addHandler(object : java.util.logging.Handler() {
    override fun publish(r: java.util.logging.LogRecord) {
        android.util.Log.d("SRTLA/${r.loggerName}", r.message)
    }
    override fun flush() {}
    override fun close() {}
})
```

The `SRTLA_LOG` environment variable is not used; configure via the JUL API above.

---

## Running Tests

### Testkit runner (no Gradle / JUnit required)

```bash
# Compile
kotlinc $(find src -name "*.kt") -d srtla-test.jar

# Run
java -cp "srtla-test.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar" \
  dev.abdulkadirozyurt.srtla.testkit.TestRunnerKt
```

Expected output: **328 tests: 328 passed, 0 failed**

### With Gradle (recommended)

```bash
./gradlew test        # runs the full testkit suite (alias: ./gradlew runTests)
```

---

## Deviations from Rust Reference

| Area | Rust (`irlserver/srtla_send`) | This Kotlin port |
|---|---|---|
| **Control channel** | Unix domain socket (`--control-socket /path`) | TCP server on `127.0.0.1:<port>` (`--control-port N`). JDK 11 has no `UnixDomainSocketAddress` (added in JDK 16). Wire protocol is identical. |
| **IP reload trigger** | `SIGHUP` Unix signal | `reload` control command + Java `WatchService` on IP file directory. JVM has no reliable cross-platform SIGHUP handling. |
| **Batch I/O** | `sendmmsg` / `recvmmsg` Linux syscalls | Plain per-packet `DatagramChannel` send/recv. JVM has no `sendmmsg` equivalent. Semantics identical; only syscall overhead differs. |
| **Async runtime** | Tokio (`select!` macro, async tasks) | Blocking threads (`Thread`, `LinkedBlockingQueue`). JVM `CompletableFuture`/`VirtualThread` are available but not needed for this use case. |
| **`SRTLA_LOG` env var** | Controls Rust tracing level | Not implemented. Use `java.util.logging` configuration or Android Handler injection instead. |
| **Probing RTT** | Used to select best uplink for initial REG1 | Same algorithm; uses loopback RTT in test environment. |
