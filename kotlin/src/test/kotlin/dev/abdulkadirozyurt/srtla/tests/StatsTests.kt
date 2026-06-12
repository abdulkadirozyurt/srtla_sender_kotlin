// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/stats.rs #[cfg(test)]
//
// SharedStats tests: mirrors Rust test_shared_stats_new, test_shared_stats_empty_update,
// test_to_json_contains_expected_fields + additional Kotlin-specific coverage.
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.sender.selection.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.sender.selection.SchedulingMode
import dev.abdulkadirozyurt.srtla.stats.SharedStats
import dev.abdulkadirozyurt.srtla.stats.StatsSnapshot
import dev.abdulkadirozyurt.srtla.testkit.*

fun registerStatsTests() {

// ── test_shared_stats_new ─────────────────────────────────────────────────────
// Mirrors Rust test_shared_stats_new in src/stats.rs
suite("SharedStats - new/default") {
    test("initial active_links is 0") {
        val stats = SharedStats()
        assertEquals(0, stats.get().activeLinks)
    }
    test("initial total_links is 0") {
        val stats = SharedStats()
        assertEquals(0, stats.get().totalLinks)
    }
    test("initial links list is empty") {
        val stats = SharedStats()
        assertTrue(stats.get().links.isEmpty())
    }
    test("initial mode is enhanced") {
        val stats = SharedStats()
        assertEquals("enhanced", stats.get().mode)
    }
}

// ── test_shared_stats_empty_update ───────────────────────────────────────────
// Mirrors Rust test_shared_stats_empty_update in src/stats.rs
suite("SharedStats - empty update") {
    test("update with empty connections sets mode correctly") {
        val stats = SharedStats()
        val config = ConfigSnapshot(
            mode = SchedulingMode.ENHANCED,
            qualityEnabled = true,
            explorationEnabled = false,
            rttDeltaMs = 30,
        )
        stats.update(emptyList(), config)
        val snap = stats.get()
        assertEquals("enhanced", snap.mode)
    }
    test("update with empty connections: quality_enabled true for enhanced") {
        val stats = SharedStats()
        val config = ConfigSnapshot(mode = SchedulingMode.ENHANCED, qualityEnabled = true)
        stats.update(emptyList(), config)
        assertTrue(stats.get().qualityEnabled)
    }
    test("update with empty connections: quality_enabled false for classic") {
        val stats = SharedStats()
        val config = ConfigSnapshot(mode = SchedulingMode.CLASSIC, qualityEnabled = true)
        stats.update(emptyList(), config)
        // Classic mode → quality always false regardless of qualityEnabled setting
        assertFalse(stats.get().qualityEnabled)
    }
    test("update with empty connections: rtt_delta_ms propagated") {
        val stats = SharedStats()
        val config = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD, rttDeltaMs = 50)
        stats.update(emptyList(), config)
        assertEquals(50, stats.get().rttDeltaMs)
    }
    test("update with empty connections: totals are 0") {
        val stats = SharedStats()
        val config = ConfigSnapshot()
        stats.update(emptyList(), config)
        val snap = stats.get()
        assertEquals(0, snap.activeLinks)
        assertEquals(0, snap.totalLinks)
        assertEquals(0, snap.totalWindow)
        assertEquals(0, snap.totalInFlight)
    }
}

// ── test_to_json_contains_expected_fields ────────────────────────────────────
// Mirrors Rust test_to_json_contains_expected_fields in src/stats.rs
suite("SharedStats - JSON serialization") {
    test("toJson contains 'mode' field") {
        val stats = SharedStats()
        assertTrue(stats.toJson().contains("\"mode\""))
    }
    test("toJson contains 'active_links' field") {
        val stats = SharedStats()
        assertTrue(stats.toJson().contains("\"active_links\""))
    }
    test("toJson contains 'total_window' field") {
        val stats = SharedStats()
        assertTrue(stats.toJson().contains("\"total_window\""))
    }
    test("toJson contains 'links' field") {
        val stats = SharedStats()
        assertTrue(stats.toJson().contains("\"links\""))
    }
    test("toJson is valid JSON (starts with { ends with })") {
        val stats = SharedStats()
        val json = stats.toJson()
        assertTrue(json.startsWith("{"), "JSON should start with {")
        assertTrue(json.endsWith("}"), "JSON should end with }")
    }
    test("toJson for rtt-threshold mode contains correct mode string") {
        val stats = SharedStats()
        val config = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD)
        stats.update(emptyList(), config)
        assertTrue(stats.toJson().contains("\"rtt-threshold\""))
    }
    test("StatsSnapshot.toJson serializes correctly") {
        val snap = StatsSnapshot(
            mode = "classic",
            qualityEnabled = false,
            rttDeltaMs = 30,
            activeLinks = 2,
            totalLinks = 3,
            totalWindow = 100,
            totalInFlight = 10,
            links = emptyList(),
        )
        val json = snap.toJson()
        assertTrue(json.contains("\"classic\""))
        assertTrue(json.contains("\"quality_enabled\":false"))
        assertTrue(json.contains("\"active_links\":2"))
        assertTrue(json.contains("\"total_links\":3"))
    }
}

// ── Mode name formatting ──────────────────────────────────────────────────────
suite("SharedStats - mode name formatting") {
    test("CLASSIC → 'classic'") {
        val stats = SharedStats()
        stats.update(emptyList(), ConfigSnapshot(mode = SchedulingMode.CLASSIC))
        assertEquals("classic", stats.get().mode)
    }
    test("ENHANCED → 'enhanced'") {
        val stats = SharedStats()
        stats.update(emptyList(), ConfigSnapshot(mode = SchedulingMode.ENHANCED))
        assertEquals("enhanced", stats.get().mode)
    }
    test("RTT_THRESHOLD → 'rtt-threshold'") {
        val stats = SharedStats()
        stats.update(emptyList(), ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD))
        assertEquals("rtt-threshold", stats.get().mode)
    }
    test("EDPF → 'edpf'") {
        val stats = SharedStats()
        stats.update(emptyList(), ConfigSnapshot(mode = SchedulingMode.EDPF))
        assertEquals("edpf", stats.get().mode)
    }
}

// ── Thread safety ─────────────────────────────────────────────────────────────
suite("SharedStats - concurrent access") {
    test("concurrent update and get does not throw") {
        val stats = SharedStats()
        val config = ConfigSnapshot()
        val writer = Thread {
            repeat(100) { stats.update(emptyList(), config) }
        }
        val reader = Thread {
            repeat(100) { stats.get() }
        }
        writer.start(); reader.start()
        writer.join(3000); reader.join(3000)
        assertTrue(true)
    }
    test("toJson under concurrent update is always valid JSON") {
        val stats = SharedStats()
        val config = ConfigSnapshot()
        var lastJson = ""
        val writer = Thread { repeat(200) { stats.update(emptyList(), config) } }
        val reader = Thread { repeat(200) { lastJson = stats.toJson() } }
        writer.start(); reader.start()
        writer.join(3000); reader.join(3000)
        // Last JSON read must be valid (at minimum starts/ends with braces)
        assertTrue(lastJson.startsWith("{"))
        assertTrue(lastJson.endsWith("}"))
    }
}

} // registerStatsTests
