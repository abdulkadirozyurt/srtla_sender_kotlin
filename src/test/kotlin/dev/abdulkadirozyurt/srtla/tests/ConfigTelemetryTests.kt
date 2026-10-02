// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/config.rs, src/tests/config_tests.rs, src/control.rs,
//         src/toml_config.rs, src/main.rs, src/version.rs,
//         src/metrics.rs, src/stats.rs
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.cli.composeVersionLine
import dev.abdulkadirozyurt.srtla.cli.versionLine
import dev.abdulkadirozyurt.srtla.config.INVALID_PARAMS
import dev.abdulkadirozyurt.srtla.config.INVALID_REQUEST
import dev.abdulkadirozyurt.srtla.config.METHOD_NOT_FOUND
import dev.abdulkadirozyurt.srtla.config.PARSE_ERROR
import dev.abdulkadirozyurt.srtla.config.TomlConfig
import dev.abdulkadirozyurt.srtla.config.TomlConfigException
import dev.abdulkadirozyurt.srtla.cli.ParsedArgs
import dev.abdulkadirozyurt.srtla.cli.parseArgs
import dev.abdulkadirozyurt.srtla.telemetry.renderMetrics
import dev.abdulkadirozyurt.srtla.telemetry.requestPath
import dev.abdulkadirozyurt.srtla.testkit.assertNull
import dev.abdulkadirozyurt.srtla.config.DynamicConfig
import dev.abdulkadirozyurt.srtla.config.RpcError
import dev.abdulkadirozyurt.srtla.config.dispatch
import dev.abdulkadirozyurt.srtla.core.CONN_TIMEOUT_MS
import dev.abdulkadirozyurt.srtla.core.CONN_TIMEOUT_MS_MAX
import dev.abdulkadirozyurt.srtla.core.CONN_TIMEOUT_MS_MIN
import dev.abdulkadirozyurt.srtla.core.ConfigSnapshot
import dev.abdulkadirozyurt.srtla.core.CriticalWindow
import dev.abdulkadirozyurt.srtla.core.STALL_ACK_STALE_MS
import dev.abdulkadirozyurt.srtla.core.STALL_MIN_IN_FLIGHT_PACKETS
import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import dev.abdulkadirozyurt.srtla.json.Json
import dev.abdulkadirozyurt.srtla.json.JsonParseException

import dev.abdulkadirozyurt.srtla.telemetry.SharedStats
import dev.abdulkadirozyurt.srtla.testkit.assertEquals
import dev.abdulkadirozyurt.srtla.testkit.assertFalse
import dev.abdulkadirozyurt.srtla.testkit.assertTrue
import dev.abdulkadirozyurt.srtla.testkit.assertFailsWith
import dev.abdulkadirozyurt.srtla.testkit.suite

fun registerConfigTelemetryTests() {
    // ──── config.rs tests ────────────────────────────────────────────────────────

    suite("config") {
        test("test_config_default") {
            val config = DynamicConfig()
            val snap = config.snapshot()
            assertEquals(snap.mode, SchedulingMode.ENHANCED, "mode should default to ENHANCED")
            assertTrue(snap.qualityEnabled, "quality should be enabled by default")
        }

        test("test_config_from_cli") {
            val config = DynamicConfig.fromCli(
                SchedulingMode.CLASSIC,
                noQuality = true,
                noStallDeselect = false,
                stallMinInFlight = STALL_MIN_IN_FLIGHT_PACKETS,
                stallAckStaleMs = STALL_ACK_STALE_MS,
                connTimeoutMs = CONN_TIMEOUT_MS,
                noRehome = false,
            )
            val snap = config.snapshot()
            assertEquals(snap.mode, SchedulingMode.CLASSIC, "mode should be CLASSIC")
            assertFalse(snap.qualityEnabled, "no_quality=true means disabled")
            assertTrue(snap.stallDeselect, "stall_deselect on by default (no_stall_deselect=false)")
        }

        test("test_effective_quality") {
            // Classic mode - quality scoring never effective
            val snap = ConfigSnapshot(
                mode = SchedulingMode.CLASSIC,
                qualityEnabled = true,
                stallDeselect = true,
                stallMinInFlight = STALL_MIN_IN_FLIGHT_PACKETS,
                stallAckStaleMs = STALL_ACK_STALE_MS,
                connTimeoutMs = CONN_TIMEOUT_MS,
                negotiatedLatencyMs = 0,
            )
            assertFalse(snap.effectiveQualityEnabled(), "quality never effective in CLASSIC mode")

            // Enhanced mode - effective
            val snapEnhanced = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
                stallDeselect = true,
                stallMinInFlight = STALL_MIN_IN_FLIGHT_PACKETS,
                stallAckStaleMs = STALL_ACK_STALE_MS,
                connTimeoutMs = CONN_TIMEOUT_MS,
                negotiatedLatencyMs = 0,
            )
            assertTrue(snapEnhanced.effectiveQualityEnabled(), "quality effective in ENHANCED mode")
        }

        test("test_concurrent_access") {
            val config = DynamicConfig()
            var writerDone = false
            var readerDone = false

            val writerThread = Thread {
                for (i in 0 until 100) {
                    config.setMode(SchedulingMode.CLASSIC)
                    config.setMode(SchedulingMode.ENHANCED)
                }
                writerDone = true
            }

            val readerThread = Thread {
                for (i in 0 until 100) {
                    config.snapshot()
                }
                readerDone = true
            }

            writerThread.start()
            readerThread.start()
            writerThread.join()
            readerThread.join()

            assertTrue(writerDone, "writer thread should complete")
            assertTrue(readerDone, "reader thread should complete")
        }
    }

    // ──── config_tests.rs (extended config) tests ────────────────────────────────

    suite("config_tests") {
        test("test_config_new") {
            val config = DynamicConfig()
            val snap = config.snapshot()
            assertEquals(snap.mode, SchedulingMode.ENHANCED, "default mode should be ENHANCED")
            assertTrue(snap.qualityEnabled, "quality should be enabled by default")
        }

        test("test_config_from_cli") {
            val config = DynamicConfig.fromCli(
                SchedulingMode.ENHANCED,
                noQuality = false,
                noStallDeselect = false,
                stallMinInFlight = STALL_MIN_IN_FLIGHT_PACKETS,
                stallAckStaleMs = STALL_ACK_STALE_MS,
                connTimeoutMs = CONN_TIMEOUT_MS,
                noRehome = false,
            )
            val snap = config.snapshot()
            assertEquals(snap.mode, SchedulingMode.ENHANCED, "mode should be ENHANCED")
            assertTrue(snap.qualityEnabled, "quality should be enabled")
            assertTrue(snap.stallDeselect, "stall_deselect should be enabled")

            val config2 = DynamicConfig.fromCli(
                SchedulingMode.CLASSIC,
                noQuality = true,
                noStallDeselect = true,
                stallMinInFlight = STALL_MIN_IN_FLIGHT_PACKETS,
                stallAckStaleMs = STALL_ACK_STALE_MS,
                connTimeoutMs = CONN_TIMEOUT_MS,
                noRehome = false,
            )
            val snap2 = config2.snapshot()
            assertEquals(snap2.mode, SchedulingMode.CLASSIC, "mode should be CLASSIC")
            assertFalse(snap2.qualityEnabled, "quality should be disabled")
            assertFalse(snap2.stallDeselect, "no_stall_deselect=true disables it")
        }

        test("test_conn_timeout_clamped") {
            val config = DynamicConfig()
            assertEquals(config.setConnTimeoutMs(100), CONN_TIMEOUT_MS_MIN, "floor value")
            assertEquals(config.setConnTimeoutMs(120_000), CONN_TIMEOUT_MS_MAX, "ceiling value")
            assertEquals(config.setConnTimeoutMs(9_000), 9_000L, "in-range value")
            assertEquals(config.snapshot().connTimeoutMs, 9_000L, "snapshot reflects set value")
        }

        test("test_config_concurrent_access") {
            val config = DynamicConfig()
            var writerDone = false
            var readerDone = false

            val writerThread = Thread {
                for (i in 0 until 100) {
                    config.setMode(SchedulingMode.CLASSIC)
                    config.setMode(SchedulingMode.ENHANCED)
                }
                writerDone = true
            }

            val readerThread = Thread {
                for (i in 0 until 100) {
                    config.snapshot()
                    Thread.sleep(1)
                }
                readerDone = true
            }

            writerThread.start()
            readerThread.start()
            writerThread.join()
            readerThread.join()

            assertTrue(writerDone, "writer should complete")
            assertTrue(readerDone, "reader should complete")
        }

        test("test_effective_quality_enabled") {
            // classic mode - quality never effective
            val snap = ConfigSnapshot(
                mode = SchedulingMode.CLASSIC,
                qualityEnabled = true,
                stallDeselect = true,
                stallMinInFlight = STALL_MIN_IN_FLIGHT_PACKETS,
                stallAckStaleMs = STALL_ACK_STALE_MS,
                connTimeoutMs = CONN_TIMEOUT_MS,
                negotiatedLatencyMs = 0,
            )
            assertFalse(snap.effectiveQualityEnabled(), "quality never effective in CLASSIC")

            // enhanced mode - can be effective
            val snap2 = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
                stallDeselect = true,
                stallMinInFlight = STALL_MIN_IN_FLIGHT_PACKETS,
                stallAckStaleMs = STALL_ACK_STALE_MS,
                connTimeoutMs = CONN_TIMEOUT_MS,
                negotiatedLatencyMs = 0,
            )
            assertTrue(snap2.effectiveQualityEnabled(), "quality effective in ENHANCED")
        }
    }

    // ──── control.rs tests ───────────────────────────────────────────────────────

    suite("control") {
        test("parse_error_returns_jsonrpc_error") {
            val config = DynamicConfig()
            val resp = dispatch(config, null, null, "not valid json")
            requireNotNull(resp) { "dispatch should return a response for invalid JSON" }
            val v = Json.parse(resp.toJson())
            val errObj = (v as? Map<*, *>)?.get("error") as? Map<*, *>
            assertEquals(errObj?.get("code"), PARSE_ERROR.toLong(), "error code should be PARSE_ERROR")
            assertEquals((v as? Map<*, *>)?.get("id"), null, "id should be null")
        }

        test("notification_returns_none") {
            val config = DynamicConfig()
            // set_mode happens to work as a notification; no id means no response.
            val req = """{"jsonrpc":"2.0","method":"set_mode","params":{"mode":"classic"}}"""
            val resp = dispatch(config, null, null, req)
            assertEquals(resp, null, "notification should return null")
            assertEquals(config.mode(), SchedulingMode.CLASSIC, "mode should be updated")
        }

        test("set_mode_happy_path") {
            val config = DynamicConfig()
            val req = """{"jsonrpc":"2.0","id":1,"method":"set_mode","params":{"mode":"classic"}}"""
            val resp = dispatch(config, null, null, req)
            requireNotNull(resp) { "dispatch should return a response" }
            val v = Json.parse(resp.toJson()) as Map<*, *>
            val result = v["result"] as? Map<*, *>
            assertEquals("classic", result?.get("mode"), "result.mode should be the wire name")
            assertEquals(v["id"], 1L, "id should be 1")
            assertEquals(config.mode(), SchedulingMode.CLASSIC, "config mode should be CLASSIC")
        }

        test("unknown_method_returns_method_not_found") {
            val config = DynamicConfig()
            val req = """{"jsonrpc":"2.0","id":"abc","method":"noop"}"""
            val resp = dispatch(config, null, null, req)
            requireNotNull(resp) { "dispatch should return a response" }
            val v = Json.parse(resp.toJson()) as Map<*, *>
            val errObj = (v["error"] as? Map<*, *>)
            assertEquals(errObj?.get("code"), METHOD_NOT_FOUND.toLong(), "error code should be METHOD_NOT_FOUND")
            assertEquals(v["id"], "abc", "id should match")
        }

        test("invalid_params_returns_invalid_params") {
            val config = DynamicConfig()
            val req = """{"jsonrpc":"2.0","id":7,"method":"set_quality","params":{}}"""
            val resp = dispatch(config, null, null, req)
            requireNotNull(resp) { "dispatch should return a response" }
            val v = Json.parse(resp.toJson()) as Map<*, *>
            val errObj = (v["error"] as? Map<*, *>)
            assertEquals(errObj?.get("code"), INVALID_PARAMS.toLong(), "error code should be INVALID_PARAMS")
        }

        test("wrong_jsonrpc_version_rejects") {
            val config = DynamicConfig()
            val req = """{"jsonrpc":"1.0","id":1,"method":"get_status"}"""
            val resp = dispatch(config, null, null, req)
            requireNotNull(resp) { "dispatch should return a response" }
            val v = Json.parse(resp.toJson()) as Map<*, *>
            val errObj = (v["error"] as? Map<*, *>)
            assertEquals(errObj?.get("code"), INVALID_REQUEST.toLong(), "error code should be INVALID_REQUEST")
        }

        test("get_status_returns_all_fields") {
            val config = DynamicConfig()
            val req = """{"jsonrpc":"2.0","id":1,"method":"get_status"}"""
            val resp = dispatch(config, null, null, req)
            requireNotNull(resp) { "dispatch should return a response" }
            val v = Json.parse(resp.toJson()) as Map<*, *>
            val result = v["result"] as? Map<*, *>
            assertTrue(result?.get("mode") is String, "mode should be string")
            assertTrue(result?.get("quality_enabled") is Boolean, "quality_enabled should be boolean")
        }
    }

    // ──── toml_config.rs tests ───────────────────────────────────────────────────

    suite("toml_config") {
        test("absent_keys_are_none") {
            val cfg = TomlConfig.parse("mode = \"classic\"")
            assertEquals(SchedulingMode.CLASSIC, cfg.mode)
            assertNull(cfg.noQuality)
            assertNull(cfg.connTimeoutMs)
        }

        test("full_toml") {
            val cfg = TomlConfig.parse(
                """
                mode = "enhanced"
                no_quality = true
                no_stall_deselect = true
                stall_min_in_flight = 64
                stall_ack_stale_ms = 1500
                conn_timeout_ms = 8000
                """.trimIndent(),
            )
            assertEquals(SchedulingMode.ENHANCED, cfg.mode)
            assertEquals(true, cfg.noQuality)
            assertEquals(true, cfg.noStallDeselect)
            assertEquals(64, cfg.stallMinInFlight)
            assertEquals(1500L, cfg.stallAckStaleMs)
            assertEquals(8000L, cfg.connTimeoutMs)
        }

        test("unknown_key_is_rejected") {
            val err = assertFailsWith<TomlConfigException> { TomlConfig.parse("switch_hysteresis = 1.2") }
            assertTrue(err.message!!.contains("switch_hysteresis"), err.message)
        }

        test("invalid_mode_is_rejected") {
            val err = assertFailsWith<TomlConfigException> { TomlConfig.parse("mode = \"fastest\"") }
            assertTrue(err.message!!.contains("fastest"), err.message)
        }
    }

    // ──── main.rs tests (CLI + config-file precedence) ───────────────────────────

    fun parseWithFile(flags: Array<String>, file: String): ParsedArgs =
        parseArgs(arrayOf("5000", "rec.example", "5001", "ips.txt") + flags).applyConfigFile(TomlConfig.parse(file))

    suite("main_cli_config") {
        test("file_overrides_clap_defaults") {
            val cli = parseWithFile(
                arrayOf(),
                """
                mode = "classic"
                no_quality = true
                no_stall_deselect = true
                stall_min_in_flight = 64
                stall_ack_stale_ms = 1500
                conn_timeout_ms = 8000
                """.trimIndent(),
            )
            assertEquals(SchedulingMode.CLASSIC, cli.mode)
            assertTrue(cli.noQuality)
            assertTrue(cli.noStallDeselect)
            assertEquals(64, cli.stallMinInFlight)
            assertEquals(1500L, cli.stallAckStaleMs)
            assertEquals(8000L, cli.connTimeoutMs)
        }

        test("typed_flag_overrides_file_even_at_default_value") {
            val cli = parseWithFile(
                arrayOf("--conn-timeout-ms", CONN_TIMEOUT_MS.toString(), "--no-quality"),
                "conn_timeout_ms = 8000\nno_quality = false",
            )
            assertEquals(CONN_TIMEOUT_MS, cli.connTimeoutMs)
            assertTrue(cli.noQuality)
        }

        test("absent_file_keys_keep_cli_values") {
            val cli = parseWithFile(arrayOf("--stall-ack-stale-ms", "2000"), "")
            assertEquals(SchedulingMode.ENHANCED, cli.mode)
            assertEquals(2000L, cli.stallAckStaleMs)
            assertEquals(CONN_TIMEOUT_MS, cli.connTimeoutMs)
        }
    }


    // ──── version.rs tests ───────────────────────────────────────────────────────

    suite("version") {
        test("full_git_context_is_rendered_verbatim") {
            val line = composeVersionLine("3.2.0", "main", "abc1234", "", "srtla_send")
            assertEquals(line, "3.2.0 (main@abc1234) [srtla_send]", "full git context")
        }

        test("dirty_worktree_suffixes_the_hash") {
            val line = composeVersionLine("3.2.0", "main", "abc1234", "-dirty", "srtla_send")
            assertEquals(line, "3.2.0 (main@abc1234-dirty) [srtla_send]", "dirty suffix")
        }

        test("detached_head_falls_back_to_the_bare_hash") {
            val line = composeVersionLine("3.2.0", "", "abc1234", "", "srtla_send")
            assertEquals(line, "3.2.0 (abc1234) [srtla_send]", "detached head")
        }

        test("no_git_context_omits_the_parenthetical") {
            val line = composeVersionLine("3.2.0", "", "", "", "srtla_send")
            assertEquals(line, "3.2.0 [srtla_send]", "no git context")
        }

        test("branch_without_a_hash_is_dropped") {
            val line = composeVersionLine("3.2.0", "main", "", "", "srtla_send")
            assertEquals(line, "3.2.0 [srtla_send]", "branch without hash dropped")
        }

        test("built_version_line_never_says_unknown") {
            val line = versionLine()
            assertFalse(
                line.contains("unknown"),
                "version line should not leak placeholder: $line",
            )
            assertFalse(
                line.contains("()"),
                "version line should not have empty parenthetical: $line",
            )
        }
    }

    // ──── metrics.rs tests ───────────────────────────────────────────────────────

    suite("metrics") {
        test("render_contains_core_metric_lines") {
            val stats = SharedStats()
            val config = DynamicConfig()
            val cw = CriticalWindow()
            val text = renderMetrics(stats, config, cw)
            assertTrue(text.contains("srtla_send_active_links"), "should contain active_links metric")
            assertTrue(text.contains("srtla_send_total_links"), "should contain total_links metric")
            assertTrue(text.contains("srtla_send_critical_windows_total"), "should contain critical_windows metric")
            assertTrue(text.contains("srtla_send_mode"), "should contain mode metric")
        }

        test("render_outputs_valid_prom_shape") {
            val stats = SharedStats()
            val config = DynamicConfig()
            val cw = CriticalWindow()
            val text = renderMetrics(stats, config, cw)
            for (line in text.lines()) {
                assertTrue(
                    line.all { it.code < 128 },
                    "non-ASCII metric line: $line",
                )
            }
        }

        test("request_path_parses_standard_get") {
            val req = "GET /metrics HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
            val path = requestPath(req, req.size)
            assertEquals(path, "/metrics", "should parse standard GET request")
        }

        test("request_path_rejects_post") {
            val req = "POST /metrics HTTP/1.1\r\n\r\n".toByteArray()
            val path = requestPath(req, req.size)
            assertEquals(path, null, "should reject POST request")
        }

        test("request_path_rejects_malformed") {
            assertEquals(requestPath(byteArrayOf(), 0), null, "empty request")
            assertEquals(requestPath("not http".toByteArray(), 8), null, "malformed request")
        }
    }

    // ──── stats.rs tests ─────────────────────────────────────────────────────────

    suite("stats") {
        test("test_shared_stats_new") {
            val stats = SharedStats()
            val snapshot = stats.get()
            assertEquals(snapshot.activeLinks, 0, "initial active_links should be 0")
            assertEquals(snapshot.totalLinks, 0, "initial total_links should be 0")
        }

        test("test_shared_stats_empty_update") {
            val stats = SharedStats()
            val config = ConfigSnapshot(
                mode = SchedulingMode.ENHANCED,
                qualityEnabled = true,
                stallDeselect = true,
                stallMinInFlight = STALL_MIN_IN_FLIGHT_PACKETS,
                stallAckStaleMs = STALL_ACK_STALE_MS,
                connTimeoutMs = CONN_TIMEOUT_MS,
                negotiatedLatencyMs = 0,
            )
            stats.update(emptyList(), config, null, null)
            val snapshot = stats.get()
            assertEquals(snapshot.mode, "enhanced", "mode should be enhanced")
            assertTrue(snapshot.qualityEnabled, "quality should be enabled")
        }

        test("test_to_json_contains_expected_fields") {
            val stats = SharedStats()
            val json = stats.toJson()
            assertTrue(json.contains("\"mode\""), "should contain mode field")
            assertTrue(json.contains("\"active_links\""), "should contain active_links field")
            assertTrue(json.contains("\"total_window\""), "should contain total_window field")
            assertTrue(json.contains("\"links\""), "should contain links field")
        }
    }
}
