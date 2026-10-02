// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-protocol/src/constants.rs
//
// UNSIGNED / ENDIANNESS POLICY (applies to the entire protocol package):
//   Packet-type values are 16-bit unsigned (0x9000..0x9212, 0x8000..0x8005),
//   stored as Int with explicit masking.
//   Sequence numbers are carried as Int holding the raw 32-bit wire word: a data
//   sequence has its MSB clear, so it is non-negative; a corrupt ACK/NAK word may
//   carry the MSB and read negative, which callers normalize (see core.Seq).
//   u32 telemetry fields (conn_id, rtt_ms, ...) are Long, masked on read.
//   Timestamps are Long milliseconds.
package dev.abdulkadirozyurt.srtla.protocol

// ── SRTLA packet types ───────────────────────────────────────────────────────

const val SRTLA_TYPE_KEEPALIVE: Int = 0x9000
const val SRTLA_TYPE_ACK: Int = 0x9100
const val SRTLA_TYPE_REG1: Int = 0x9200
const val SRTLA_TYPE_REG2: Int = 0x9201
const val SRTLA_TYPE_REG3: Int = 0x9202
const val SRTLA_TYPE_REG_ERR: Int = 0x9210
const val SRTLA_TYPE_REG_NGP: Int = 0x9211
const val SRTLA_TYPE_REG_NAK: Int = 0x9212

// ── SRT packet types ─────────────────────────────────────────────────────────

const val SRT_TYPE_HANDSHAKE: Int = 0x8000
const val SRT_TYPE_ACK: Int = 0x8002
const val SRT_TYPE_NAK: Int = 0x8003
const val SRT_TYPE_SHUTDOWN: Int = 0x8005
const val SRT_TYPE_DATA: Int = 0x0000

// ── SRT handshake layout ─────────────────────────────────────────────────────
// For reading the negotiated TSBPD latency off the wire (parseSrtHandshakeLatency).
// Field names and offsets follow libsrt's CHandShake / SrtHSRequest.

/** SRT control-packet header length; the handshake body follows it. */
const val SRT_CONTROL_HEADER_LEN: Int = 16

/** Length of the UDT handshake body (CHandShake::m_iContentSize). */
const val SRT_HANDSHAKE_CIF_LEN: Int = 48

/** Handshake version that carries SRT extension blocks. */
const val SRT_HS_VERSION_5: Int = 5

/** URQ_CONCLUSION: the only handshake phase that carries extensions. */
const val SRT_HS_REQTYPE_CONCLUSION: Int = -1

/** CHandShake::HS_EXT_HSREQ bit in the handshake's extension field. */
const val SRT_HS_EXT_FLAG_HSREQ: Int = 1

/** Extension-block commands: HSREQ (initiator), HSRSP (negotiated answer). */
const val SRT_HS_EXT_CMD_HSREQ: Int = 1
const val SRT_HS_EXT_CMD_HSRSP: Int = 2

/** Words in an HSREQ/HSRSP block: version, flags, latency. */
const val SRT_HS_EXT_HSREQ_WORDS: Int = 3

/** TSBPD flags in the HSREQ/HSRSP flags word. */
const val SRT_HS_OPT_TSBPDSND: Int = 1 shl 0
const val SRT_HS_OPT_TSBPDRCV: Int = 1 shl 1

// ── Packet sizes ─────────────────────────────────────────────────────────────

const val SRTLA_ID_LEN: Int = 256
const val SRTLA_TYPE_REG1_LEN: Int = 2 + SRTLA_ID_LEN
const val SRTLA_TYPE_REG2_LEN: Int = 2 + SRTLA_ID_LEN
const val SRTLA_TYPE_REG3_LEN: Int = 2
const val MTU: Int = 1500

// ── Timeouts (seconds) ───────────────────────────────────────────────────────

const val CONN_TIMEOUT: Long = 5L
const val REG2_TIMEOUT: Long = 4L
const val REG3_TIMEOUT: Long = 4L
const val IDLE_TIME: Long = 1L

// ── Window management ────────────────────────────────────────────────────────

const val WINDOW_MIN: Int = 1
const val WINDOW_DEF: Int = 20
const val WINDOW_MAX: Int = 60
const val WINDOW_MULT: Int = 1000
const val WINDOW_DECR: Int = 100
const val WINDOW_INCR: Int = 30

const val PKT_LOG_SIZE: Int = 256

// ── Extended KEEPALIVE ───────────────────────────────────────────────────────

/** "Connection Info" marker in an extended keepalive (0xC01F). */
const val SRTLA_KEEPALIVE_MAGIC: Int = 0xc01f
const val SRTLA_KEEPALIVE_EXT_LEN: Int = 38
const val SRTLA_KEEPALIVE_EXT_VERSION: Int = 0x0001
