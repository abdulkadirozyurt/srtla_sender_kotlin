import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    kotlin("jvm") version "2.0.21"
    application
    `maven-publish`
}

group = "dev.abdulkadirozyurt"
version = "2.0.0"

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
    description = "Run zero-dependency testkit suite"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.abdulkadirozyurt.srtla.testkit.TestRunnerKt")
    isIgnoreExitValue = false
}

// `gradlew test` ve `gradlew build/check` testkit suite'ini koşar.
tasks.test {
    dependsOn(runTests)
}

val generateVersionResource = tasks.register("generateVersionResource") {
    group = "build"
    description = "Generate srtla-version.properties from git metadata"

    val outputDir = layout.buildDirectory.dir("generated/version-resource")
    val outputFile = outputDir.map { it.file("srtla-version.properties") }

    outputs.dir(outputDir)
    // Git state changes without any declared input; always regenerate.
    outputs.upToDateWhen { false }

    doLast {
        fun gitCommand(args: List<String>): String {
            return try {
                val process = ProcessBuilder(listOf("git") + args)
                    .directory(project.rootDir)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                if (process.waitFor() == 0 && output.isNotEmpty()) output else ""
            } catch (e: Exception) {
                ""
            }
        }

        fun gitStatus(): Boolean {
            return try {
                val process = ProcessBuilder("git", "diff", "--quiet")
                    .directory(project.rootDir)
                    .start()
                val code = process.waitFor()
                code == 1
            } catch (e: Exception) {
                false
            }
        }

        val gitHash = gitCommand(listOf("rev-parse", "--short", "HEAD"))
        val gitBranch = if (gitHash.isNotEmpty()) {
            gitCommand(listOf("rev-parse", "--abbrev-ref", "HEAD"))
                .takeIf { it != "HEAD" && it.isNotEmpty() }
                ?: ""
        } else {
            ""
        }
        val isDirty = if (gitHash.isNotEmpty()) gitStatus() else false
        val gitDirty = if (isDirty) "-dirty" else ""

        val props = Properties()
        props.setProperty("version", "${project.version}")
        props.setProperty("package", "srtla_send_kotlin")
        props.setProperty("branch", gitBranch)
        props.setProperty("hash", gitHash)
        props.setProperty("dirty", gitDirty)

        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("srtla-version.properties").outputStream().use {
            props.store(it, null)
        }
    }
}

sourceSets {
    main {
        // Passing the task (not the path) wires the dependency for every consumer,
        // processResources and sourcesJar alike.
        resources.srcDir(generateVersionResource)
    }
}

