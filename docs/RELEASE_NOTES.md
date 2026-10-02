# srtla-sender-kotlin — sync with upstream srtla_send v4.1.0

This release re-syncs the Kotlin/JVM port with [irlserver/srtla_send](https://github.com/irlserver/srtla_send) **v4.1.0** (`5f2e081`).
The previous Kotlin release was based on v3.0.0 (`80cd0c4`), so it covers 169 upstream commits.

> **Breaking changes.** Read the next section before upgrading. Two scheduling modes were removed. The text control protocol was replaced by JSON-RPC 2.0. The library API was restructured.

## Breaking changes

- **Scheduling modes:** only `classic` and `enhanced` remain. `rtt-threshold`, `edpf`, BLEST, IoDS and exploration were removed upstream as unproven.
  - Removed flags: `--exploration` and `--rtt-delta-ms`.
  - `--mode rtt-threshold` and `--mode edpf` now fail with an error.
- **Control protocol:** the line commands (`mode …`, `quality on`, `status`, `stats`, `reload`) are gone. stdin and the control sockets now speak **JSON-RPC 2.0**, one request per line. See [docs/CONTROL_PROTOCOL.md](CONTROL_PROTOCOL.md).
- **IP reload:** the `reload` command was removed. Send `SIGHUP` instead (see "Operations"). Where SIGHUP is unavailable, the sender watches the IP file and reloads it when it changes.
- **IPs file:** each line is now `<ip>[ <weight>]`. `#` comment lines are no longer special; like upstream, they are invalid lines that are skipped with a warning.
- **Library API:** packages are reorganized to match upstream's sans-IO split:
  - `protocol`: wire format.
  - `core`: clock, sequence math, mode, config snapshot, priority.
  - `connection`, `registration`, `selection`: pure state.
  - `net`, `sender`, `config`, `telemetry`, `cli`: the shell.

  `SrtlaSender` now takes a `DynamicConfig` and the IPs-file path, and `run()` blocks until `stop()`. `Housekeeping.tick` and `SelectionOrchestrator` no longer exist. `UplinkSocketFactory` is now `UplinkBinder`.
- **Stats JSON:** the per-link `bitrate_bps` field is now `bitrate_bytes_per_sec`. Its unit was always bytes per second.

## Fixes ported from upstream

- **SRT NAK parsing:**
  - The loss list is now read at offset 16. The old offset of 4 turned header fields into phantom loss reports.
  - A range end with the MSB set, or below its start, is now rejected.
  - Each NAK expands to at most 1000 ids.
- **ACK sequence wrap:** ACK ordering now uses 31-bit serial arithmetic. Every ACK after a sequence wrap is no longer treated as a duplicate.
- **Monotonic clock:** an NTP step can no longer zero an RTT or reset a link timeout.
- **Uplink sockets** are no longer connected. Replies from a NAT-remapped receiver or from the C reference receiver are now accepted.
- **Registration hardening:** a REG3 or REG_ERR is accepted only while that uplink has the matching handshake in flight. A forged packet can no longer drop a live link.
- **Reconnect timing:** an established link retries 4 times at 1 s, then every 5 s. A short modem blip now re-registers before SRT's 5 s idle timeout.
- **Recovery limbo:** receiver traffic no longer keeps a link that is in recovery alive. Such a link now re-registers.
- **Batch send failures:** a failed batch send now puts the link into recovery. This prevents phantom in-flight packets.
- **Startup order:** the local SRT listener binds before the IPs file is read and before uplinks are dialed.
- **RTT measurement:** all three RTT sources (SRT ACK, SRTLA ACK, keepalive) now feed the estimator. Zero and implausible samples are rejected.
- **Duplicate relays:** a duplicate SRT ACK or NAK that arrives on several uplinks is forwarded to the encoder only once.

## New features

- **Stalled-link deselect** (on by default; disable with `--no-stall-deselect`):
  - The staleness window adapts to RTT.
  - The latch drops a stalled link fast and lets it rejoin slowly, with a rejoin dwell, a backoff and a share ramp.
  - A fast "silence pull" reacts to sub-second stalls.
  - A gated link receives 1-in-100 duplicate probes.
  - Tunables: `--stall-min-in-flight` and `--stall-ack-stale-ms`.
- **Weak-link classifier:** three delay tiers, throughput-share hysteresis, and a probation re-test with backoff.
- **Per-link congestion soft cap:** states Climbing, Holding, BackingOff and Drain, with HAI and fast-recovery climb modes. A link backs off only for loss it caused.
- **Enhanced selection:**
  - A late link is held out of the payload rotation.
  - A BDP-sized in-flight cap and a CC soft cap limit each link.
  - A sticky sole-carrier election runs when every link is degraded.
- **SRT handshake sniffing:** the sender reads the TSBPD latency the receiver declares and uses it as the delivery budget.
- **Retransmit routing:** SRT retransmits (the R bit) go to the best-quality link.
- **Keyframe priority sidecar** (`--priority-bind ADDR:PORT`): a 5-byte UDP datagram opens a critical routing window. See [docs/KEYFRAME_PRIORITY.md](KEYFRAME_PRIORITY.md).
- **Link weights:** each line of the IPs file can carry a weight (Moblin connection priorities). Weights apply in classic mode only.
- **Whole-bond re-home:** when the bond is dead and the receiver's DNS answer has moved, every uplink moves to the new address. Disable with `--no-rehome`.
- **Liveness timeout:** set it with `--conn-timeout-ms` (clamped to 1000–60000), or at runtime with `set_conn_timeout`.
- **Prometheus endpoint:** `--metrics-bind ADDR:PORT` serves `/metrics`.
- **JSON-RPC subscriptions:** clients can subscribe to the `stats` and `priority.window` topics.
- **TOML config file** (`--config <PATH>`): a flag typed on the command line wins over the file. An unknown key stops startup.
- **Adaptive batch size:** 4, 16 or 32 packets, chosen from each link's bitrate.

## Operations

- `SIGTERM` and `SIGINT` stop the event loop cleanly.
- `SIGHUP` reloads the IPs file through `sun.misc.Signal` where the runtime offers it. Otherwise the sender watches the IPs file and reloads it on change.
  - A reload that yields zero valid IPs is refused, and the current links keep running.
- `-v` prints a version line with git metadata, never `unknown`.

## JVM-specific notes

- **Threading:** one NIO `Selector` event-loop thread owns all connection state, mirroring upstream's single tokio task. Housekeeping runs every 1 s and batch flush every 15 ms.
- **Unix socket:** `--control-socket` needs JDK 16+. `--control-port <PORT>` offers the same protocol, subscriptions included, on loopback TCP.
- **No batch syscalls:** the JVM has no `sendmmsg`/`recvmmsg`, so datagrams are sent one by one. The batch queue still affects scheduling scores.
- **Zero dependencies:** the port still uses only the Kotlin stdlib and the JDK. JSON, TOML and argument parsing are hand-written.
- **Send errors:** a send the JVM rejects with an unchecked `UnsupportedAddressTypeException` (for example an IPv4-only uplink aimed at an IPv6 receiver) is now treated like any other send error, so the link goes into recovery as it does upstream.
- **Tests:** 496 tests. Upstream unit and integration tests were ported to the zero-dependency testkit. The Linux netns, Apple and miri tests were not ported. Loopback E2E tests cover the event loop, the control socket, `/metrics` and the priority sidecar.
- **Build:** use JDK 11–21 to run Gradle 8.10.2.
