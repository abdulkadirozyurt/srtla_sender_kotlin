package dev.abdulkadirozyurt.srtla.testkit

// Import all test suites so they register themselves via their top-level suite{} calls.
// Each test file calls suite() at the top level (file scope), which registers lazily;
// but Kotlin top-level functions are not called until the class is loaded.
// We therefore explicitly reference each suite registration object here.
import dev.abdulkadirozyurt.srtla.tests.registerProtocolTests

fun main() {
    // ── Register all suites ──────────────────────────────────────────────────
    registerProtocolTests()

    // ── Run ─────────────────────────────────────────────────────────────────
    println()
    println("=== srtla-sender-kotlin testkit ===")
    println()

    val results = runAllSuites()
    val ok = printSummary(results)
    if (!ok) kotlin.system.exitProcess(1)
}
