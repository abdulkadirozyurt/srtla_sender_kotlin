# NOTICE — srtla-sender-kotlin

## Primary Attribution

This project is a Kotlin/JVM port of **irlserver/srtla_send v3.0.0**
(commit `80cd0c4`), originally written in Rust by Thomas Lekanger.

- Source: https://github.com/irlserver/srtla_send
- License: MIT (see root `LICENSE` file)
- Copyright (c) 2025 Thomas Lekanger

The port was made by **Abdulkadir Özyurt** (2025–2026).
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

Scheduling algorithm ideas (quality scoring, BLEST, IoDS, EDPF pipeline)
were inspired by **Moblin** (https://github.com/eerimoq/moblin), released
under the MIT License.

## No Additional Dependencies

This port has **zero external runtime dependencies** beyond the Kotlin
standard library (bundled with the compiler). No Maven Central artifacts
are required at runtime.

## Port Scope

Files ported (under `kotlin/src/main/kotlin/dev/abdulkadirozyurt/srtla/`):

| Kotlin module | Rust source(s) |
|---|---|
| `protocol/` | `src/protocol/{constants,types,parsers,builders}.rs` |
| `connection/` | `src/connection/{mod,ack_nak,socket,batch_send,batch_recv}.rs` |
| `filter/` | `src/{ewma,kalman}.rs` |
| `registration/` | `src/registration/{mod,probing}.rs` |
| `sender/` | `src/sender/{mod,packet_handler,housekeeping,status,sequence}.rs` |
| `sender/selection/` | `src/sender/selection/{mod,classic,enhanced,rtt_threshold,quality,exploration,blest,iods,edpf}.rs` |
| `config/` | `src/config.rs` |
| `stats/` | `src/stats.rs` |
| `cli/` | `src/main.rs` |
