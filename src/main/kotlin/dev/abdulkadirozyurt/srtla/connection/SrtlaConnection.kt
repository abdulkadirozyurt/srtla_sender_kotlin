// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/connection/{mod,ack_nak}.rs
//
// Pure, socket-free per-uplink state. The shell owns the socket (see
// sender.ConnIo) and transmits whatever the builders here return. Every method
// that needs time takes the injected `now`.
//
// THREADING: not thread-safe. Only the sender's single event-loop thread
// touches a connection.
package dev.abdulkadirozyurt.srtla.connection

import dev.abdulkadirozyurt.srtla.connection.congestion.CongestionControl
import dev.abdulkadirozyurt.srtla.connection.congestion.IntRef
import dev.abdulkadirozyurt.srtla.core.CONN_TIMEOUT_MS
import dev.abdulkadirozyurt.srtla.core.NO_ACK_YET
import dev.abdulkadirozyurt.srtla.core.PROBE_LOG_MAX_AGE_MS
import dev.abdulkadirozyurt.srtla.core.PROBE_LOG_SOFT_CAP
import dev.abdulkadirozyurt.srtla.core.SILENCE_PULL_FLOOR_MS
import dev.abdulkadirozyurt.srtla.core.SILENCE_PULL_RTT_MULT
import dev.abdulkadirozyurt.srtla.core.STALL_PROBE_ONE_IN_N
import dev.abdulkadirozyurt.srtla.core.STALL_REJOIN_DWELL_MULT
import dev.abdulkadirozyurt.srtla.core.STALL_REJOIN_PROBATION_MULT
import dev.abdulkadirozyurt.srtla.core.STALL_STALE_FLOOR_MS
import dev.abdulkadirozyurt.srtla.core.STALL_STALE_RTT_MULT
import dev.abdulkadirozyurt.srtla.core.satAdd
import dev.abdulkadirozyurt.srtla.core.satMul
import dev.abdulkadirozyurt.srtla.core.satSub
import dev.abdulkadirozyurt.srtla.core.seqAfter
import dev.abdulkadirozyurt.srtla.core.seqDiff
import dev.abdulkadirozyurt.srtla.core.seqNext
import dev.abdulkadirozyurt.srtla.core.seqNormalize
import dev.abdulkadirozyurt.srtla.protocol.ConnectionInfo
import dev.abdulkadirozyurt.srtla.protocol.IDLE_TIME
import dev.abdulkadirozyurt.srtla.protocol.PKT_LOG_SIZE
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_ID_LEN
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_DEF
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_MAX
import dev.abdulkadirozyurt.srtla.protocol.WINDOW_MULT
import dev.abdulkadirozyurt.srtla.protocol.createKeepalivePacketExt
import dev.abdulkadirozyurt.srtla.protocol.createReg2Packet
import dev.abdulkadirozyurt.srtla.selection.WeakReason
import dev.abdulkadirozyurt.srtla.selection.calculateQualityMultiplier
import java.net.InetAddress
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("srtla.connection")

/**
 * Widest newly-ACKed range cleared by walking sequence numbers instead of
 * sweeping the packet log. Keeps a bogus far-future ACK cheap.
 */
private const val TARGETED_REMOVAL_LIMIT: Int = 64

class SrtlaConnection(
    val connId: Long,
    val label: String,
    /** Source IP the shell binds this uplink's socket to. */
    val localIp: InetAddress,
    now: Long,
) {
    var connected: Boolean = false
    var window: Int = WINDOW_DEF * WINDOW_MULT
    var inFlightPackets: Int = 0

    /** Payload in flight on this link: sequence → send time (ms). */
    val packetLog: HashMap<Int, Long> = HashMap(PKT_LOG_SIZE)

    /**
     * Send times of this link's outstanding duplicate probes, kept apart from
     * [packetLog] on purpose. A cumulative SRT ACK sweeps the packet log of
     * every link, and a probe's twin travelled the healthy link, so a shared log
     * lost the probe before its own (slower) SRTLA ACK arrived. Entries here are
     * immune to that sweep, expire by age, and never count as in-flight payload
     * or attract NAK attribution.
     */
    val probeLog: HashMap<Int, Long> = HashMap()

    /**
     * Highest cumulatively ACKed sequence, or [NO_ACK_YET]. Compare it with the
     * serial helpers in core.Seq, never with `<`/`>`: SRT sequences wrap.
     */
    var highestAckedSeq: Int = NO_ACK_YET

    var lastReceived: Long? = null
    var lastSent: Long? = null
    var lastKeepaliveSent: Long? = null

    /**
     * nowMs of the last delivery proof: an earned ACK or a keepalive round trip.
     * Never stamped on generic inbound bytes, so a link that echoes traffic while
     * its data path is dead still goes stale. 0 = no proof yet.
     */
    var lastAckOrRttSampleMs: Long = 0L

    /** Per-select routing flag set by applyStallGate. Selection penalty only. */
    var stallGated: Boolean = false

    /** nowMs when the stall latch engaged; 0 = not latched. */
    internal var stallLatchedSinceMs: Long = 0L
    /** Start of the current run of fresh proof on a latched link; 0 = none. */
    internal var stallRecoverySinceMs: Long = 0L
    /** Cumulative stall-latch engagements; survives soft resets. */
    internal var stallGateEventsCount: Long = 0L
    /** Rolling counter for the 1-in-N duplicate-probe cadence. */
    internal var stallProbeCounter: Int = 0
    /** nowMs when the stall latch last released; 0 = never. */
    internal var stallReleasedAtMs: Long = 0L
    /** Rejoin-dwell multiplier; 0 and 1 both mean 1x. */
    internal var stallRejoinBackoffRaw: Int = 0
    /** Start of the post-rejoin share ramp; 0 = no ramp running. */
    internal var stallRejoinRampStartMs: Long = 0L
    internal var stallRejoinRampMs: Long = 0L
    /** Whether the running ramp was armed by the stall gate. */
    internal var stallRejoinRampFromStallGate: Boolean = false

    /** Elected to carry the payload while every link is quality-gated. */
    internal var soleCarrier: Boolean = false
    internal var soleCarrierSinceMs: Long = 0L
    internal var soleCarrierExcluded: Boolean = false
    /** Cumulative handovers of the sole-carrier role to this link from another. */
    internal var soleCarrierElectionsCount: Long = 0L

    /** Fast transient tier: loaded and totally silent for the pull window. */
    var silencePulled: Boolean = false
    internal var silencePullsCount: Long = 0L

    /** Per-link liveness timeout, refreshed from the config every selection pass. */
    internal var connTimeoutMs: Long = CONN_TIMEOUT_MS
    /** One-way delivery deadline from the peer's handshake; 0 until known. */
    internal var delayBudgetMs: Int = 0

    val rtt: RttTracker = RttTracker()
    val congestion: CongestionControl = CongestionControl()
    val bitrate: BitrateTracker = BitrateTracker(now)
    val reconnection: ReconnectionState = ReconnectionState(startupGraceDeadlineMs = now + STARTUP_GRACE_MS)
    var qualityCache: CachedQuality = CachedQuality()
    val batchSender: BatchSender = BatchSender()

    var phase: LinkPhase = LinkPhase.Registering

    /** Latest weak-link classifier verdict (housekeeping). */
    var weak: Boolean = false
    /** Why the classifier called this link weak; selection needs the reason. */
    var weakReason: WeakReason = WeakReason.HEALTHY
    /** Per-select flag: held out of the payload rotation on quality grounds. */
    var qualityExcluded: Boolean = false
    /** Latest CC state is BackingOff. Telemetry only, not a routing gate. */
    var ccBackingOff: Boolean = false
    /** Latest CC soft-cap target in bps; 0 = no signal. */
    var ccTargetBps: Long = 0L
    /** Sustained loss latch from the link CC; drives demotion and the loss gate. */
    var lossDegraded: Boolean = false
    /** Normalised operator weight (1 = unweighted). Survives resets. */
    var linkWeight: Int = LINK_WEIGHT_MIN

    // ── Scoring ──────────────────────────────────────────────────────────────

    fun weightMultiplier(): Float = linkWeightMultiplier(window, linkWeight)

    /**
     * C's select_conn(): window / (in_flight + 1). Queued-but-unflushed packets
     * count as in-flight, so routing a packet lowers its own link's score at once.
     */
    fun getScore(): Int {
        if (!connected) return -1
        val totalInFlight = inFlightPackets.satAdd(batchSender.queuedCount())
        val denom = maxOf(totalInFlight.satAdd(1), 1)
        return window / denom
    }

    // ── Batched sending ──────────────────────────────────────────────────────

    /** Queue a data packet. Returns true when the batch must be flushed. */
    fun queueDataPacket(data: ByteArray, len: Int, seq: Int?, sendTimeMs: Long): Boolean {
        bitrate.updateOnSend(len.toLong())
        return batchSender.queuePacket(data, len, seq, sendTimeMs)
    }

    fun queueDataPacket(data: ByteArray, seq: Int?, sendTimeMs: Long): Boolean =
        queueDataPacket(data, data.size, seq, sendTimeMs)

    /**
     * Queue a duplicate probe: a redundant copy of a packet whose unique copy went
     * out on another link. Recorded in [probeLog] and queued untracked, so it
     * survives the cumulative-ACK sweep, never inflates in-flight, and a NAK for
     * the sequence stays attributed to the link that carried the stream.
     */
    fun queueProbePacket(data: ByteArray, len: Int, seq: Int, sendTimeMs: Long): Boolean {
        bitrate.updateOnSend(len.toLong())
        recordProbe(seq, sendTimeMs)
        return batchSender.queuePacket(data, len, null, sendTimeMs)
    }

    fun queueProbePacket(data: ByteArray, seq: Int, sendTimeMs: Long): Boolean =
        queueProbePacket(data, data.size, seq, sendTimeMs)

    private fun recordProbe(seq: Int, sendTimeMs: Long) {
        if (probeLog.size >= PROBE_LOG_SOFT_CAP) {
            val cutoff = sendTimeMs.satSub(PROBE_LOG_MAX_AGE_MS)
            probeLog.entries.removeIf { it.value < cutoff }
            // Still full of live entries: probed faster than it can answer.
            if (probeLog.size >= PROBE_LOG_SOFT_CAP) probeLog.clear()
        }
        probeLog[seq] = sendTimeMs
    }

    fun needsBatchFlush(nowMs: Long): Boolean = batchSender.needsTimeFlush(nowMs)

    fun hasQueuedPackets(): Boolean = batchSender.hasQueuedPackets()

    /**
     * Drain the batch queue for transmission, registering each tracked packet as
     * in-flight. Registration is optimistic and covers the whole batch: the shell
     * must put the link into recovery on any send error, which clears it again.
     * Does not stamp lastSent; the shell does that once I/O is confirmed.
     */
    fun takeBatch(now: Long): List<DrainedPacket> {
        val batch = batchSender.drain(now)
        for (p in batch) {
            val s = p.seq
            if (s != null) registerPacket(s, p.queueTimeMs)
        }
        return batch
    }

    // ── Keepalive / registration builders ────────────────────────────────────

    /**
     * Build an extended keepalive and record that it was sent. State is updated
     * optimistically: a dropped keepalive is re-sent on the next tick.
     */
    fun keepalivePacket(now: Long): ByteArray {
        val info = ConnectionInfo(
            connId = connId and 0xFFFF_FFFFL,
            window = window,
            inFlight = inFlightPackets,
            rttMs = rtt.kalmanRtt.value.toLong().coerceIn(0L, 0xFFFF_FFFFL),
            nakCount = congestion.nakCount.toLong() and 0xFFFF_FFFFL,
            bitrateBytesSec = (bitrate.currentBitrateBps / 8.0).toLong().coerceIn(0L, 0xFFFF_FFFFL),
        )
        val pkt = createKeepalivePacketExt(info, now)
        lastSent = now
        lastKeepaliveSent = now
        if (!rtt.waitingForKeepaliveResponse &&
            (rtt.lastRttMeasurementMs == 0L || now.satSub(rtt.lastRttMeasurementMs) > 3000L)
        ) {
            rtt.recordKeepaliveSent(now)
        }
        return pkt
    }

    /** Stamp lastSent after the shell transmits an out-of-band packet. */
    fun noteSent(now: Long) {
        lastSent = now
    }

    /** Build a REG2 probe and arm the startup grace window. */
    fun probeReg2Packet(probeId: ByteArray, now: Long): ByteArray {
        require(probeId.size == SRTLA_ID_LEN)
        val pkt = createReg2Packet(probeId)
        reconnection.startupGraceDeadlineMs = now + STARTUP_GRACE_MS
        return pkt
    }

    // ── RTT accessors ────────────────────────────────────────────────────────

    fun isRttStable(): Boolean = rtt.isStable()

    /** Smoothed RTT, clamped at zero (the Kalman can overshoot negative). */
    fun getSmoothRttMs(): Double = maxOf(rtt.kalmanRtt.value, 0.0)

    /** RTT trend in ms/sample. Positive = rising. */
    fun getRttVelocity(): Double = rtt.kalmanRtt.velocity

    fun getRttMinMs(): Double = rtt.rttMinMs

    fun getRttJitterMs(): Double = rtt.rttJitterMs

    fun queueBuildingSuspected(): Boolean = rtt.queueBuildingSuspected()

    fun needsRttMeasurement(nowMs: Long): Boolean =
        rtt.needsMeasurement(connected, reconnection.connectionEstablishedMs, nowMs)

    /** Keepalive every IDLE_TIME (1 s) on every connected link. */
    fun needsKeepalive(nowMs: Long): Boolean {
        if (!connected) return false
        val last = lastKeepaliveSent ?: return true
        return nowMs.satSub(last) >= IDLE_TIME * 1000L
    }

    fun performWindowRecovery(nowMs: Long) {
        val ref = IntRef(window)
        congestion.performWindowRecovery(ref, connected, rtt.kalmanRtt.velocity, label, nowMs)
        window = ref.value
    }

    // ── Phase machine ────────────────────────────────────────────────────────

    /** Record an RTT probe and promote Warming → Live once enough arrive. */
    fun recordRttProbe() {
        val p = phase
        if (p is LinkPhase.Warming) {
            val probes = p.rttProbes + 1
            phase = if (probes >= WARMING_RTT_PROBES) {
                log.fine { "$label: warming complete, transitioning to Live" }
                LinkPhase.Live
            } else {
                p.copy(rttProbes = probes)
            }
        }
    }

    /**
     * Drive phase transitions (housekeeping). Degraded stays schedulable: removing
     * a link starves it of the ACK traffic that proves its recovery.
     */
    fun updatePhase(nowMs: Long) {
        val degradedQualityThreshold = 0.5
        val degradedNakBurstThreshold = 5
        val nakDegraded = qualityCache.multiplier < degradedQualityThreshold &&
            congestion.nakBurstCount >= degradedNakBurstThreshold
        val nakRecovered = qualityCache.multiplier >= degradedQualityThreshold &&
            congestion.nakBurstCount < degradedNakBurstThreshold

        val p = phase
        when {
            p is LinkPhase.Warming && nowMs.satSub(p.enteredMs) >= WARMING_TIMEOUT_MS -> {
                log.fine { "$label: warming timeout (${WARMING_TIMEOUT_MS}ms), auto-promoting to Live" }
                phase = LinkPhase.Live
            }
            p is LinkPhase.Live && (nakDegraded || lossDegraded) -> {
                log.fine { "$label: Live -> Degraded (quality=${"%.2f".format(qualityCache.multiplier)}, nak_burst=${congestion.nakBurstCount}, loss_degraded=$lossDegraded)" }
                phase = LinkPhase.Degraded
            }
            p is LinkPhase.Degraded && nakRecovered && !lossDegraded -> {
                log.fine { "$label: Degraded -> Live (quality=${"%.2f".format(qualityCache.multiplier)})" }
                phase = LinkPhase.Live
            }
        }
    }

    fun isSchedulable(): Boolean = phase.isSchedulable()

    fun phaseWeight(): Double = phase.weight()

    // ── Stall gate ───────────────────────────────────────────────────────────

    /**
     * Effective staleness window: RTT-adaptive between STALL_STALE_FLOOR_MS and
     * [ceilingMs]. No RTT baseline falls back to the ceiling; a ceiling configured
     * below the floor wins.
     */
    fun effectiveStallStaleMs(ceilingMs: Long): Long {
        val srtt = getSmoothRttMs()
        if (srtt <= 0.0) return ceilingMs
        return minOf(maxOf(srtt.toLong().satMul(STALL_STALE_RTT_MULT), STALL_STALE_FLOOR_MS), ceilingMs)
    }

    /**
     * Stall signal (pure read): connected, backlog at or above [minInFlight], and
     * last delivery proof older than the effective window. A link with no proof
     * yet is never stalled.
     */
    fun isStalled(nowMs: Long, minInFlight: Int, staleCeilingMs: Long): Boolean =
        connected &&
            inFlightPackets >= minInFlight &&
            lastAckOrRttSampleMs != 0L &&
            nowMs.satSub(lastAckOrRttSampleMs) >= effectiveStallStaleMs(staleCeilingMs)

    /**
     * Drive the asymmetric stall latch: quick to drop, conservative to rejoin.
     * Rejoining needs an uninterrupted run of fresh, timely proof spanning the
     * (backed-off) rejoin dwell. A silence-pulled link whose proof also goes fully
     * stale escalates into the latch.
     */
    fun updateStallLatch(nowMs: Long, minInFlight: Int, staleCeilingMs: Long) {
        val proofFullyStale = lastAckOrRttSampleMs != 0L &&
            nowMs.satSub(lastAckOrRttSampleMs) >= effectiveStallStaleMs(staleCeilingMs)
        if (isStalled(nowMs, minInFlight, staleCeilingMs) || (silencePulled && proofFullyStale)) {
            if (stallLatchedSinceMs == 0L) {
                val probationMs = effectiveStallStaleMs(staleCeilingMs).satMul(STALL_REJOIN_PROBATION_MULT)
                stallRejoinBackoffRaw = stallRejoinBackoffNext(
                    stallRejoinBackoffRaw,
                    stallReleasedAtMs,
                    nowMs.satSub(stallReleasedAtMs),
                    probationMs,
                )
                log.fine { "$label: stall latch engaged, rejoin dwell now ${stallRejoinBackoffRaw}x" }
                stallLatchedSinceMs = nowMs
                stallGateEventsCount++
            }
            stallRecoverySinceMs = 0L
            return
        }
        if (stallLatchedSinceMs == 0L) return

        val staleMs = effectiveStallStaleMs(staleCeilingMs)
        val proofFresh = lastAckOrRttSampleMs != 0L &&
            nowMs.satSub(lastAckOrRttSampleMs) < staleMs &&
            deliveryProofIsTimely(getSmoothRttMs(), delayBudgetMs)
        if (!proofFresh) {
            stallRecoverySinceMs = 0L
            return
        }
        if (stallRecoverySinceMs == 0L) stallRecoverySinceMs = nowMs
        val baseDwellMs = staleMs.satMul(STALL_REJOIN_DWELL_MULT)
        // Only the wait scales with the backoff; the ramp stays at the base dwell.
        val dwellMs = baseDwellMs.satMul(maxOf(stallRejoinBackoffRaw, 1).toLong())
        if (nowMs.satSub(stallRecoverySinceMs) >= dwellMs) {
            log.fine { "$label: stall latch released after sustained proof (${dwellMs}ms dwell), ramping share back over ${baseDwellMs}ms" }
            stallLatchedSinceMs = 0L
            stallRecoverySinceMs = 0L
            stallReleasedAtMs = nowMs
            armRejoinRamp(nowMs, baseDwellMs, true)
        }
    }

    /**
     * Start the post-rejoin share ramp unless one is already running. An
     * in-progress ramp is never restarted, or a flapping verdict would pin the
     * link at the ramp floor.
     */
    internal fun armRejoinRamp(nowMs: Long, rampMs: Long, fromStallGate: Boolean) {
        if (rejoinRampMultiplier(nowMs) < 1.0) return
        stallRejoinRampStartMs = nowMs
        stallRejoinRampMs = rampMs
        stallRejoinRampFromStallGate = fromStallGate
    }

    fun isQualityExcluded(): Boolean = qualityExcluded

    /**
     * Drop every flag owned by the Enhanced quality gates. Classic must clear them
     * because the mode switches at runtime. The rejoin ramp is kept.
     */
    internal fun clearQualityGateState() {
        qualityExcluded = false
        soleCarrier = false
        soleCarrierExcluded = false
        soleCarrierSinceMs = 0L
    }

    fun isSoleCarrier(): Boolean = soleCarrier

    fun isSoleCarrierExcluded(): Boolean = soleCarrierExcluded

    fun soleCarrierElections(): Long = soleCarrierElectionsCount

    /** Fraction of its natural score this link competes with right now. */
    fun rejoinRampMultiplier(nowMs: Long): Double =
        rejoinRampMultiplier(stallRejoinRampStartMs, stallRejoinRampMs, nowMs)

    fun stallRejoinBackoff(): Int = maxOf(stallRejoinBackoffRaw, 1)

    fun isRejoinRamping(nowMs: Long): Boolean = rejoinRampMultiplier(nowMs) < 1.0

    fun stallLatched(): Boolean = stallLatchedSinceMs != 0L

    /**
     * Clear the latch without touching the event counter (guard disabled at
     * runtime). Drops only ramps this guard armed.
     */
    internal fun clearStallLatch() {
        stallLatchedSinceMs = 0L
        stallRecoverySinceMs = 0L
        stallReleasedAtMs = 0L
        stallRejoinBackoffRaw = 0
        if (stallRejoinRampFromStallGate) {
            stallRejoinRampStartMs = 0L
            stallRejoinRampMs = 0L
            stallRejoinRampFromStallGate = false
        }
    }

    fun isStallGated(): Boolean = stallGated

    fun stallGateEvents(): Long = stallGateEventsCount

    /** True once per STALL_PROBE_ONE_IN_N calls; one call per routed packet. */
    fun stallProbeDue(): Boolean {
        stallProbeCounter++
        if (stallProbeCounter >= STALL_PROBE_ONE_IN_N) {
            stallProbeCounter = 0
            return true
        }
        return false
    }

    /**
     * Silence-pull window: max(floor, 2 x srtt), capped at the effective
     * staleness window so the two tiers never disagree about who acts.
     */
    fun silencePullWindowMs(staleCeilingMs: Long): Long {
        val srtt = getSmoothRttMs()
        val base = if (srtt <= 0.0) {
            SILENCE_PULL_FLOOR_MS
        } else {
            maxOf(srtt.toLong().satMul(SILENCE_PULL_RTT_MULT), SILENCE_PULL_FLOOR_MS)
        }
        return minOf(base, effectiveStallStaleMs(staleCeilingMs))
    }

    /**
     * Fast silence signal (pure read): a connected, loaded link that has received
     * nothing at all for the pull window. Keyed on any inbound byte, unlike the
     * latch, because the pull clears the moment the link speaks.
     */
    fun isBrieflySilent(nowMs: Long, minInFlight: Int, staleCeilingMs: Long): Boolean {
        if (!connected || inFlightPackets < minInFlight) return false
        val lr = lastReceived ?: return false
        return nowMs.satSub(lr) >= silencePullWindowMs(staleCeilingMs)
    }

    /**
     * Drive the silence-pull flag. Engages on loaded-and-silent; releases only
     * when the link actually speaks (or disconnects), not when its backlog drains.
     */
    internal fun updateSilencePull(nowMs: Long, minInFlight: Int, staleCeilingMs: Long) {
        if (isBrieflySilent(nowMs, minInFlight, staleCeilingMs)) {
            if (!silencePulled) {
                silencePullsCount++
                log.fine { "$label: silence pull engaged" }
            }
            silencePulled = true
            return
        }
        if (!silencePulled) return
        val window = silencePullWindowMs(staleCeilingMs)
        val spoke = lastReceived?.let { nowMs.satSub(it) < window } ?: false
        if (spoke || !connected) silencePulled = false
    }

    fun silencePulls(): Long = silencePullsCount

    // ── Liveness ─────────────────────────────────────────────────────────────

    /** Whether this link has gone silent past its liveness timeout. */
    fun isTimedOut(nowMs: Long): Boolean {
        if (!connected) {
            if (reconnection.connectionEstablishedMs == 0L && nowMs < reconnection.startupGraceDeadlineMs) {
                return false
            }
            val lr = lastReceived ?: return true
            return nowMs.satSub(lr) >= connTimeoutMs
        }
        val lr = lastReceived ?: return false
        return nowMs.satSub(lr) >= connTimeoutMs
    }

    /**
     * Clear state accumulated before REG3 (phantom in-flight, early NAK penalty)
     * and enter the Warming phase.
     */
    fun clearPreRegistrationState(nowMs: Long) {
        if (packetLog.isNotEmpty() || congestion.nakCount > 0) {
            log.fine { "$label: clearing pre-registration state (${packetLog.size} in-flight, ${congestion.nakCount} NAKs)" }
        }
        packetLog.clear()
        probeLog.clear()
        inFlightPackets = 0
        highestAckedSeq = NO_ACK_YET
        congestion.reset()
        batchSender.reset()
        qualityCache = CachedQuality()
        phase = LinkPhase.Warming(rttProbes = 0, enteredMs = nowMs)
    }

    private fun resetCoreState() {
        connected = false
        window = WINDOW_DEF * WINDOW_MULT
        inFlightPackets = 0
        packetLog.clear()
        probeLog.clear()
        highestAckedSeq = NO_ACK_YET
        batchSender.reset()
        phase = LinkPhase.Registering
        // No delivery proof survives a reset; the event counters do.
        lastAckOrRttSampleMs = 0L
        stallGated = false
        stallLatchedSinceMs = 0L
        stallRecoverySinceMs = 0L
        stallProbeCounter = 0
        stallReleasedAtMs = 0L
        stallRejoinBackoffRaw = 0
        stallRejoinRampStartMs = 0L
        stallRejoinRampMs = 0L
        stallRejoinRampFromStallGate = false
        soleCarrier = false
        soleCarrierSinceMs = 0L
        soleCarrierExcluded = false
        silencePulled = false
    }

    /** Soft reset (C: last_rcvd = 1): clears packet state, keeps CC/bitrate stats. */
    fun markForRecovery() {
        lastReceived = null
        lastKeepaliveSent = null
        rtt.lastKeepaliveSentMs = 0L
        rtt.waitingForKeepaliveResponse = false
        resetCoreState()
        reconnection.startupGraceDeadlineMs = 0L
    }

    fun timeSinceLastNakMs(nowMs: Long): Long? = congestion.timeSinceLastNakMs(nowMs)

    fun totalNakCount(): Int = congestion.nakCount

    fun nakBurstCount(): Int = congestion.nakBurstCount

    fun connectionEstablishedMs(): Long = reconnection.connectionEstablishedMs

    /** Cached quality multiplier, recalculated every 50 ms. */
    fun getCachedQualityMultiplier(currentTimeMs: Long): Double {
        if (currentTimeMs.satSub(qualityCache.lastCalculatedMs) >= QUALITY_CACHE_INTERVAL_MS) {
            qualityCache.multiplier = calculateQualityMultiplier(this, currentTimeMs)
            qualityCache.lastCalculatedMs = currentTimeMs
        }
        return qualityCache.multiplier
    }

    fun shouldAttemptReconnect(nowMs: Long): Boolean = reconnection.shouldAttemptReconnect(nowMs)

    fun recordReconnectAttempt(nowMs: Long) = reconnection.recordAttempt(label, nowMs)

    fun markReconnectSuccess() = reconnection.markSuccess(label)

    fun calculateBitrate(nowMs: Long) = bitrate.calculate(nowMs)

    fun currentBitrateMbps(): Double = bitrate.mbps()

    /** Pick the batch regime from the observed bitrate (housekeeping). */
    fun recomputeBatchRegime() {
        batchSender.setRegime(BatchRegime.fromBps(bitrate.currentBitrateBps))
    }

    /**
     * Full reset after the shell replaced this link's socket. The attempt count
     * survives: only REG3 proves the link is back.
     */
    fun resetForReconnect(now: Long) {
        lastReceived = null
        resetCoreState()
        congestion.reset()
        rtt.reset()
        bitrate.reset(now)
        reconnection.lastReconnectAttemptMs = now
    }

    // ── ACK / NAK (ack_nak.rs) ───────────────────────────────────────────────

    /** Register a packet as in-flight. */
    fun registerPacket(seq: Int, sendTimeMs: Long) {
        packetLog[seq] = sendTimeMs
        inFlightPackets = packetLog.size
    }

    /**
     * SRT cumulative ACK: clear every packet at or before [ack] in serial order.
     *
     * [ownsAckedSeq] says whether this link carried the unique copy of [ack] and
     * gates the RTT sample: a cumulative ACK is a flow-level signal that every
     * link prunes on, but only proves delivery by the link that carried it.
     */
    fun handleSrtAck(ackRaw: Int, nowMs: Long, ownsAckedSeq: Boolean) {
        val ack = seqNormalize(ackRaw)
        val oldHighest = highestAckedSeq
        val firstAck = oldHighest == NO_ACK_YET
        if (!firstAck && !seqAfter(ack, oldHighest)) return

        val ackSendTimeMs = packetLog[ack]
        highestAckedSeq = ack

        val advance = if (firstAck) Int.MAX_VALUE else seqDiff(ack, oldHighest)
        if (advance <= TARGETED_REMOVAL_LIMIT) {
            var seq = oldHighest
            repeat(advance) {
                seq = seqNext(seq)
                packetLog.remove(seq)
            }
        } else {
            packetLog.entries.removeIf { !seqAfter(it.key, ack) }
        }
        inFlightPackets = packetLog.size

        if (ownsAckedSeq && ackSendTimeMs != null) rtt.recordRoundTrip(ackSendTimeMs, nowMs)
    }

    /** NAK for a specific sequence. Returns true if this link carried it. */
    fun handleNak(seq: Int, nowMs: Long): Boolean {
        val found = packetLog.remove(seq) != null
        if (found) {
            inFlightPackets = packetLog.size
            val ref = IntRef(window)
            congestion.handleNak(ref, seq, label, nowMs)
            window = ref.value
        }
        return found
    }

    /**
     * SRTLA ACK for a specific sequence. A probe answered on this link proves
     * delivery and yields a round trip but must not move the window. A payload
     * packet is earned delivery proof, an RTT sample and window growth.
     */
    fun handleSrtlaAckSpecific(seq: Int, classicMode: Boolean, nowMs: Long): Boolean {
        val probeSent = probeLog.remove(seq)
        if (probeSent != null) {
            lastAckOrRttSampleMs = nowMs
            rtt.recordRoundTrip(probeSent, nowMs)
            return true
        }
        val sentMs = packetLog.remove(seq) ?: return false
        inFlightPackets = packetLog.size
        lastAckOrRttSampleMs = nowMs
        rtt.recordRoundTrip(sentMs, nowMs)
        val ref = IntRef(window)
        if (classicMode) {
            congestion.handleSrtlaAckSpecificClassic(ref, inFlightPackets, seq, label)
        } else {
            congestion.handleSrtlaAckEnhanced(ref, inFlightPackets, label, nowMs)
        }
        window = ref.value
        return true
    }

    /** Global +1 window for links that have received data (C: last_rcvd != 0). */
    fun handleSrtlaAckGlobal() {
        if (connected && lastReceived != null) {
            window = minOf(window + 1, WINDOW_MAX * WINDOW_MULT)
        }
    }

    override fun toString(): String = "SrtlaConnection($label)"
}
