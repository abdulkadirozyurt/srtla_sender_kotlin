// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/sender/selection/edpf.rs
//
// EDPF (Earliest Delivery Path First) link selection.
// Selects the link with the lowest predicted arrival time, considering
// in-flight data, link capacity, loss rate (from quality multiplier), and base RTT.
// Kalman-smoothed RTT is used as propagation delay estimate.
package dev.abdulkadirozyurt.srtla.sender.selection

import dev.abdulkadirozyurt.srtla.connection.SrtlaConnection

// src/sender/selection/edpf.rs
/** SRT payload packet size in bytes. */
const val SRT_PKT_SIZE: Int = 1316   // edpf.rs:SRT_PKT_SIZE

/**
 * Compute predicted arrival time for a connection.
 *
 * Returns null if the connection lacks valid capacity or RTT data.
 * Mirrors Rust `predicted_arrival` in src/sender/selection/edpf.rs.
 *
 * Formula:
 *   effective_capacity = (bitrate_bps / 8) × (1 - loss)
 *   predicted_arrival  = (in_flight_bytes + pkt_size) / effective_capacity + propagation_s
 */
fun edpfPredictedArrival(conn: SrtlaConnection, pktSize: Int = SRT_PKT_SIZE): Double? {
    if (!conn.connected) return null

    val bitrateBps = conn.bitrate.currentBitrateBps
    if (bitrateBps <= 0.0) return null

    val capacityBytesPerSec = bitrateBps / 8.0

    // Loss estimated from quality multiplier (mirrors Rust quality_cache.multiplier)
    // We compute the uncached quality multiplier for arrival estimation
    val loss = (1.0 - calculateQualityMultiplier(conn, System.currentTimeMillis()))
        .coerceIn(0.0, 0.99)
    val effectiveCapacity = capacityBytesPerSec * (1.0 - loss)
    if (effectiveCapacity <= 0.0) return null

    val inFlightBytes = (conn.inFlightPackets.coerceAtLeast(0) * SRT_PKT_SIZE).toDouble()

    // Use Kalman-smoothed RTT as propagation delay; fall back to rttMinMs
    // Mirrors Rust: conn.rtt.kalman_rtt.value() or rtt_min_ms
    val smoothRtt = conn.getSmoothRttMs()
    val propagationS = if (smoothRtt > 0.0) smoothRtt / 1000.0
                       else conn.getRttMinMs() / 1000.0

    return (inFlightBytes + pktSize.toDouble()) / effectiveCapacity + propagationS
}

/**
 * Select the connection with lowest predicted arrival time from all connections.
 * Mirrors Rust `edpf::select_from`.
 */
fun edpfSelectFrom(conns: List<SrtlaConnection>, pktSize: Int = SRT_PKT_SIZE): Int? {
    var bestIdx: Int? = null
    var bestArrival = Double.MAX_VALUE
    for ((i, conn) in conns.withIndex()) {
        val arrival = edpfPredictedArrival(conn, pktSize) ?: continue
        if (arrival < bestArrival) {
            bestArrival = arrival
            bestIdx = i
        }
    }
    return bestIdx
}

/**
 * Select the connection with lowest predicted arrival time from a filtered subset.
 * [indices] contains the indices of candidate connections in [conns].
 * Mirrors Rust `edpf::select_from_indices`.
 */
fun edpfSelectFromIndices(
    conns: List<SrtlaConnection>,
    indices: List<Int>,
    pktSize: Int = SRT_PKT_SIZE,
): Int? {
    var bestIdx: Int? = null
    var bestArrival = Double.MAX_VALUE
    for (i in indices) {
        if (i >= conns.size) continue
        val arrival = edpfPredictedArrival(conns[i], pktSize) ?: continue
        if (arrival < bestArrival) {
            bestArrival = arrival
            bestIdx = i
        }
    }
    return bestIdx
}
