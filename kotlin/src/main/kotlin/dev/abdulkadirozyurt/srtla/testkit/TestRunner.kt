package dev.abdulkadirozyurt.srtla.testkit

import dev.abdulkadirozyurt.srtla.tests.registerProtocolTests
import dev.abdulkadirozyurt.srtla.tests.registerFilterTests
import dev.abdulkadirozyurt.srtla.tests.registerConnectionTests
import dev.abdulkadirozyurt.srtla.tests.registerRegistrationTests
import dev.abdulkadirozyurt.srtla.tests.registerRttTrackerTests
import dev.abdulkadirozyurt.srtla.tests.registerSenderTests
import dev.abdulkadirozyurt.srtla.tests.registerSelectionTests
// Faz D test suites
import dev.abdulkadirozyurt.srtla.tests.registerConfigTests
import dev.abdulkadirozyurt.srtla.tests.registerStatsTests
import dev.abdulkadirozyurt.srtla.tests.registerCliArgTests

fun main() {
    // ── Register all suites ──────────────────────────────────────────────────
    registerProtocolTests()
    registerFilterTests()
    registerRttTrackerTests()
    registerConnectionTests()
    registerRegistrationTests()
    registerSenderTests()
    registerSelectionTests()
    // Faz D
    registerConfigTests()
    registerStatsTests()
    registerCliArgTests()

    // ── Run ─────────────────────────────────────────────────────────────────
    println()
    println("=== srtla-sender-kotlin testkit ===")
    println()

    val results = runAllSuites()
    val ok = printSummary(results)
    if (!ok) kotlin.system.exitProcess(1)
}
