// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/sender/housekeeping.rs (tests), src/sender/rehome.rs (tests)
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.core.nowMs
import dev.abdulkadirozyurt.srtla.registration.RegistrationManager
import dev.abdulkadirozyurt.srtla.sender.AllLinksFailedException
import dev.abdulkadirozyurt.srtla.protocol.SRTLA_ID_LEN
import dev.abdulkadirozyurt.srtla.sender.ConnIoMap
import dev.abdulkadirozyurt.srtla.sender.GLOBAL_TIMEOUT_MS
import dev.abdulkadirozyurt.srtla.sender.NoopReaderRegistry
import dev.abdulkadirozyurt.srtla.sender.REHOME_MIN_INTERVAL_MS
import dev.abdulkadirozyurt.srtla.sender.RehomeGate
import dev.abdulkadirozyurt.srtla.sender.SenderState
import dev.abdulkadirozyurt.srtla.sender.StubResolver
import dev.abdulkadirozyurt.srtla.sender.handleHousekeeping
import dev.abdulkadirozyurt.srtla.sender.receiverMoved
import dev.abdulkadirozyurt.srtla.sender.tryRehome
import dev.abdulkadirozyurt.srtla.testkit.*
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

fun registerHousekeepingRehomeTests() {
    suite("housekeeping") {
        test("dead_reader_is_restarted_for_active_connection") {
            // SKIPPED (JVM): On the JVM, there are no explicit reader tasks; the Selector
            // drives reads directly. A dead reader task pattern does not apply here.
        }

        test("all_failed_timeout_measures_elapsed_since_failure") {
            val connections = createTestConnections(2)
            val state = SenderState(
                connections = connections,
                connIo = createTestConnIoMap(connections),
                reg = RegistrationManager(),
            )
            // Models a stream that was established and then lost every link.
            state.reg.hasConnected = true

            val t0 = nowMs()

            // Drop all uplinks. Stamp the last reconnect attempt past the whole test
            // window so housekeeping reaches the timeout branch instead of attempting
            // a socket reconnection.
            for (conn in connections) {
                conn.markForRecovery()
                conn.reconnection.lastReconnectAttemptMs = t0 + 10 * GLOBAL_TIMEOUT_MS
            }

            // Arm: first all-down pass. Uptime is irrelevant (only now - failed_at
            // matters), so arming must not error however long the process has run.
            val armed = try {
                handleHousekeeping(
                    state,
                    "127.0.0.1",
                    false,
                    t0,
                    NoopReaderRegistry,
                )
                true
            } catch (e: AllLinksFailedException) {
                false
            }
            assertTrue(armed, "arming the all-failed timer must not error")
            assertNotNull(state.allFailedAt, "the failure timer should be armed")

            // Still within the window: no error until a full GLOBAL_TIMEOUT_MS elapses.
            val within = try {
                handleHousekeeping(
                    state,
                    "127.0.0.1",
                    false,
                    t0 + GLOBAL_TIMEOUT_MS - 1000,
                    NoopReaderRegistry,
                )
                true
            } catch (e: AllLinksFailedException) {
                false
            }
            assertTrue(within, "no error until a full ${GLOBAL_TIMEOUT_MS}ms has elapsed since the links failed")

            // Past the window: fires.
            val fired = try {
                handleHousekeeping(
                    state,
                    "127.0.0.1",
                    false,
                    t0 + GLOBAL_TIMEOUT_MS + 1000,
                    NoopReaderRegistry,
                )
                false
            } catch (e: AllLinksFailedException) {
                true
            }
            assertTrue(
                fired,
                "the all-failed timeout must fire once a full ${GLOBAL_TIMEOUT_MS}ms has elapsed since failure",
            )
        }

        test("a_healthy_bond_is_never_re_homed_however_much_dns_drifts") {
            val old = remoteAddr(1)
            val fixture = RehomeFixture(old)
            val resolver = StubResolver(listOf(listOf(remoteAddr(9))))
            val gate = RehomeGate(true, resolver)
            fixture.state.rehome = gate

            // Both uplinks stay live, so the all-failed timer never arms.
            val t0 = nowMs()
            for (now in listOf(t0, t0 + GLOBAL_TIMEOUT_MS * 3)) {
                fixture.keepAllUplinksLive(now)
                try {
                    handleHousekeeping(
                        fixture.state,
                        "rec.example.com",
                        false,
                        now,
                        NoopReaderRegistry,
                    )
                } catch (_: AllLinksFailedException) {
                }
            }

            assertEquals(resolver.calls(), 0, "a live bond must not even re-resolve")
            assertEquals(fixture.remotes(), listOf(old))
            assertEquals(gate.rehomeCount, 0L)
            assertNull(fixture.state.allFailedAt)
        }

        test("a_dead_bond_with_unchanged_dns_is_not_re_homed") {
            val old = remoteAddr(1)
            val fixture = RehomeFixture(old)
            // Answer with a reordered multi-A set that still lists our address.
            val resolver = StubResolver(listOf(listOf(remoteAddr(7), old)))
            val gate = RehomeGate(true, resolver)
            fixture.state.rehome = gate

            val t0 = nowMs()
            fixture.killAllUplinks(t0)
            try {
                handleHousekeeping(
                    fixture.state,
                    "rec.example.com",
                    false,
                    t0,
                    NoopReaderRegistry,
                )
            } catch (_: AllLinksFailedException) {
            }

            val fired = try {
                handleHousekeeping(
                    fixture.state,
                    "rec.example.com",
                    false,
                    t0 + GLOBAL_TIMEOUT_MS + 1_000,
                    NoopReaderRegistry,
                )
                false
            } catch (_: AllLinksFailedException) {
                true
            }

            assertEquals(resolver.calls(), 1, "the probe runs, and answers 'no move'")
            assertEquals(fixture.remotes(), listOf(old), "the bond must stay put")
            assertEquals(gate.rehomeCount, 0L)
            assertTrue(
                fired,
                "without drift the pre-existing all-failed error must still fire",
            )
        }

        test("a_dead_bond_inside_the_all_failed_window_is_not_probed") {
            val old = remoteAddr(1)
            val fixture = RehomeFixture(old)
            val resolver = StubResolver(listOf(listOf(remoteAddr(9))))
            val gate = RehomeGate(true, resolver)
            fixture.state.rehome = gate

            val t0 = nowMs()
            fixture.killAllUplinks(t0)
            try {
                handleHousekeeping(
                    fixture.state,
                    "rec.example.com",
                    false,
                    t0,
                    NoopReaderRegistry,
                )
            } catch (_: AllLinksFailedException) {
            }

            try {
                handleHousekeeping(
                    fixture.state,
                    "rec.example.com",
                    false,
                    t0 + GLOBAL_TIMEOUT_MS - 1_000,
                    NoopReaderRegistry,
                )
            } catch (_: AllLinksFailedException) {
            }

            assertEquals(resolver.calls(), 0, "no probe inside the all-failed window")
            assertEquals(fixture.remotes(), listOf(old))
        }

        test("a_dead_bond_with_drift_re_homes_once_then_is_rate_limited") {
            val old = remoteAddr(1)
            val new = remoteAddr(9)
            val fixture = RehomeFixture(old)
            val resolver = StubResolver(listOf(listOf(new), listOf(new)))
            val gate = RehomeGate(true, resolver)
            fixture.state.rehome = gate

            val t0 = nowMs()
            fixture.killAllUplinks(t0)
            try {
                handleHousekeeping(
                    fixture.state,
                    "rec.example.com",
                    false,
                    t0,
                    NoopReaderRegistry,
                )
            } catch (_: AllLinksFailedException) {
            }

            val movedAt = t0 + GLOBAL_TIMEOUT_MS + 1_000
            try {
                handleHousekeeping(
                    fixture.state,
                    "rec.example.com",
                    false,
                    movedAt,
                    NoopReaderRegistry,
                )
            } catch (_: AllLinksFailedException) {
            }

            assertEquals(fixture.remotes(), listOf(new), "every uplink moved together")
            assertEquals(gate.rehomeCount, 1L)
            assertEquals(
                fixture.state.allFailedAt,
                movedAt,
                "the fresh registration gets a full window before the bond is declared unrecoverable again",
            )
            assertTrue(
                fixture.state.reg.isProbing(),
                "the bond re-registers from scratch, starting with RTT probing",
            )

            // Still dead and still drifting, but inside the rate-limit window.
            fixture.killAllUplinks(movedAt)
            val stillLimited = try {
                handleHousekeeping(
                    fixture.state,
                    "rec.example.com",
                    false,
                    movedAt + GLOBAL_TIMEOUT_MS + 1_000,
                    NoopReaderRegistry,
                )
                false
            } catch (_: AllLinksFailedException) {
                true
            }
            assertEquals(resolver.calls(), 1, "the rate limit gates the lookup too")
            assertEquals(gate.rehomeCount, 1L)
            assertTrue(stillLimited, "the all-failed error path stands meanwhile")
        }

        test("the_opt_out_leaves_a_dead_drifted_bond_where_it_is") {
            val old = remoteAddr(1)
            val fixture = RehomeFixture(old)
            val resolver = StubResolver(listOf(listOf(remoteAddr(9))))
            val gate = RehomeGate(false, resolver)
            fixture.state.rehome = gate

            val t0 = nowMs()
            fixture.killAllUplinks(t0)
            try {
                handleHousekeeping(
                    fixture.state,
                    "rec.example.com",
                    false,
                    t0,
                    NoopReaderRegistry,
                )
            } catch (_: AllLinksFailedException) {
            }

            val fired = try {
                handleHousekeeping(
                    fixture.state,
                    "rec.example.com",
                    false,
                    t0 + GLOBAL_TIMEOUT_MS + 1_000,
                    NoopReaderRegistry,
                )
                false
            } catch (_: AllLinksFailedException) {
                true
            }

            assertEquals(resolver.calls(), 0)
            assertEquals(fixture.remotes(), listOf(old))
            assertTrue(
                fired,
                "the pre-existing all-failed error must fire",
            )
        }
    }

    suite("rehome") {
        test("receiver_moved_needs_every_pinned_address_to_be_gone") {
            // Still listed: not a move.
            assertFalse(receiverMoved(listOf(remoteAddr(1)), listOf(remoteAddr(1))))
            // Multi-A reorder that still lists ours: not a move.
            assertFalse(receiverMoved(listOf(remoteAddr(1)), listOf(remoteAddr(2), remoteAddr(1))))
            // One uplink is still on a listed address: not a move for the bond.
            assertFalse(receiverMoved(listOf(remoteAddr(1), remoteAddr(2)), listOf(remoteAddr(2), remoteAddr(3))))
            // Every pinned address is gone: a move.
            assertTrue(receiverMoved(listOf(remoteAddr(1), remoteAddr(2)), listOf(remoteAddr(3))))
            // An empty answer told us nothing.
            assertFalse(receiverMoved(listOf(remoteAddr(1)), listOf()))
            // Nothing pinned: nothing to move.
            assertFalse(receiverMoved(listOf(), listOf(remoteAddr(1))))
        }

        test("probe_slot_is_rate_limited_and_respects_the_opt_out") {
            // Test rate limiting by verifying that consecutive tryRehome calls
            // at different times respect the rate limit window.
            val old = remoteAddr(1)
            val new = remoteAddr(9)
            val connections1 = createTestConnections(1)
            val resolver1 = StubResolver(listOf(listOf(new), listOf(new), listOf(new)))
            val state1 = SenderState(
                connections = connections1,
                connIo = createTestConnIoMap(connections1),
                rehome = RehomeGate(true, resolver1),
            )
            for (io in state1.connIo.values) io.remote = old

            val t0 = 1_000L
            // First probe should succeed (enabled and no prior probes).
            val first = tryRehome(state1, NoopReaderRegistry, "test.com", 30_000, t0)
            assertTrue(first, "the first dead bond probes at once")

            // Second probe within the window should fail (rate limited).
            val connections2 = createTestConnections(1)
            val state2 = SenderState(
                connections = connections2,
                connIo = createTestConnIoMap(connections2),
                rehome = state1.rehome, // Reuse the gate to test rate limiting.
            )
            for (io in state2.connIo.values) io.remote = old
            val second = tryRehome(state2, NoopReaderRegistry, "test.com", 30_000, t0 + REHOME_MIN_INTERVAL_MS - 1)
            assertFalse(
                second,
                "a second attempt inside the window must be refused",
            )

            // Probe after the window expires should succeed.
            val connections3 = createTestConnections(1)
            val state3 = SenderState(
                connections = connections3,
                connIo = createTestConnIoMap(connections3),
                rehome = state1.rehome,
            )
            for (io in state3.connIo.values) io.remote = old
            val third = tryRehome(state3, NoopReaderRegistry, "test.com", 30_000, t0 + REHOME_MIN_INTERVAL_MS)
            assertTrue(
                third,
                "the slot is due again once the window has passed",
            )

            // Test opt-out: disabled gate should never probe.
            val connections4 = createTestConnections(1)
            val resolver4 = StubResolver(listOf(listOf(new)))
            val state4 = SenderState(
                connections = connections4,
                connIo = createTestConnIoMap(connections4),
                rehome = RehomeGate(false, resolver4),
            )
            for (io in state4.connIo.values) io.remote = old
            val disabled = tryRehome(state4, NoopReaderRegistry, "test.com", 30_000, t0)
            assertFalse(disabled, "--no-rehome must never probe")
            assertEquals(resolver4.calls(), 0)
        }

        test("a_failed_lookup_is_not_drift") {
            val old = remoteAddr(1)
            val connections = createTestConnections(1)
            val state = SenderState(
                connections = connections,
                connIo = createTestConnIoMap(connections),
                rehome = RehomeGate(true, StubResolver(listOf(null))),
            )
            // Set up initial state.
            for (io in state.connIo.values) io.remote = old

            val result = tryRehome(state, NoopReaderRegistry, "rec.example.com", 30_000, nowMs())
            assertFalse(result)
            assertEquals(state.connIo[connections[0].connId]?.remote, old, "the bond must stay put")
        }

        test("unchanged_dns_does_not_re_home") {
            val old = remoteAddr(1)
            val connections = createTestConnections(1)
            val state = SenderState(
                connections = connections,
                connIo = createTestConnIoMap(connections),
                // A reordered multi-A answer that still lists our address.
                rehome = RehomeGate(true, StubResolver(listOf(listOf(remoteAddr(7), old)))),
            )
            for (io in state.connIo.values) io.remote = old

            val result = tryRehome(state, NoopReaderRegistry, "rec.example.com", 30_000, nowMs())
            assertFalse(result)
            assertEquals(state.connIo[connections[0].connId]?.remote, old)
        }

        test("the_opt_out_skips_the_lookup_entirely") {
            val old = remoteAddr(1)
            val connections = createTestConnections(1)
            val resolver = StubResolver(listOf(listOf(remoteAddr(9))))
            val state = SenderState(
                connections = connections,
                connIo = createTestConnIoMap(connections),
                rehome = RehomeGate(false, resolver),
            )
            for (io in state.connIo.values) io.remote = old

            val result = tryRehome(state, NoopReaderRegistry, "rec.example.com", 30_000, nowMs())
            assertFalse(result)
            assertEquals(resolver.calls(), 0, "--no-rehome must not even resolve")
            assertEquals(state.connIo[connections[0].connId]?.remote, old)
        }

        test("drift_re_homes_once_then_is_rate_limited") {
            val old = remoteAddr(1)
            val new = remoteAddr(9)
            val connections = createTestConnections(1)
            val resolver = StubResolver(listOf(listOf(new), listOf(new)))
            val state = SenderState(
                connections = connections,
                connIo = createTestConnIoMap(connections),
                rehome = RehomeGate(true, resolver),
            )
            for (io in state.connIo.values) io.remote = old

            val t0 = nowMs()
            val result1 = tryRehome(state, NoopReaderRegistry, "rec.example.com", 30_000, t0)
            assertTrue(result1)
            assertEquals(state.connIo[connections[0].connId]?.remote, new, "every uplink moved together")
            assertEquals(state.rehome.rehomeCount, 1L)

            // Still drifting (the stub would answer again), but inside the window.
            val result2 = tryRehome(state, NoopReaderRegistry, "rec.example.com", 30_000, t0 + 1_000)
            assertFalse(result2)
            assertEquals(resolver.calls(), 1, "the rate limit gates the lookup too")
            assertEquals(state.rehome.rehomeCount, 1L)
        }

        test("re_home_preserves_our_id_half_and_resets_to_pre_reg1") {
            val connections = mutableListOf(createTestConnection(), createTestConnection())
            val state = SenderState(
                connections = connections,
                connIo = ConnIoMap().apply { for (c in connections) put(c.connId, createTestConnIo(remoteAddr(1))) },
                rehome = RehomeGate(true, StubResolver(listOf(listOf(remoteAddr(9))))),
            )

            // Model a bond that had fully registered against the old receiver.
            val clientHalf = state.reg.srtlaId.copyOfRange(0, SRTLA_ID_LEN / 2)
            state.reg.srtlaId.fill(0xab.toByte(), SRTLA_ID_LEN / 2, SRTLA_ID_LEN)
            state.reg.armReg3Gate(0)
            state.reg.setReg1TargetIdx(1)
            state.reg.setPendingReg2Idx(1)
            state.reg.setBroadcastReg2Pending(true)
            state.reg.updateActiveConnections(connections)
            assertTrue(state.reg.activeConnections > 0)

            assertTrue(tryRehome(state, NoopReaderRegistry, "rec.example.com", 30_000, nowMs()), "drift must re-home")

            assertContentEquals(
                state.reg.srtlaId.copyOfRange(0, SRTLA_ID_LEN / 2),
                clientHalf,
                "our half of the SRTLA id must survive the move so a deterministic receiver reissues the same full id",
            )
            assertFalse(
                state.reg.srtlaId.copyOfRange(SRTLA_ID_LEN / 2, SRTLA_ID_LEN).all { it == 0xab.toByte() },
                "the old receiver's half must be discarded, and re-randomized (not zeroed) so the REG1 looks exactly like a fresh sender's",
            )

            assertNull(state.reg.pendingReg2Idx())
            assertFalse(state.reg.broadcastReg2Pending)
            assertFalse(state.reg.isAwaitingReg3(0), "REG3 grants must be revoked")
            assertEquals(state.reg.activeConnections, 0)
            assertTrue(
                state.reg.isProbing(),
                "the bond must re-probe and then re-register from REG1",
            )
        }

        test("re_home_clears_connection_and_seq_tracker_state") {
            val connections = mutableListOf(createTestConnection(), createTestConnection())
            val recordingRegistry = RecordingReaderRegistry()
            val state = SenderState(
                connections = connections,
                connIo = ConnIoMap().apply { for (c in connections) put(c.connId, createTestConnIo(remoteAddr(1))) },
                rehome = RehomeGate(true, StubResolver(listOf(listOf(remoteAddr(9))))),
            )

            // Give the link live-looking state and sequence-tracker ownership.
            for (conn in connections) {
                conn.connected = true
                conn.inFlightPackets = 5
            }
            val owner = connections[0].connId
            val t0 = nowMs()
            state.seqTracker.insert(1000, owner, t0)
            assertEquals(state.seqTracker.get(1000, t0), owner)

            assertTrue(tryRehome(state, recordingRegistry, "rec.example.com", 30_000, t0), "drift must re-home")

            assertNull(
                state.seqTracker.get(1000, t0),
                "a re-homed link must not stay the owner of sequences it can no longer answer for",
            )
            for (conn in connections) {
                assertFalse(conn.connected, "${conn.label} must be unregistered")
                assertEquals(conn.inFlightPackets, 0)
            }
            assertEquals(
                recordingRegistry.restartCount(),
                connections.size,
                "every re-homed uplink gets a fresh reader",
            )
        }
    }
}

// ── Test fixtures and helpers ────────────────────────────────────────────────

private fun remoteAddr(last: Int): InetSocketAddress =
    InetSocketAddress("203.0.113.$last", 5000)

private class RehomeFixture(remoteAddr: InetSocketAddress) {
    private val conns = mutableListOf(createTestConnection(), createTestConnection())
    val state = SenderState(
        connections = conns,
        connIo = ConnIoMap().apply { for (c in conns) put(c.connId, createTestConnIo(remoteAddr)) },
        reg = RegistrationManager().apply { hasConnected = true },
    )

    fun keepAllUplinksLive(now: Long) {
        for (conn in state.connections) {
            conn.connected = true
            conn.lastReceived = now
            conn.reconnection.startupGraceDeadlineMs = now + 300_000L // STARTUP_GRACE_MS
        }
    }

    fun killAllUplinks(at: Long) {
        for (conn in state.connections) {
            conn.markForRecovery()
            conn.reconnection.lastReconnectAttemptMs = at + 10 * GLOBAL_TIMEOUT_MS
        }
    }

    fun remotes(): List<InetSocketAddress> {
        val remotes = mutableSetOf<InetSocketAddress>()
        for (conn in state.connections) {
            val io = state.connIo[conn.connId]
            if (io != null) remotes.add(io.remote)
        }
        return remotes.sortedBy { it.toString() }
    }
}

private class RecordingReaderRegistry : dev.abdulkadirozyurt.srtla.sender.ReaderRegistry {
    private val restarts = AtomicInteger(0)

    override fun restartReaderFor(conn: dev.abdulkadirozyurt.srtla.connection.SrtlaConnection, io: dev.abdulkadirozyurt.srtla.sender.ConnIo) {
        restarts.incrementAndGet()
    }

    override fun syncReaders(connections: List<dev.abdulkadirozyurt.srtla.connection.SrtlaConnection>, connIo: dev.abdulkadirozyurt.srtla.sender.ConnIoMap) {
    }

    fun restartCount(): Int = restarts.get()
}

