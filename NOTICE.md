# NOTICE — srtla-sender-kotlin

## Primary Attribution

This project is a Kotlin/JVM port of **irlserver/srtla_send v4.1.0**
(commit `5f2e081`), originally written in Rust by Thomas Lekanger.

- Source: https://github.com/irlserver/srtla_send
- License: MIT (see root `LICENSE` file)
- Copyright (c) 2025 Thomas Lekanger

The port was originally made from v3.0.0 (commit `80cd0c4`) by **Abdulkadir Özyurt** 
and has been re-synced to v4.1.0 (2025–2026).
All ported code is released under the same MIT license.

## Protocol Attribution

The SRTLA bonding protocol (REG1/REG2/REG3 handshake, ACK/NAK handling,
keepalive timing, connection group semantics) was designed and published by
**BELABOX** (https://github.com/BELABOX/srtla).

> This is a **clean-room Kotlin port** — no AGPL-licensed BELABOX source
> code was consulted or incorporated. Only the wire protocol specification
> (packet types, field offsets, handshake sequence) was referenced, which
> is a matter of interoperability.

## Additional Inspiration

Scheduling algorithm ideas (quality scoring, NAK decay, weak-link classification,
link congestion control, sole-carrier stickiness) were inspired by 
**Moblin** (https://github.com/eerimoq/moblin), released under the MIT License.

## No Additional Dependencies

This port has **zero external runtime dependencies** beyond the Kotlin
standard library (bundled with the compiler). No Maven Central artifacts
are required at runtime.

## Port Scope

Files ported (under `src/main/kotlin/dev/abdulkadirozyurt/srtla/`):

| Kotlin module | Rust source(s) |
|---|---|
| `protocol/` | `crates/srtla-protocol/src/*` |
| `core/` | `crates/srtla-core/src/{utils,seq,mode,config_snapshot,priority}.rs` |
| `filter/` | `crates/srtla-core/src/{ewma,kalman}.rs` |
| `connection/` | `crates/srtla-core/src/connection/*` |
| `registration/` | `crates/srtla-core/src/registration/*` |
| `selection/` | `crates/srtla-core/src/selection/*` |
| `net/` | `src/net/*`, `src/priority_listener.rs` |
| `sender/` | `src/sender/*` |
| `config/` | `src/{config,control,control_socket,toml_config}.rs` |
| `telemetry/` | `src/{stats,metrics,subscriptions}.rs` |
| `json/` | Mini JSON serializer (replaces `serde_json` crate) |
| `cli/` | `src/{main,version}.rs` |
