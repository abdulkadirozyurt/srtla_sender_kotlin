// Source: src/main.rs (Cli struct / clap parsing)
// Additional unit tests for CLI argument parser (Kotlin-specific, no Rust equivalent).
package dev.abdulkadirozyurt.srtla.tests

import dev.abdulkadirozyurt.srtla.cli.parseArgs
import dev.abdulkadirozyurt.srtla.sender.selection.SchedulingMode
import dev.abdulkadirozyurt.srtla.testkit.*

fun registerCliArgTests() {

suite("CLI arg parser - version flag") {
    test("-v sets printVersion") {
        val p = parseArgs(arrayOf("-v"))
        assertNotNull(p)
        assertTrue(p!!.printVersion)
    }
    test("--version sets printVersion") {
        val p = parseArgs(arrayOf("--version"))
        assertNotNull(p)
        assertTrue(p!!.printVersion)
    }
}

suite("CLI arg parser - positional args") {
    test("4 positional args parsed correctly") {
        val p = parseArgs(arrayOf("5000", "example.com", "5001", "/tmp/ips.txt"))
        assertNotNull(p)
        assertEquals(5000, p!!.localSrtPort)
        assertEquals("example.com", p.receiverHost)
        assertEquals(5001, p.receiverPort)
        assertEquals("/tmp/ips.txt", p.ipsFile)
    }
    test("missing positional args returns null") {
        val p = parseArgs(arrayOf("5000", "example.com"))
        assertNull(p)
    }
    test("no args returns null") {
        val p = parseArgs(arrayOf())
        assertNull(p)
    }
}

suite("CLI arg parser - defaults") {
    fun baseArgs() = arrayOf("5000", "host", "5001", "/tmp/ips.txt")
    test("default mode is enhanced") {
        val p = parseArgs(baseArgs())
        assertEquals(SchedulingMode.ENHANCED, p!!.mode)
    }
    test("default no-quality is false") {
        val p = parseArgs(baseArgs())
        assertFalse(p!!.noQuality)
    }
    test("default exploration is false") {
        val p = parseArgs(baseArgs())
        assertFalse(p!!.exploration)
    }
    test("default rtt-delta-ms is 30") {
        val p = parseArgs(baseArgs())
        assertEquals(30, p!!.rttDeltaMs)
    }
    test("default control-port is null") {
        val p = parseArgs(baseArgs())
        assertNull(p!!.controlPort)
    }
}

suite("CLI arg parser - mode option") {
    fun baseArgs(vararg extra: String) = arrayOf("5000", "host", "5001", "/tmp/ips.txt", *extra)
    test("--mode classic") {
        val p = parseArgs(baseArgs("--mode", "classic"))
        assertEquals(SchedulingMode.CLASSIC, p!!.mode)
    }
    test("--mode enhanced") {
        val p = parseArgs(baseArgs("--mode", "enhanced"))
        assertEquals(SchedulingMode.ENHANCED, p!!.mode)
    }
    test("--mode rtt-threshold") {
        val p = parseArgs(baseArgs("--mode", "rtt-threshold"))
        assertEquals(SchedulingMode.RTT_THRESHOLD, p!!.mode)
    }
    test("--mode edpf") {
        val p = parseArgs(baseArgs("--mode", "edpf"))
        assertEquals(SchedulingMode.EDPF, p!!.mode)
    }
    test("unknown --mode returns null") {
        val p = parseArgs(baseArgs("--mode", "unknown"))
        assertNull(p)
    }
}

suite("CLI arg parser - flags") {
    fun baseArgs(vararg extra: String) = arrayOf("5000", "host", "5001", "/tmp/ips.txt", *extra)
    test("--no-quality sets noQuality=true") {
        val p = parseArgs(baseArgs("--no-quality"))
        assertTrue(p!!.noQuality)
    }
    test("--exploration sets exploration=true") {
        val p = parseArgs(baseArgs("--exploration"))
        assertTrue(p!!.exploration)
    }
    test("--rtt-delta-ms 100 sets 100") {
        val p = parseArgs(baseArgs("--rtt-delta-ms", "100"))
        assertEquals(100, p!!.rttDeltaMs)
    }
    test("--rtt-delta-ms invalid returns null") {
        val p = parseArgs(baseArgs("--rtt-delta-ms", "abc"))
        assertNull(p)
    }
    test("--control-port 9090 sets 9090") {
        val p = parseArgs(baseArgs("--control-port", "9090"))
        assertEquals(9090, p!!.controlPort)
    }
    test("--control-port invalid returns null") {
        val p = parseArgs(baseArgs("--control-port", "notaport"))
        assertNull(p)
    }
    test("unknown option returns null") {
        val p = parseArgs(baseArgs("--unknown-flag"))
        assertNull(p)
    }
}

suite("CLI arg parser - combined flags") {
    test("all options together") {
        val p = parseArgs(arrayOf(
            "5000", "myhost.example", "5001", "/etc/ips.txt",
            "--mode", "rtt-threshold",
            "--no-quality",
            "--exploration",
            "--rtt-delta-ms", "75",
            "--control-port", "9000",
        ))
        assertNotNull(p)
        assertEquals(SchedulingMode.RTT_THRESHOLD, p!!.mode)
        assertTrue(p.noQuality)
        assertTrue(p.exploration)
        assertEquals(75, p.rttDeltaMs)
        assertEquals(9000, p.controlPort)
        assertEquals("myhost.example", p.receiverHost)
    }
}

} // registerCliArgTests
