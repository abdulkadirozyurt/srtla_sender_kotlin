// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Zero-dependency mini test framework — no JUnit, no Maven.
// Usage:
//   suite("My Suite") {
//       test("some name") { assertEquals(1, 1) }
//   }
// Then run TestRunner main to execute all registered suites.
package dev.abdulkadirozyurt.srtla.testkit

// ── Registration ────────────────────────────────────────────────────────────

data class TestCase(val name: String, val body: () -> Unit)
data class Suite(val name: String, val cases: List<TestCase>)

private val _suites = mutableListOf<Suite>()
val allSuites: List<Suite> get() = _suites

class SuiteBuilder(private val suiteName: String) {
    private val cases = mutableListOf<TestCase>()
    fun test(name: String, body: () -> Unit) { cases += TestCase(name, body) }
    fun build(): Suite = Suite(suiteName, cases)
}

fun suite(name: String, block: SuiteBuilder.() -> Unit) {
    val b = SuiteBuilder(name)
    b.block()
    _suites += b.build()
}

// ── Assertion failure ────────────────────────────────────────────────────────

class AssertionError(message: String) : Exception(message)

// ── Assertions ───────────────────────────────────────────────────────────────

fun assertEquals(expected: Any?, actual: Any?, message: String? = null) {
    if (expected != actual) {
        val msg = message ?: "expected=<$expected> but was=<$actual>"
        throw AssertionError(msg)
    }
}

fun assertNotEquals(unexpected: Any?, actual: Any?, message: String? = null) {
    if (unexpected == actual) {
        val msg = message ?: "expected values to differ but both were=<$actual>"
        throw AssertionError(msg)
    }
}

fun assertTrue(value: Boolean, message: String? = null) {
    if (!value) throw AssertionError(message ?: "expected true but was false")
}

fun assertFalse(value: Boolean, message: String? = null) {
    if (value) throw AssertionError(message ?: "expected false but was true")
}

fun assertNull(value: Any?, message: String? = null) {
    if (value != null) throw AssertionError(message ?: "expected null but was=<$value>")
}

fun assertNotNull(value: Any?, message: String? = null) {
    if (value == null) throw AssertionError(message ?: "expected non-null value")
}

fun assertContentEquals(expected: ByteArray, actual: ByteArray, message: String? = null) {
    if (!expected.contentEquals(actual)) {
        val msg = message ?: "ByteArray mismatch:\n  expected=${expected.toHexString()}\n  actual  =${actual.toHexString()}"
        throw AssertionError(msg)
    }
}

fun assertContentEquals(expected: IntArray, actual: IntArray, message: String? = null) {
    if (!expected.contentEquals(actual)) {
        val msg = message ?: "IntArray mismatch:\n  expected=${expected.toList()}\n  actual  =${actual.toList()}"
        throw AssertionError(msg)
    }
}

fun assertContentEquals(expected: LongArray, actual: LongArray, message: String? = null) {
    if (!expected.contentEquals(actual)) {
        val msg = message ?: "LongArray mismatch:\n  expected=${expected.toList()}\n  actual  =${actual.toList()}"
        throw AssertionError(msg)
    }
}

/** Assert that two lists of Long (used for NAK/ACK sequences) are equal. */
fun assertSequenceEquals(expected: List<Long>, actual: List<Long>, message: String? = null) {
    if (expected != actual) {
        val msg = message ?: "Sequence mismatch:\n  expected=$expected\n  actual  =$actual"
        throw AssertionError(msg)
    }
}

/**
 * Assert that the given block throws an exception of type [T].
 * Returns the caught exception so callers can inspect its message.
 */
inline fun <reified T : Throwable> assertFailsWith(message: String? = null, block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError(
            (message ?: "Expected ${T::class.simpleName} but got ${e::class.simpleName}") + ": $e"
        )
    }
    throw AssertionError(message ?: "Expected ${T::class.simpleName} but no exception was thrown")
}

// ── Helpers ──────────────────────────────────────────────────────────────────

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

// ── ANSI colours (auto-disabled when stdout is not a TTY) ────────────────────

private val isTty: Boolean = System.console() != null
private fun green(s: String) = if (isTty) "[32m$s[0m" else s
private fun red(s: String)   = if (isTty) "[31m$s[0m" else s
private fun bold(s: String)  = if (isTty) "[1m$s[0m"  else s

// ── Runner ───────────────────────────────────────────────────────────────────

data class TestResult(val suite: String, val test: String, val error: Throwable?)

fun runAllSuites(): List<TestResult> {
    val results = mutableListOf<TestResult>()
    for (s in allSuites) {
        for (tc in s.cases) {
            val err = try { tc.body(); null } catch (e: Throwable) { e }
            results += TestResult(s.name, tc.name, err)
        }
    }
    return results
}

fun printSummary(results: List<TestResult>): Boolean {
    var passed = 0; var failed = 0
    for (r in results) {
        if (r.error == null) {
            println(green("  PASS") + "  [${r.suite}] ${r.test}")
            passed++
        } else {
            println(red("  FAIL") + "  [${r.suite}] ${r.test}")
            println("        ${r.error.message}")
            failed++
        }
    }
    println()
    val total = passed + failed
    val summary = "$total tests: ${passed} passed, ${failed} failed"
    println(if (failed == 0) bold(green(summary)) else bold(red(summary)))
    return failed == 0
}
