plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "dev.abdulkadirozyurt"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(11)
}

// Zero external dependencies — only Kotlin stdlib (bundled) + JDK
// No Maven/Gradle repository access needed in sandbox; this file is for the
// user's machine.
repositories {
    mavenCentral()
}

dependencies {
    // stdlib is added automatically by the kotlin("jvm") plugin
}

application {
    mainClass.set("dev.abdulkadirozyurt.srtla.cli.MainKt")
}

// Also expose testkit runner as a runnable task for sandbox verification
tasks.register<JavaExec>("runTests") {
    group = "verification"
    description = "Run zero-dependency testkit suite (no JUnit required)"
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["test"].runtimeClasspath
    mainClass.set("dev.abdulkadirozyurt.srtla.testkit.TestRunnerKt")
    // Exit code propagates; Gradle will fail the task on non-zero exit
    isIgnoreExitValue = false
}

sourceSets {
    test {
        kotlin.srcDir("src/test/kotlin")
    }
}
