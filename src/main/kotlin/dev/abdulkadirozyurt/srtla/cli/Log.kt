// Ported from irlserver/srtla_send v3.0.0 (MIT)
// Source: src/main.rs (tracing_subscriber setup)
//
// Log — thin wrapper around java.util.logging.
//
// Rust uses tracing_subscriber with RUST_LOG env var.
// JVM uses java.util.logging with SRTLA_LOG env var.
//
// SRTLA_LOG values: "debug", "info" (default), "warning", "error", "off"
// Maps to java.util.logging.Level: FINE, INFO, WARNING, SEVERE, OFF.
//
// This module configures the root logger and provides a simple Log object
// for consistent formatting across the application.
package dev.abdulkadirozyurt.srtla.cli

import java.util.logging.*

/**
 * Configure java.util.logging based on SRTLA_LOG environment variable.
 * Mirrors Rust tracing_subscriber ENV_FILTER setup in src/main.rs.
 *
 * SRTLA_LOG=debug  → Level.FINE  (verbose)
 * SRTLA_LOG=info   → Level.INFO  (default)
 * SRTLA_LOG=warn   → Level.WARNING
 * SRTLA_LOG=error  → Level.SEVERE
 * SRTLA_LOG=off    → Level.OFF
 */
fun configureLogging() {
    val envLevel = System.getenv("SRTLA_LOG")?.lowercase() ?: "info"

    val jvmLevel = when (envLevel) {
        "debug", "trace"    -> Level.FINE
        "info"              -> Level.INFO
        "warn", "warning"   -> Level.WARNING
        "error", "severe"   -> Level.SEVERE
        "off"               -> Level.OFF
        else                -> Level.INFO
    }

    // Remove all existing handlers from root logger
    val rootLogger = Logger.getLogger("")
    rootLogger.handlers.forEach { rootLogger.removeHandler(it) }

    // Install a single clean ConsoleHandler
    val handler = ConsoleHandler()
    handler.level = jvmLevel
    handler.formatter = SrtlaLogFormatter()

    rootLogger.level = jvmLevel
    rootLogger.addHandler(handler)
}

/**
 * Simple log formatter: [LEVEL] logger.name: message
 * Mirrors Rust tracing output style (no timestamp by default, target=false).
 */
private class SrtlaLogFormatter : Formatter() {
    override fun format(record: LogRecord): String {
        val levelTag = when (record.level) {
            Level.FINE    -> "DEBUG"
            Level.INFO    -> "INFO "
            Level.WARNING -> "WARN "
            Level.SEVERE  -> "ERROR"
            else          -> record.level.name.take(5).padEnd(5)
        }
        val name = record.loggerName.substringAfterLast('.').take(20)
        val msg = record.message
        val thrown = record.thrown?.let { "\n  ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        return "[$levelTag] $name: $msg$thrown\n"
    }
}
