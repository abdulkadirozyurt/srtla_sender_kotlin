// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Copyright (c) 2025-2026 Abdulkadir Özyurt
// Test suite runner — registers all suites and runs them via the zero-dependency testkit.
package dev.abdulkadirozyurt.srtla.testkit

import dev.abdulkadirozyurt.srtla.e2e.registerE2ETests
import dev.abdulkadirozyurt.srtla.tests.registerConfigTelemetryTests
import dev.abdulkadirozyurt.srtla.tests.registerConnectionTests
import dev.abdulkadirozyurt.srtla.tests.registerCoreTests
import dev.abdulkadirozyurt.srtla.tests.registerHousekeepingRehomeTests
import dev.abdulkadirozyurt.srtla.tests.registerLinkCcTests
import dev.abdulkadirozyurt.srtla.tests.registerLinkWeightTests
import dev.abdulkadirozyurt.srtla.tests.registerProtocolTests
import dev.abdulkadirozyurt.srtla.tests.registerRegistrationTests
import dev.abdulkadirozyurt.srtla.tests.registerSelectionTests
import dev.abdulkadirozyurt.srtla.tests.registerSenderTests
import dev.abdulkadirozyurt.srtla.tests.registerShellTests
import dev.abdulkadirozyurt.srtla.tests.registerStallDeselectTests
import dev.abdulkadirozyurt.srtla.tests.registerWireConformanceTests

fun main(args: Array<String>) {
    registerProtocolTests()
    registerWireConformanceTests()
    registerCoreTests()
    registerConnectionTests()
    registerRegistrationTests()
    registerSelectionTests()
    registerLinkCcTests()
    registerSenderTests()
    registerLinkWeightTests()
    registerStallDeselectTests()
    registerShellTests()
    registerHousekeepingRehomeTests()
    registerConfigTelemetryTests()
    registerE2ETests()

    // Optional filter: run only suites whose name contains the first argument.
    val filter = args.firstOrNull()

    println()
    println("=== srtla-sender-kotlin testkit ===")
    println()

    val results = runAllSuites(filter)
    val ok = printSummary(results)
    if (!ok) kotlin.system.exitProcess(1)
}
