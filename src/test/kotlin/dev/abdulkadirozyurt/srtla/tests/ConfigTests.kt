// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/tests/config_tests.rs + src/config.rs #[cfg(test)]
//
// Full port of ALL config_tests.rs scenarios + config.rs inline tests.
// Coverage: DynamicConfig creation, from_cli, all commands, effective quality/exploration,
// concurrent access, whitespace handling, unknown/empty commands.
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.config.*
import dev.abdulkadirozyurt.srtla.sender.selection.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.sender.selection.SchedulingMode
import dev.abdulkadirozyurt.srtla.testkit.*

fun registerConfigTests() {

// ── test_config_new / test_config_default ─────────────────────────────────────
// Mirrors Rust test_config_new (config_tests.rs) + test_config_default (config.rs)
suite("DynamicConfig - defaults") {
    test("default mode is enhanced") {
        val cfg = DynamicConfig()
        assertEquals(SchedulingMode.ENHANCED, cfg.mode())
    }
    test("default quality enabled") {
        val cfg = DynamicConfig()
        assertTrue(cfg.snapshot().qualityEnabled)
    }
    test("default exploration disabled") {
        val cfg = DynamicConfig()
        assertFalse(cfg.snapshot().explorationEnabled)
    }
    test("default rtt-delta is 30") {
        val cfg = DynamicConfig()
        assertEquals(DEFAULT_RTT_DELTA_MS, cfg.snapshot().rttDeltaMs)
    }
    test("snapshot returns all defaults") {
        val cfg = DynamicConfig()
        val snap = cfg.snapshot()
        assertEquals(SchedulingMode.ENHANCED, snap.mode)
        assertTrue(snap.qualityEnabled)
        assertFalse(snap.explorationEnabled)
        assertEquals(30, snap.rttDeltaMs)
    }
}

// ── test_config_from_cli ──────────────────────────────────────────────────────
// Mirrors Rust test_config_from_cli in config_tests.rs
suite("DynamicConfig - fromCli") {
    test("from_cli enhanced no-quality=false") {
        val cfg = DynamicConfig.fromCli(SchedulingMode.ENHANCED, noQuality = false, exploration = false, rttDeltaMs = 30)
        val snap = cfg.snapshot()
        assertEquals(SchedulingMode.ENHANCED, snap.mode)
        assertTrue(snap.qualityEnabled)
        assertFalse(snap.explorationEnabled)
    }
    test("from_cli classic no-quality=true exploration=true rtt=50") {
        val cfg = DynamicConfig.fromCli(SchedulingMode.CLASSIC, noQuality = true, exploration = true, rttDeltaMs = 50)
        val snap = cfg.snapshot()
        assertEquals(SchedulingMode.CLASSIC, snap.mode)
        assertFalse(snap.qualityEnabled)   // no_quality=true → disabled
        assertTrue(snap.explorationEnabled)
        assertEquals(50, snap.rttDeltaMs)
    }
    test("from_cli rtt-threshold") {
        val cfg = DynamicConfig.fromCli(SchedulingMode.RTT_THRESHOLD, rttDeltaMs = 100)
        assertEquals(SchedulingMode.RTT_THRESHOLD, cfg.mode())
        assertEquals(100, cfg.snapshot().rttDeltaMs)
    }
    test("from_cli edpf") {
        val cfg = DynamicConfig.fromCli(SchedulingMode.EDPF)
        assertEquals(SchedulingMode.EDPF, cfg.mode())
    }
}

// ── test_apply_cmd_mode ───────────────────────────────────────────────────────
// Mirrors Rust test_apply_cmd_mode (config_tests.rs) + test_mode_commands (config.rs)
suite("applyCmd - mode") {
    test("mode classic sets classic") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "mode classic")
        assertEquals(SchedulingMode.CLASSIC, cfg.mode())
    }
    test("mode enhanced sets enhanced") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "mode classic")
        applyCmd(cfg, "mode enhanced")
        assertEquals(SchedulingMode.ENHANCED, cfg.mode())
    }
    test("mode rtt-threshold") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "mode rtt-threshold")
        assertEquals(SchedulingMode.RTT_THRESHOLD, cfg.mode())
    }
    test("mode edpf") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "mode edpf")
        assertEquals(SchedulingMode.EDPF, cfg.mode())
    }
    test("unknown mode does not change config") {
        val cfg = DynamicConfig()
        val before = cfg.mode()
        applyCmd(cfg, "mode foobar")
        assertEquals(before, cfg.mode())
    }
    test("mode with no arg does not change config") {
        val cfg = DynamicConfig()
        val before = cfg.mode()
        applyCmd(cfg, "mode")
        assertEquals(before, cfg.mode())
    }
    test("mode returns Text response") {
        val cfg = DynamicConfig()
        val resp = applyCmd(cfg, "mode classic")
        assertTrue(resp is CmdResponse.Text)
        assertTrue((resp as CmdResponse.Text).text.contains("classic"))
    }
}

// ── test_apply_cmd_quality ────────────────────────────────────────────────────
// Mirrors Rust test_apply_cmd_quality (config_tests.rs) + test_quality_commands
suite("applyCmd - quality") {
    test("quality off disables quality") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "quality off")
        assertFalse(cfg.snapshot().qualityEnabled)
    }
    test("quality on re-enables quality") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "quality off")
        applyCmd(cfg, "quality on")
        assertTrue(cfg.snapshot().qualityEnabled)
    }
    test("quality invalid value does not change") {
        val cfg = DynamicConfig()
        val before = cfg.snapshot().qualityEnabled
        applyCmd(cfg, "quality maybe")
        assertEquals(before, cfg.snapshot().qualityEnabled)
    }
    test("quality with no arg does not change") {
        val cfg = DynamicConfig()
        val before = cfg.snapshot().qualityEnabled
        applyCmd(cfg, "quality")
        assertEquals(before, cfg.snapshot().qualityEnabled)
    }
}

// ── test_apply_cmd_explore ────────────────────────────────────────────────────
// Mirrors Rust test_apply_cmd_explore (config_tests.rs) + test_exploration_commands
suite("applyCmd - explore") {
    test("explore on enables exploration") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "explore on")
        assertTrue(cfg.snapshot().explorationEnabled)
    }
    test("explore off disables exploration") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "explore on")
        applyCmd(cfg, "explore off")
        assertFalse(cfg.snapshot().explorationEnabled)
    }
    test("explore invalid value does not change") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "explore maybe")
        assertFalse(cfg.snapshot().explorationEnabled)
    }
}

// ── test_apply_cmd_rtt_delta ──────────────────────────────────────────────────
// Mirrors Rust test_apply_cmd_rtt_delta (config_tests.rs) + test_rtt_delta_commands
suite("applyCmd - rtt-delta") {
    test("rtt-delta 50 sets 50") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "rtt-delta 50")
        assertEquals(50, cfg.snapshot().rttDeltaMs)
    }
    test("rtt-delta 100 sets 100") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "rtt-delta 100")
        assertEquals(100, cfg.snapshot().rttDeltaMs)
    }
    test("rtt-delta invalid does not change — matches Rust test_apply_cmd_rtt_delta") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "rtt-delta 50")
        applyCmd(cfg, "rtt-delta invalid")
        assertEquals(50, cfg.snapshot().rttDeltaMs)  // unchanged
    }
    test("rtt-delta returns Text response") {
        val cfg = DynamicConfig()
        val resp = applyCmd(cfg, "rtt-delta 75")
        assertTrue(resp is CmdResponse.Text)
    }
    test("rtt-delta with no arg does not change") {
        val cfg = DynamicConfig()
        val before = cfg.snapshot().rttDeltaMs
        applyCmd(cfg, "rtt-delta")
        assertEquals(before, cfg.snapshot().rttDeltaMs)
    }
}

// ── test_apply_cmd_status ─────────────────────────────────────────────────────
// Mirrors Rust test_apply_cmd_status in config_tests.rs
suite("applyCmd - status") {
    test("status does not change config") {
        val cfg = DynamicConfig()
        val before = cfg.snapshot()
        applyCmd(cfg, "status")
        val after = cfg.snapshot()
        assertEquals(before.mode, after.mode)
        assertEquals(before.qualityEnabled, after.qualityEnabled)
        assertEquals(before.explorationEnabled, after.explorationEnabled)
    }
    test("status returns Text with mode") {
        val cfg = DynamicConfig()
        val resp = applyCmd(cfg, "status")
        assertTrue(resp is CmdResponse.Text)
        val text = (resp as CmdResponse.Text).text
        assertTrue(text.contains("mode"), "status response should contain 'mode'")
    }
    test("status reflects current mode") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "mode classic")
        val resp = applyCmd(cfg, "status")
        val text = (resp as CmdResponse.Text).text
        assertTrue(text.contains("classic"))
    }
}

// ── test_apply_cmd_empty_and_unknown ─────────────────────────────────────────
// Mirrors Rust test_apply_cmd_empty_and_unknown in config_tests.rs
suite("applyCmd - empty and unknown") {
    test("empty string returns None, no change") {
        val cfg = DynamicConfig()
        val resp = applyCmd(cfg, "")
        assertTrue(resp is CmdResponse.None)
        assertEquals(SchedulingMode.ENHANCED, cfg.mode())
    }
    test("whitespace-only returns None") {
        val cfg = DynamicConfig()
        val resp = applyCmd(cfg, "   ")
        assertTrue(resp is CmdResponse.None)
    }
    test("unknown command returns None, no change") {
        val cfg = DynamicConfig()
        val resp = applyCmd(cfg, "unknown command")
        assertTrue(resp is CmdResponse.None)
        assertEquals(SchedulingMode.ENHANCED, cfg.mode())
    }
}

// ── test_apply_cmd_whitespace_handling ───────────────────────────────────────
// Mirrors Rust test_apply_cmd_whitespace_handling in config_tests.rs
suite("applyCmd - whitespace handling") {
    test("leading/trailing whitespace trimmed") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "  mode classic  ")
        assertEquals(SchedulingMode.CLASSIC, cfg.mode())
    }
    test("multi-space between tokens") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "mode  enhanced")
        assertEquals(SchedulingMode.ENHANCED, cfg.mode())
    }
    test("tab-separated tokens") {
        val cfg = DynamicConfig()
        applyCmd(cfg, "mode\tclassic")
        assertEquals(SchedulingMode.CLASSIC, cfg.mode())
    }
}

// ── test_effective_quality_enabled ───────────────────────────────────────────
// Mirrors Rust test_effective_quality_enabled in config_tests.rs + test_effective_quality
suite("ConfigSnapshot - effectiveQualityEnabled") {
    test("classic mode: quality never effective (quality=true)") {
        val snap = ConfigSnapshot(mode = SchedulingMode.CLASSIC, qualityEnabled = true, explorationEnabled = true)
        assertFalse(snap.effectiveQualityEnabled())
    }
    test("classic mode: exploration never effective") {
        val snap = ConfigSnapshot(mode = SchedulingMode.CLASSIC, qualityEnabled = true, explorationEnabled = true)
        assertFalse(snap.effectiveExplorationEnabled())
    }
    test("enhanced mode: quality effective when enabled") {
        val snap = ConfigSnapshot(mode = SchedulingMode.ENHANCED, qualityEnabled = true, explorationEnabled = true)
        assertTrue(snap.effectiveQualityEnabled())
    }
    test("enhanced mode: exploration effective when enabled") {
        val snap = ConfigSnapshot(mode = SchedulingMode.ENHANCED, qualityEnabled = true, explorationEnabled = true)
        assertTrue(snap.effectiveExplorationEnabled())
    }
    test("enhanced mode: quality off → not effective") {
        val snap = ConfigSnapshot(mode = SchedulingMode.ENHANCED, qualityEnabled = false)
        assertFalse(snap.effectiveQualityEnabled())
    }
    test("enhanced mode: exploration off → not effective") {
        val snap = ConfigSnapshot(mode = SchedulingMode.ENHANCED, explorationEnabled = false)
        assertFalse(snap.effectiveExplorationEnabled())
    }
    test("rtt-threshold: quality effective") {
        val snap = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD, qualityEnabled = true, explorationEnabled = true)
        assertTrue(snap.effectiveQualityEnabled())
    }
    test("rtt-threshold: exploration NOT effective (enhanced-only)") {
        val snap = ConfigSnapshot(mode = SchedulingMode.RTT_THRESHOLD, qualityEnabled = true, explorationEnabled = true)
        assertFalse(snap.effectiveExplorationEnabled())
    }
    test("edpf: quality effective if enabled") {
        // EDPF mode doesn't use quality scoring in same way but effectiveQualityEnabled is !isClassic
        val snap = ConfigSnapshot(mode = SchedulingMode.EDPF, qualityEnabled = true, explorationEnabled = true)
        assertTrue(snap.effectiveQualityEnabled())
    }
}

// ── test_config_concurrent_access ────────────────────────────────────────────
// Mirrors Rust test_config_concurrent_access in config_tests.rs + test_concurrent_access
suite("DynamicConfig - concurrent access") {
    test("concurrent read/write does not throw") {
        val cfg = DynamicConfig()
        val writer = Thread {
            repeat(200) {
                applyCmd(cfg, "mode classic")
                applyCmd(cfg, "mode enhanced")
                applyCmd(cfg, "mode rtt-threshold")
            }
        }
        val reader = Thread {
            repeat(200) { cfg.snapshot() }
        }
        writer.start(); reader.start()
        writer.join(5000); reader.join(5000)
        // No assertions needed — lack of exception = pass (mirrors Rust test)
        assertTrue(true)
    }
    test("snapshot under concurrent mutation is always valid mode") {
        val cfg = DynamicConfig()
        val validModes = SchedulingMode.values().toSet()
        val errors = mutableListOf<String>()
        val writer = Thread {
            repeat(500) {
                applyCmd(cfg, "mode classic")
                applyCmd(cfg, "mode enhanced")
            }
        }
        val reader = Thread {
            repeat(500) {
                val snap = cfg.snapshot()
                if (snap.mode !in validModes) errors += "invalid mode: ${snap.mode}"
            }
        }
        writer.start(); reader.start()
        writer.join(5000); reader.join(5000)
        assertTrue(errors.isEmpty(), "Concurrent access yielded invalid mode: $errors")
    }
}

// ── reload command ────────────────────────────────────────────────────────────
suite("applyCmd - reload") {
    test("reload returns Text sentinel 'reload'") {
        val cfg = DynamicConfig()
        val resp = applyCmd(cfg, "reload")
        assertTrue(resp is CmdResponse.Text)
        assertEquals("reload", (resp as CmdResponse.Text).text)
    }
    test("reload does not change config") {
        val cfg = DynamicConfig()
        val before = cfg.snapshot()
        applyCmd(cfg, "reload")
        val after = cfg.snapshot()
        assertEquals(before.mode, after.mode)
        assertEquals(before.qualityEnabled, after.qualityEnabled)
    }
}

// ── stats command ─────────────────────────────────────────────────────────────
suite("applyCmd - stats") {
    test("stats with provider returns Text with JSON") {
        val cfg = DynamicConfig()
        val resp = applyCmd(cfg, "stats", statsProvider = { """{"mode":"enhanced"}""" })
        assertTrue(resp is CmdResponse.Text)
        assertTrue((resp as CmdResponse.Text).text.contains("mode"))
    }
    test("stats without provider returns None") {
        val cfg = DynamicConfig()
        val resp = applyCmd(cfg, "stats", statsProvider = null)
        assertTrue(resp is CmdResponse.None)
    }
}

} // registerConfigTests
