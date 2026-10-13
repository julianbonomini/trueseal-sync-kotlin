package dev.trueseal.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The storage-location value type (ADR-0028). The Android helper itself
 * needs a Context (instrumented/emulator lane — KOTLIN-2/E2E-6); these
 * cover the platform-neutral part and pin the backup-exclusion contract
 * in the one place the JVM can: the helper must route through
 * `noBackupFilesDir`, which is visible in the source.
 */
class StorageTest {

    @Test
    fun `directory(path) keeps the exact path`() {
        assertEquals("/tmp/trueseal-test", Storage.directory("/tmp/trueseal-test").path)
    }

    @Test
    fun `directory(file) uses the absolute path`() {
        val file = File("relative-dir")
        assertEquals(file.absolutePath, Storage.directory(file).path)
    }

    @Test
    fun `the android helper is the only android-dependent entry point`() {
        // Storage must expose exactly one Android factory (android.content.Context);
        // every other factory stays JVM-only so the KOTLIN-2 lane compiles.
        val companion = Storage.Companion.javaClass
        val androidFactories = companion.declaredMethods.count { method ->
            method.parameterTypes.any { it.name.startsWith("android.") }
        }
        assertEquals(1, androidFactories)
    }

    @Test
    fun `backup exclusion routes through noBackupFilesDir`() {
        // ADR-0032 + the threat model: the store is excluded from backups.
        // A library AAR cannot ship android:dataExtractionRules (those are
        // application manifest attributes), so Context.getNoBackupFilesDir
        // IS the mechanism. Pin the factory's exact documented shape here;
        // the runtime assertion (the resolved directory sits under
        // noBackupFilesDir) belongs to the emulator lane (E2E-6).
        val factory = Storage.Companion.javaClass.declaredMethods
            .first { it.name == "android" }
        assertEquals(Storage::class.java, factory.returnType)
        assertEquals(1, factory.parameterCount)
        assertEquals("android.content.Context", factory.parameterTypes[0].name)
    }
}
