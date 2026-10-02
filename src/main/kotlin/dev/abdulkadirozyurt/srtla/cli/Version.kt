// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/version.rs

package dev.abdulkadirozyurt.srtla.cli

/**
 * Compose the `--version` line from the crate identity plus whatever git
 * metadata the build could resolve.
 *
 * `branch`, `hash`, and `dirty` are the raw build values: each is empty
 * when it could not be determined. The parenthetical is emitted only when
 * there is a commit to name.
 *
 * Examples:
 * - ("3.2.0", "main", "abc1234", "", "srtla_send") -> "3.2.0 (main@abc1234) [srtla_send]"
 * - ("3.2.0", "main", "abc1234", "-dirty", "srtla_send") -> "3.2.0 (main@abc1234-dirty) [srtla_send]"
 * - ("3.2.0", "", "abc1234", "", "srtla_send") -> "3.2.0 (abc1234) [srtla_send]"
 * - ("3.2.0", "", "", "", "srtla_send") -> "3.2.0 [srtla_send]"
 */
fun composeVersionLine(
    version: String,
    branch: String,
    hash: String,
    dirty: String,
    packageName: String
): String {
    val metadata = buildMetadata(branch, hash, dirty)
    return if (metadata != null) {
        "$version ($metadata) [$packageName]"
    } else {
        "$version [$packageName]"
    }
}

/**
 * The `branch@hash-dirty` build-metadata fragment, or null when the build had
 * no commit to name.
 */
private fun buildMetadata(branch: String, hash: String, dirty: String): String? {
    // The hash is what identifies the build. A branch without one names nothing
    // reproducible, so it is never emitted alone.
    if (hash.isEmpty()) {
        return null
    }
    return if (branch.isEmpty()) {
        "$hash$dirty"
    } else {
        "$branch@$hash$dirty"
    }
}

/**
 * The `--version` line for this binary, using the metadata baked in at build
 * time by the Gradle build task.
 *
 * Reads from classpath resource `/srtla-version.properties` with keys:
 * - version: semantic version (defaults to "0.0.0")
 * - branch: git branch name (empty if detached or unavailable)
 * - hash: short git commit hash (empty if unavailable)
 * - dirty: "-dirty" suffix if working tree has changes, else empty
 * - package: package name (defaults to "srtla_send_kotlin")
 *
 * If the resource is missing or a key is absent, returns "" except as noted above.
 */
fun versionLine(): String {
    return try {
        val props = java.util.Properties()
        VersionResource::class.java.getResourceAsStream("/srtla-version.properties")?.use {
            props.load(it)
            val version = props.getProperty("version", "0.0.0")
            val branch = props.getProperty("branch", "")
            val hash = props.getProperty("hash", "")
            val dirty = props.getProperty("dirty", "")
            val packageName = props.getProperty("package", "srtla_send_kotlin")
            composeVersionLine(version, branch, hash, dirty, packageName)
        } ?: ""
    } catch (e: Exception) {
        ""
    }
}

private object VersionResource
