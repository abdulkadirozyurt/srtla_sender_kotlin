// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: crates/srtla-core/src/mode.rs
//
// v4 culled the unproven selection modes: rtt-threshold and edpf are gone.
package dev.abdulkadirozyurt.srtla.core

/** Scheduling mode for connection selection. */
enum class SchedulingMode(val wireName: String) {
    /**
     * Pure capacity-based selection (window / in_flight), matching the original
     * C implementation. Kept as a known-good baseline and fallback.
     */
    CLASSIC("classic"),

    /** Quality-aware selection with dampening (default). */
    ENHANCED("enhanced");

    fun isClassic(): Boolean = this == CLASSIC

    override fun toString(): String = wireName

    companion object {
        val DEFAULT: SchedulingMode = ENHANCED

        /**
         * Parse `classic` or `enhanced`. Throws [IllegalArgumentException] with the
         * same wording as the Rust `FromStr` impl for anything else.
         */
        fun parse(s: String): SchedulingMode = when (s) {
            "classic" -> CLASSIC
            "enhanced" -> ENHANCED
            else -> throw IllegalArgumentException("invalid mode '$s': use classic or enhanced")
        }

        fun parseOrNull(s: String): SchedulingMode? = when (s) {
            "classic" -> CLASSIC
            "enhanced" -> ENHANCED
            else -> null
        }
    }
}
