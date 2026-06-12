package dev.abdulkadirozyurt.srtla.testkit

import dev.abdulkadirozyurt.srtla.tests.registerProtocolTests
import dev.abdulkadirozyurt.srtla.tests.registerFilterTests
import dev.abdulkadirozyurt.srtla.tests.registerConnectionTests
import dev.abdulkadirozyurt.srtla.tests.registerRegistrationTests
import dev.abdulkadirozyurt.srtla.tests.registerRttTrackerTests
import dev.abdulkadirozyurt.srtla.tests.registerSenderTests
import dev.abdulkadirozyurt.srtla.tests.registerSelectionTests

fun main() {
    // ── Register all suites ──────────────────────────────────────────────────
    registerProtocolTests()
    registerFilterTests()
    registerRttTrackerTests()
    registerConnectionTests()
    registerRegistrationTests()
    registerSenderTests()
    registerSelectionTests()

    // ── Run ─────────────────────────────────────────────────────────────────
    println()
    println("=== srtla-sender-kotlin testkit ===")
    println()

    val results = runAllSuites()
    val ok = printSummary(results)
    if (!ok) kotlin.system.exitProcess(1)
}
