// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/connection/incoming.rs
package dev.abdulkadirozyurt.srtla.connection

/** Receive-side effects of one uplink datagram, applied by the shell. */
class SrtlaIncoming {
    /** Packets to relay to the local SRT client. */
    val forwardToClient: MutableList<ByteArray> = ArrayList(2)
    val ackNumbers: MutableList<Int> = ArrayList(2)
    val nakNumbers: MutableList<Int> = ArrayList(2)
    val srtlaAckNumbers: MutableList<Int> = ArrayList(4)
    var readAny: Boolean = false
    /**
     * REG1 to transmit on this connection's socket: produced when a REG_NGP
     * arrived and the registration driver wants an immediate answer.
     */
    var reg1Send: ByteArray? = null
    /**
     * TSBPD receive delay (ms) the far-end SRT listener declared in a handshake
     * response that just crossed this link. Session-scoped: the shell stores it
     * on the shared config.
     */
    var negotiatedLatencyMs: Int? = null
}
