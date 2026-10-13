pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "trueseal-sync-kotlin"
include(":lib")

// KOTLIN-2 (#10): the TEST-ONLY JVM lane for the headless e2e gates
// (e2e ADR-0001). It compiles the SAME sources as :lib against the pinned
// Linux x64 core, is never published (scripts/check-jvm-test-only.sh
// enforces that in CI), and JVM stays an unsupported Kotlin target.
include(":jvm-test")
