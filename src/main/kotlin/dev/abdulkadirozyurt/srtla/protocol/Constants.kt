// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/protocol/constants.rs
//
// UNSIGNED / ENDIANNESS POLICY (applies to the entire protocol package):
//   All packet-type values are 16-bit unsigned (0x9000..0x9212, 0x8000..0x8005).
//   Kotlin's Int is 32-bit signed.  We store them as Int with explicit masking
//   so we never overflow: `buf[0].toInt() and 0xFF shl 8 or (buf[1].toInt() and 0xFF)`.
//   Constants that exceed Int range do NOT exist in this file (none do — max is 0x9212).
//   Sequence numbers and timestamps are stored as Long (64-bit signed) to safely
//   hold the full unsigned 32-bit / 64-bit range without masking at every call site.
//   i32 fields (window, in_flight) map directly to Kotlin Int (same bit width, same sign).
//   u32 fields (conn_id, rtt_ms, etc.) map to Long, masked with 0xFFFFFFFFL on read.
package dev.abdulkadirozyurt.srtla.protocol

// ── SRTLA packet type constants ──────────────────────────────────────────────
// src/protocol/constants.rs :: SRTLA_TYPE_*

/** SRTLA keepalive (0x9000). */
const val SRTLA_TYPE_KEEPALIVE: Int = 0x9000
/** SRTLA cumulative ACK (0x9100). */
const val SRTLA_TYPE_ACK: Int = 0x9100
/** SRTLA registration step 1 — sender sends ID (0x9200). */
const val SRTLA_TYPE_REG1: Int = 0x9200
/** SRTLA registration step 2 — receiver echoes ID (0x9201). */
const val SRTLA_TYPE_REG2: Int = 0x9201
/** SRTLA registration step 3 — sender confirms (0x9202). */
const val SRTLA_TYPE_REG3: Int = 0x9202
/** Registration error (0x9210). */
const val SRTLA_TYPE_REG_ERR: Int = 0x9210
/** Registration no-good-path (0x9211). */
const val SRTLA_TYPE_REG_NGP: Int = 0x9211
/** Registration NAK (0x9212). */
const val SRTLA_TYPE_REG_NAK: Int = 0x9212

// ── SRT packet type constants ────────────────────────────────────────────────
// src/protocol/constants.rs :: SRT_TYPE_*

/** SRT handshake control packet (0x8000). */
const val SRT_TYPE_HANDSHAKE: Int = 0x8000
/** SRT ACK control packet (0x8002). */
const val SRT_TYPE_ACK: Int = 0x8002
/** SRT NAK (loss report) control packet (0x8003). */
const val SRT_TYPE_NAK: Int = 0x8003
/** SRT shutdown control packet (0x8005). */
const val SRT_TYPE_SHUTDOWN: Int = 0x8005
/** SRT data packet (top two bits zero → 0x0000 masked). */
const val SRT_TYPE_DATA: Int = 0x0000

// ── Packet size constants ────────────────────────────────────────────────────
// src/protocol/constants.rs :: SRTLA_ID_LEN / SRTLA_TYPE_REG*_LEN / MTU

/** Registration ID length in bytes (256). src/protocol/constants.rs:SRTLA_ID_LEN */
const val SRTLA_ID_LEN: Int = 256
/** REG1 packet length = 2-byte type + 256-byte ID. */
const val SRTLA_TYPE_REG1_LEN: Int = 2 + SRTLA_ID_LEN
/** REG2 packet length = 2-byte type + 256-byte ID. */
const val SRTLA_TYPE_REG2_LEN: Int = 2 + SRTLA_ID_LEN
/** REG3 packet length = 2-byte type only. */
const val SRTLA_TYPE_REG3_LEN: Int = 2
/** Maximum transmission unit in bytes (1500). src/protocol/constants.rs:MTU */
const val MTU: Int = 1500

// ── Timeout constants (seconds) ──────────────────────────────────────────────
// src/protocol/constants.rs :: CONN_TIMEOUT / REG2_TIMEOUT / REG3_TIMEOUT / IDLE_TIME

/** Overall connection timeout, seconds (5). */
const val CONN_TIMEOUT: Long = 5L
/** REG2 handshake timeout, seconds (4). */
const val REG2_TIMEOUT: Long = 4L
/** REG3 handshake timeout, seconds (4). */
const val REG3_TIMEOUT: Long = 4L
/** Idle keepalive interval, seconds (1). */
const val IDLE_TIME: Long = 1L

// ── Window management constants ──────────────────────────────────────────────
// src/protocol/constants.rs :: WINDOW_*

/** Minimum congestion window (1). */
const val WINDOW_MIN: Int = 1
/** Default congestion window (20). */
const val WINDOW_DEF: Int = 20
/** Maximum congestion window (60). */
const val WINDOW_MAX: Int = 60
/** Window multiplier used in calculations (1000). */
const val WINDOW_MULT: Int = 1000
/** Window decrease step (100). */
const val WINDOW_DECR: Int = 100
/** Window increase step (30). */
const val WINDOW_INCR: Int = 30

// ── Packet log / sequence tracking ──────────────────────────────────────────
// src/protocol/constants.rs :: PKT_LOG_SIZE

/** Circular log size for packet tracking (256). */
const val PKT_LOG_SIZE: Int = 256

// ── Extended KEEPALIVE constants ─────────────────────────────────────────────
// src/protocol/constants.rs :: SRTLA_KEEPALIVE_MAGIC / EXT_LEN / EXT_VERSION

/**
 * Magic marker for "Connection Info" extension in a keepalive packet (0xC01F).
 * src/protocol/constants.rs:SRTLA_KEEPALIVE_MAGIC
 */
const val SRTLA_KEEPALIVE_MAGIC: Int = 0xc01f
/** Extended keepalive packet length in bytes (38). */
const val SRTLA_KEEPALIVE_EXT_LEN: Int = 38
/** Extended keepalive protocol version (0x0001). */
const val SRTLA_KEEPALIVE_EXT_VERSION: Int = 0x0001
