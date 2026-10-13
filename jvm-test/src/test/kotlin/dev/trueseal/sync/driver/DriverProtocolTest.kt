package dev.trueseal.sync.driver

import dev.trueseal.sync.jvm.NativeLaneTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintWriter
import java.io.File

/**
 * The JSON control protocol E2E-1 will speak, round-tripped through the
 * real driver against the real pinned core (same lane guard as
 * [NativeLaneTest]).
 */
class DriverProtocolTest {

    private val nativeLib = File("jvm-test/native/libtrueseal_sync.so").let {
        if (it.isFile) it else File("../jvm-test/native/libtrueseal_sync.so")
    }

    @Before
    fun requireLane() {
        val available = System.getProperty("os.name").lowercase().contains("linux") && nativeLib.isFile
        if (System.getenv("TRUSEAL_JVM_LANE") == "require") {
            assertTrue("pinned Linux core missing — fetch-core-artifacts.sh --linux", available)
        } else {
            assumeTrue("JVM native lane unavailable", available)
        }
    }

    private class Harness {
        val toDriver = PipedWriter()
        val driverInput = BufferedReader(PipedReader(toDriver))
        val fromDriver = PipedWriter()
        val driverOutput = BufferedReader(PipedReader(fromDriver))
        val driver = Driver(driverInput, PrintWriter(fromDriver, true))
        private val json = Json { ignoreUnknownKeys = true }

        fun command(line: String): kotlinx.serialization.json.JsonObject {
            toDriver.write(line + "\n")
            toDriver.flush()
            val response = driverOutput.readLine()
                ?: error("driver closed the stream")
            return json.parseToJsonElement(response).jsonObject
        }
    }

    @Test
    fun `info, open, status, send and close round-trip over the protocol`() = runBlocking {
        val h = Harness()
        val job = launch(Dispatchers.IO) { h.driver.run() }
        try {
            val info = h.command("""{"id":1,"command":"info"}""")
            assertTrue(info.getValue("ok").jsonPrimitive.boolean)
            assertEquals(
                "0.6.0",
                info.getValue("result").jsonObject.getValue("sdkVersion").jsonPrimitive.content,
            )

            val dir = Files_createTempDir()
            val open = h.command(
                """{"id":2,"command":"open","storagePath":"${dir.absolutePath}",""" +
                    """"relay":"trueseal://${"cc".repeat(32)}@127.0.0.1:17700?push=17701"}""",
            )
            assertTrue("open failed: $open", open.getValue("ok").jsonPrimitive.boolean)
            val me = open.getValue("result").jsonObject.getValue("me").jsonObject
            assertTrue(me.getValue("id").jsonPrimitive.content.isNotEmpty())

            val status = h.command("""{"id":3,"command":"status"}""")
            assertEquals(
                "notJoined",
                status.getValue("result").jsonObject.getValue("status").jsonPrimitive.content,
            )

            // send while NotJoined: the CANONICAL typed error crosses the
            // protocol (error.code == the TrueSealError code, G12 parity).
            val send = h.command(
                """{"id":4,"command":"send","bodyHex":"68656c6c6f"}""",
            )
            assertTrue(!send.getValue("ok").jsonPrimitive.boolean)
            assertEquals(
                "notMember",
                send.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
            )

            val close = h.command("""{"id":5,"command":"close"}""")
            assertTrue("close failed: $close", close.getValue("ok").jsonPrimitive.boolean)
            dir.deleteRecursively()

            // Errors are typed: a second send with no session is a driver error.
            val noSession = h.command("""{"id":6,"command":"status"}""")
            assertTrue(!noSession.getValue("ok").jsonPrimitive.boolean)
            assertEquals(6L, noSession.getValue("id").jsonPrimitive.long)
        } finally {
            h.toDriver.close()
            job.cancel()
        }
    }

    private fun Files_createTempDir(): File =
        java.nio.file.Files.createTempDirectory("trueseal-driver-test").toFile()
}
