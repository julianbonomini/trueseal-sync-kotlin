package dev.trueseal.sync.jvm

import dev.trueseal.sync.ConnectionState
import dev.trueseal.sync.DeliveryIssue
import dev.trueseal.sync.GroupStatus
import dev.trueseal.sync.Storage
import dev.trueseal.sync.TrueSeal
import dev.trueseal.sync.TrueSealError
import dev.trueseal.sync.UnreadableReason
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

// SDK-2 (#5): check the sync JSON test vectors THROUGH the Kotlin bindings —
// release gate G5. The vendored file proves the shipped native library and
// the generated trueseal_sync.kt recognise the same wire reality as the
// Rust reference, instead of inferring compatibility from versions
// (ADR-0022). Consumption contract: trueseal-sync/docs/test-vectors/README.md.
// Mirrors the Swift G5 precedent (trueseal-sync-swift SyncVectorTests, swift#13).
//
// Every check here needs the real core (TrueSeal.info and send() are FFI
// calls), so the whole suite rides the KOTLIN-2 JVM native lane: it SKIPS
// locally without the pinned .so and HARD-FAILS in CI
// (TRUSEAL_JVM_LANE=require — a lane that can't run counts as a failure,
// e2e ADR-0001).

// The pin (G8) — the same upstream commit + SHA-256 the relay and the Swift
// SDK pin against.
private const val SYNC_V1_SOURCE = "trueseal-sync@265f99f docs/test-vectors/sync-v1.json"
private const val SYNC_V1_FORMAT_VERSION = 1
private const val SYNC_V1_SHA256 = "0506d5a80a9485f612d4d38a2530b70296a1e022e0539f3a001b6298a3dc3b37"

private class VectorException(message: String) : Exception(message)

private fun drift(found: String) = VectorException(
    "vendored sync-v1.json SHA-256 $found != pinned $SYNC_V1_SHA256 — the file drifted from " +
        "$SYNC_V1_SOURCE. Do NOT re-pin to silence this: an unexpected drift is a wire-format bug.",
)

private fun malformed(what: String) = VectorException("sync-v1.json malformed: $what")

private fun mismatch(what: String) = VectorException(what)

class SyncVectorTest {

    private val nativeLib = File("jvm-test/native/libtrueseal_sync.so").let {
        if (it.isFile) it else File("../jvm-test/native/libtrueseal_sync.so")
    }

    private val laneAvailable: Boolean
        get() = System.getProperty("os.name").lowercase().contains("linux") && nativeLib.isFile

    private val tempDirs = mutableListOf<File>()

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

    @org.junit.After
    fun cleanUp() {
        for (dir in tempDirs) dir.deleteRecursively()
        tempDirs.clear()
    }

    // ── Loading (drift-refusing, G8) ────────────────────────────────────────

    /** Loads and pins the vendored vectors. A byte of drift — accidental or
     *  copied-in — fails here before any check runs (issue #5: fail the build
     *  on missing vector files). */
    private fun loadVectors(): JsonObject {
        val stream = javaClass.classLoader.getResourceAsStream("vectors/sync-v1.json")
            ?: throw malformed(
                "vectors/sync-v1.json missing from the test resources — the vector file MUST be present",
            )
        val bytes = stream.use { it.readBytes() }
        val found = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (found != SYNC_V1_SHA256) throw drift(found)
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        if (root["format"]?.jsonPrimitive?.content != "trueseal-sync-vectors" ||
            root["formatVersion"]?.jsonPrimitive?.int != SYNC_V1_FORMAT_VERSION
        ) {
            throw malformed("unexpected format/formatVersion")
        }
        return root
    }

    // ── protocol: versions and limits through the bindings ──────────────────

    /** The comparison, factored so the negative control can feed it a
     *  mutated copy. Throws on mismatch, listing ALL of them. Every value on
     *  the left is the LINKED CORE's FFI static reached through the public
     *  SDK surface (SDK-3 pass-throughs) — a through-the-bindings check. */
    private fun assertProtocolMatches(v: JsonObject) {
        val p = v["protocol"]?.jsonObject ?: throw malformed("missing protocol section")
        val failures = mutableListOf<String>()
        fun expect(key: String, actual: Long, file: String) {
            val want = p[key]?.jsonPrimitive?.long
            if (want == null) {
                failures.add("protocol.$key missing from the vector file")
            } else if (want != actual) {
                failures.add("protocol.$key: bindings report $actual, vectors pin $want ($file)")
            }
        }
        expect("transportVersion", TrueSeal.info.transportVersion.toLong(), "TrueSeal.info.transportVersion")
        expect("endToEndVersion", TrueSeal.info.endToEndVersion.toLong(), "TrueSeal.info.endToEndVersion")
        expect("protocolSizeLimit", TrueSeal.maxPayloadBytes.toLong(), "TrueSeal.maxPayloadBytes")
        expect("maxGroupSize", TrueSeal.maxGroupSize.toLong(), "TrueSeal.maxGroupSize")
        if (failures.isNotEmpty()) throw mismatch(failures.joinToString("; "))
    }

    @Test
    fun `protocol versions and limits match through the bindings`() {
        assertProtocolMatches(loadVectors())
    }

    /** Negative control (issue #5): a mutated vector MUST fail the check.
     *  Proves the comparisons above are real, not tautological. */
    @Test
    fun `a mutated vector fails the check`() {
        val v = loadVectors()
        val p = v["protocol"]?.jsonObject ?: throw malformed("missing protocol section")
        val original = p["maxGroupSize"]?.jsonPrimitive?.long ?: throw malformed("missing maxGroupSize")
        val mutated = JsonObject(
            v.toMutableMap().apply {
                put(
                    "protocol",
                    JsonObject(
                        p.toMutableMap().apply {
                            put("maxGroupSize", kotlinx.serialization.json.JsonPrimitive(original + 1))
                        },
                    ),
                )
            },
        )
        try {
            assertProtocolMatches(mutated)
            fail("a mutated maxGroupSize must fail the protocol check")
        } catch (e: VectorException) {
            assertTrue(
                "mutation must surface as a maxGroupSize mismatch: ${e.message}",
                e.message!!.contains("maxGroupSize"),
            )
        }
    }

    // ── rejections: the canonical taxonomy through the bindings ─────────────

    /** Every rejection's expected typed error / Delivery Issue / Connection
     *  State must exist in the SDK's canonical surface (ADR-0028/0032 + the
     *  SYNC-15 amendment — 14 errors, shared-facts names). The maps below are
     *  keyed by each instance's own `code`: referencing the case at COMPILE
     *  TIME pins its existence (a dropped or renamed case stops this file
     *  compiling), and comparing `code == name` pins the canonical string. */
    @Test
    fun `every rejection's expected name exists through the bindings`() {
        val v = loadVectors()
        val rejections = v["rejections"]?.jsonArray
        if (rejections == null || rejections.isEmpty()) throw malformed("missing/empty rejections")

        // kind "typedError": a real TrueSealError case with this code.
        val typedErrors = listOf(TrueSealError.PayloadTooLarge(max = 0u)).associateBy { it.code }
        // kind "deliveryIssue": real DeliveryIssue cases (with reasons).
        val issues = listOf(
            DeliveryIssue.HeldForUpgrade(version = 0u),
            DeliveryIssue.Unreadable(reason = UnreadableReason.Malformed),
        ).associateBy { it.code }
        val unreadableReasons = UnreadableReason.entries.associateBy { it.code }
        // kind "connectionState".
        val connectionStates = listOf(
            ConnectionState.RelayVersionUnsupported(min = 0u, max = 0u),
        ).associateBy { it.code }

        val failures = mutableListOf<String>()
        for (r in rejections) {
            val entry = r.jsonObject
            val layer = entry["layer"]?.jsonPrimitive?.content
            val kase = entry["case"]?.jsonPrimitive?.content
            val expect = entry["expect"]?.jsonObject
            val kind = expect?.get("kind")?.jsonPrimitive?.content
            if (layer == null || kase == null || expect == null || kind == null) {
                failures.add("malformed rejection entry")
                continue
            }
            val label = "$layer/$kase"
            when (kind) {
                "typedError" -> {
                    val name = expect["name"]?.jsonPrimitive?.content
                    if (name == null || typedErrors[name] == null) {
                        failures.add("$label: typed error '$name' has no TrueSealError case")
                    }
                }
                "deliveryIssue" -> {
                    val name = expect["name"]?.jsonPrimitive?.content
                    if (name == null) {
                        failures.add("$label: deliveryIssue without a name")
                    } else if (name == "unreadable") {
                        val reason = expect["reason"]?.jsonPrimitive?.content
                        if (reason == null || unreadableReasons[reason] == null) {
                            failures.add("$label: unreadable reason '$reason' has no UnreadableReason case")
                        }
                    } else if (issues[name] == null) {
                        failures.add("$label: delivery issue '$name' has no DeliveryIssue case")
                    }
                }
                "connectionState" -> {
                    val name = expect["name"]?.jsonPrimitive?.content
                    if (name == null || connectionStates[name] == null) {
                        failures.add("$label: connection state '$name' has no ConnectionState case")
                    }
                }
                // kind "connectionFailure" is the typed Disconnected path (a
                // refused version exchange resolves as connectionFailed); it
                // has no payload name to pin — structural.
                "connectionFailure" -> Unit
                else -> failures.add(
                    "$label: unknown expect.kind '$kind' — a new rejection kind the SDK's surface must name",
                )
            }
        }
        if (failures.isNotEmpty()) throw mismatch(failures.joinToString("; "))
    }

    // ── size rejection, behaviorally through the bindings ───────────────────

    /** The size-layer rejection IS reachable offline through the bindings:
     *  the core consults the size gate FIRST (ADR-0025: refused
     *  synchronously in send(), before any other side effect), so even a
     *  non-member's send(limit+1) throws PayloadTooLarge carrying the
     *  vector's exact limit. The at-limit body passes the size gate and
     *  fails on the NEXT gate (notMember — nothing to fan out to), pinning
     *  the ordering both ways. */
    @Test
    fun `size rejection refuses over-limit with the vector's exact limit`() = runBlocking {
        val v = loadVectors()
        val p = v["protocol"]?.jsonObject ?: throw malformed("missing protocol section")
        val limit = p["protocolSizeLimit"]?.jsonPrimitive?.long
            ?: throw malformed("missing protocol.protocolSizeLimit")

        // Internal consistency of the vector's own rejection entry.
        val rejections = v["rejections"]?.jsonArray ?: throw malformed("missing rejections")
        val sizeEntry = rejections.map { it.jsonObject }
            .firstOrNull { it["case"]?.jsonPrimitive?.content == "bodyOverProtocolLimit" }
            ?: throw malformed("size rejection entry missing")
        val expect = sizeEntry["expect"]?.jsonObject ?: throw malformed("size entry without expect")
        if (expect["name"]?.jsonPrimitive?.content != "payloadTooLarge" ||
            expect["max"]?.jsonPrimitive?.long != limit ||
            sizeEntry["input"]?.jsonObject?.get("bodyLen")?.jsonPrimitive?.long != limit + 1
        ) {
            throw malformed("size rejection entry inconsistent with the protocol section")
        }

        val dir = Files.createTempDirectory("trueseal-vectors").toFile()
        tempDirs.add(dir)
        val ts = TrueSeal.open(
            relay = "trueseal://${"cc".repeat(32)}@127.0.0.1:17709?push=17710",
            storage = Storage.directory(dir),
            namespace = "vectors",
        )
        try {
            // Over the limit: typed refusal carrying the vector's exact max —
            // through the public SDK send(), against the linked core.
            try {
                ts.send(ByteArray(limit.toInt() + 1))
                fail("send(limit+1) must throw payloadTooLarge (ADR-0025; vector size/bodyOverProtocolLimit)")
            } catch (e: TrueSealError.PayloadTooLarge) {
                assertEquals(
                    "payloadTooLarge.max must be the vector's protocolSizeLimit",
                    limit,
                    e.max.toLong(),
                )
            }
            // At the limit: the size gate passes; the next gate (membership)
            // fires instead — pinning size-before-membership from the other side.
            try {
                ts.send(ByteArray(limit.toInt()))
                fail("at-limit send while NotJoined must throw notMember (the size gate passed)")
            } catch (e: TrueSealError.NotMember) {
                assertEquals(GroupStatus.NotJoined, e.status)
            }
        } finally {
            ts.close()
        }
    }

    // ── core-internal sections: present, and their checker named ────────────

    /** These sections pin bytes the public FFI deliberately does not expose
     *  entry points for (signing inputs, seal/open, message codec, frame
     *  assembly) — SYNC-2 exposed no test-only FFI hooks. Faking access would
     *  violate G5's purpose, so they are checked by the owner named per
     *  section; this test asserts the sections are present and non-empty so
     *  they cannot silently vanish from the file every consumer pins. */
    @Test
    fun `core-internal sections are present and their checkers named`() {
        val v = loadVectors()
        val owners = listOf(
            "domains" to "core: vectors::generated_vectors_verify_against_the_code",
            "transportVersionPrefix" to "relay: internal/vectors vectors_test.go (RELAY-5) + core vectors test",
            "signing" to "core: vectors test (signing_message / signing_message_for are the signer's own functions)",
            "addressedEncryption" to "core: vectors test (seal/open round-trip with fixed ephemeral)",
            "messageId" to "core: vectors test (tsm1_ derivation)",
            "messages" to "core: vectors test (real message codec, incl. the 65-byte pre-SYNC-3 Pair refusal)",
            "sealedEnvelope" to "core: vectors test (build_sealed_with_ephemeral; prefix is the decrypted payload)",
            "frames" to "relay: vectors_test.go byte-for-byte (RELAY-5) + core vectors test",
        )
        for ((section, owner) in owners) {
            val value = v[section]
                ?: throw mismatch(
                    "core-internal section '$section' is MISSING from the pinned vectors " +
                        "(checked by $owner) — was it removed upstream?",
                )
            if (value is JsonObject) {
                assertTrue("section '$section' is empty", value.isNotEmpty())
            }
        }
        // The remaining protocol fields the FFI does not expose — presence
        // asserted so the coverage map is complete.
        val p = v["protocol"]?.jsonObject ?: throw malformed("missing protocol section")
        for (key in listOf(
            "storeVersion", "maxEnvelopeBytes", "noiseFramePlaintextMax",
            "maxManifestVersionJump", "maxHeldBlobs", "replayWindowMs",
        )) {
            assertNotNull("protocol.$key missing", p[key])
        }
    }

    // ── machine-readable report (Release Evidence, G5) ──────────────────────

    /** Emits the G5 report: one entry per section with its status and
     *  checker. Written to $TRUSEAL_VECTOR_REPORT when set (CI attaches it as
     *  an artifact); always printed for the test log. Entries are derived
     *  deterministically — never from JUnit execution order. */
    @Test
    fun `emit the machine-readable G5 report`() {
        data class Entry(val section: String, val status: String, val detail: String)

        val checked = "checked-through-bindings"
        val documented = "documented-core-internal"
        val entries = listOf(
            Entry("pin", checked, "$SYNC_V1_SOURCE; sha256 $SYNC_V1_SHA256; drift-refusing load"),
            Entry(
                "protocol", checked,
                "transportVersion/endToEndVersion/protocolSizeLimit/maxGroupSize via TrueSeal.info/maxPayloadBytes/maxGroupSize (the linked core's FFI statics)",
            ),
            Entry(
                "rejections (taxonomy)", checked,
                "every expected typed error / DeliveryIssue(+reason) / ConnectionState exists in the SDK surface (compile-time referenced, canonical code strings asserted)",
            ),
            Entry(
                "rejections/size (behavioral)", checked,
                "send(limit+1) throws payloadTooLarge(max=61440) — the vector's exact limit; send(limit) passes the size gate (fails notMember offline); size-before-membership pinned both ways",
            ),
            Entry("negative control", checked, "a mutated protocol expectation fails the check"),
            Entry("domains", documented, "checked by core vectors test"),
            Entry("transportVersionPrefix", documented, "checked by relay vectors_test.go (RELAY-5) + core"),
            Entry("signing", documented, "checked by core vectors test"),
            Entry("addressedEncryption", documented, "checked by core vectors test"),
            Entry("messageId", documented, "checked by core vectors test"),
            Entry("messages", documented, "checked by core vectors test"),
            Entry("sealedEnvelope", documented, "checked by core vectors test"),
            Entry("frames", documented, "checked by relay vectors_test.go (RELAY-5) + core"),
            Entry("protocol (remaining fields)", documented, "checked by core vectors test"),
            Entry(
                "rejections (inbound behaviors: held/older/expired/malformed envelopes)", documented,
                "checked by the e2e Adversary Client (G3) — needs a hostile peer",
            ),
        )

        val doc = buildJsonObject {
            put("gate", "G5")
            put("sdk", "trueseal-sync-kotlin")
            put(
                "pin",
                buildJsonObject {
                    put("source", SYNC_V1_SOURCE)
                    put("sha256", SYNC_V1_SHA256)
                    put("formatVersion", SYNC_V1_FORMAT_VERSION)
                },
            )
            put(
                "checks",
                buildJsonArray {
                    for (e in entries) {
                        add(
                            buildJsonObject {
                                put("section", e.section)
                                put("status", e.status)
                                put("detail", e.detail)
                            },
                        )
                    }
                },
            )
        }
        val text = doc.toString()
        println("TRUSEAL-G5-REPORT: $text")
        System.getenv("TRUSEAL_VECTOR_REPORT")?.let { path ->
            File(path).writeText(text)
        }
    }
}
