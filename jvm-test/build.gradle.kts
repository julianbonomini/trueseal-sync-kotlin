// KOTLIN-2 (#10, e2e ADR-0001): the TEST-ONLY JVM lane.
//
// This module compiles the SAME Kotlin sources as the published :lib AAR
// (no copied or forked sources — the sourceSets point at :lib's dirs) and
// runs them headless on Linux against the pinned Linux x64 core library
// (scripts/fetch-core-artifacts.sh --linux). It exists so the e2e Scenario
// Suite (G1) can drive the Kotlin SDK without an emulator.
//
// It is NOT a product: plain JVM is an unsupported Kotlin target for the
// preview. There is deliberately no publication here, and CI fails if one
// is added (scripts/check-jvm-test-only.sh). The module is never an input
// to the AAR, the release workflow or the promote workflow.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Repositories come from settings.gradle.kts (FAIL_ON_PROJECT_REPOS).

sourceSets {
    main {
        // The SAME sources as :lib — the canonical wrapper and the fetched,
        // patched UniFFI bindings (gitignored; fetch-core-artifacts.sh).
        kotlin.srcDirs("src/main/kotlin", "../lib/src/main/kotlin")
        java.srcDirs("../lib/src/main/java")
    }
    test {
        // The SAME unit tests :lib runs, plus this lane's own native and
        // driver tests under src/test/kotlin.
        kotlin.srcDirs("src/test/kotlin", "../lib/src/test/kotlin")
    }
}

dependencies {
    // Plain JNA jar (the AAR consumes the Android-packaged variant; the
    // generated UniFFI bindings call com.sun.jna either way).
    implementation(libs.jna.plain)

    // The wrapper's public API uses Flow; the JVM lane uses plain
    // coroutines-core (kotlinx-coroutines-android is an :lib-only dep).
    implementation(libs.kotlinx.coroutines.core)

    // The driver's JSON control protocol (E2E-1 consumes it).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // The pinned Linux x64 core (fetch-core-artifacts.sh --linux).
    systemProperty("jna.library.path", "$rootDir/jvm-test/native")
    // CI sets TRUSEAL_JVM_LANE=require so the native tests FAIL instead of
    // skipping when the pinned library is missing or unloadable.
    environment("TRUSEAL_JVM_LANE", System.getenv("TRUSEAL_JVM_LANE") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

kotlin {
    jvmToolchain(17)
}
