package dev.trueseal.sync.jvm

import dev.trueseal.sync.Storage
import dev.trueseal.sync.TrueSeal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * KOTLIN-2 (#10, e2e ADR-0001): the headless JVM lane actually loads the
 * PINNED Linux x64 core library and drives the canonical API through real
 * FFI — the proof the e2e Scenario Suite can run Kotlin without an
 * emulator.
 *
 * The lane needs `jvm-test/native/libtrueseal_sync.so` (fetch it with
 * `scripts/fetch-core-artifacts.sh --linux`) and a Linux host (the pinned
 * library is an x86-64 ELF). Locally the tests SKIP when either is
 * missing; CI sets TRUSEAL_JVM_LANE=require, turning the skip into a hard
 * failure — a lane that can't run counts as a failure (e2e ADR-0001).
 */
class NativeLaneTest {

    private val nativeLib = File("jvm-test/native/libtrueseal_sync.so").let {
        if (it.isFile) it else File("../jvm-test/native/libtrueseal_sync.so")
    }

    private val laneAvailable: Boolean
        get() = System.getProperty("os.name").lowercase().contains("linux") && nativeLib.isFile

    @Before
    fun requireLane() {
        if (System.getenv("TRUSEAL_JVM_LANE") == "require") {
            assertTrue(
                "TRUSEAL_JVM_LANE=require but the pinned Linux core is missing " +
                    "(${nativeLib.path}) — run scripts/fetch-core-artifacts.sh --linux",
                laneAvailable,
            )
        } else {
            assumeTrue(
                "JVM native lane unavailable (need Linux + jvm-test/native/libtrueseal_sync.so)",
                laneAvailable,
            )
        }
    }

    @Test
    fun `info and limits come from the pinned core`() {
        val info = TrueSeal.info
        assertEquals("0.6.0", info.sdkVersion)
        assertEquals(1L, info.transportVersion.toLong())
        assertEquals(1L, info.endToEndVersion.toLong())
        assertEquals(61_440L, TrueSeal.maxPayloadBytes.toLong())
        assertEquals(32L, TrueSeal.maxGroupSize.toLong())
    }

    @Test
    fun `open creates an identity and send queues through the real core`() = runBlocking {
        val dir = Files.createTempDirectory("trueseal-jvm-lane").toFile()
        val relay = "trueseal://${"aa".repeat(32)}@127.0.0.1:17700?push=17701"
        val ts = TrueSeal.open(relay = relay, storage = Storage.directory(dir))
        try {
            // A real identity from the real core (64-hex X25519 public key).
            assertEquals(64, ts.me.value.id.length)
            assertTrue(ts.me.value.name.isNotEmpty())

            // The relay is unreachable (background connect fails); per
            // ADR-0026 send() still succeeds — the body lands in the outbox.
            val messageId = ts.send("hello jvm lane".toByteArray())
            assertTrue("message IDs carry the tsm1_ prefix", messageId.startsWith("tsm1_"))

            // Session State on disk is the real SQLite store.
            assertTrue(File(dir, "default").exists() || dir.listFiles()?.isNotEmpty() == true)
        } finally {
            ts.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `send enforces the protocol size limit through the lane`() = runBlocking {
        val dir = Files.createTempDirectory("trueseal-jvm-lane-limit").toFile()
        val relay = "trueseal://${"bb".repeat(32)}@127.0.0.1:17700?push=17701"
        val ts = TrueSeal.open(relay = relay, storage = Storage.directory(dir))
        try {
            try {
                ts.send(ByteArray(61_441))
                fail("61,441 bytes must throw payloadTooLarge")
            } catch (e: dev.trueseal.sync.TrueSealError.PayloadTooLarge) {
                // expected — the typed error crosses the FFI on this lane
            }
            val id = ts.send(ByteArray(61_440))
            assertTrue(id.startsWith("tsm1_"))
        } finally {
            ts.close()
            dir.deleteRecursively()
        }
    }
}
