import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    application
    `maven-publish`
}

group = "dev.abdulkadirozyurt"
version = "0.1.0-SNAPSHOT"

// JVM 11 hedefi; toolchain zorunluluğu yok — JDK 11+ olan her makinede derlenir.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

// JitPack publishing: com.github.abdulkadirozyurt:srtla_sender_kotlin:<tag>
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "srtla-sender-kotlin"
        }
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// Zero external dependencies — yalnızca Kotlin stdlib (plugin ekler) + JDK.
repositories {
    mavenCentral()
}

application {
    mainClass.set("dev.abdulkadirozyurt.srtla.cli.MainKt")
}

// Bağımlılıksız testkit suite'i (JUnit kullanılmaz).
val runTests = tasks.register<JavaExec>("runTests") {
    group = "verification"
    description = "Run zero-dependency testkit suite (328 tests)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.abdulkadirozyurt.srtla.testkit.TestRunnerKt")
    isIgnoreExitValue = false
}

// `gradlew test` ve `gradlew build/check` testkit suite'ini koşar.
tasks.test {
    dependsOn(runTests)
}
