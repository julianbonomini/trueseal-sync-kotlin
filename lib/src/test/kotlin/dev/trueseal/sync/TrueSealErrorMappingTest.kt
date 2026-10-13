package dev.trueseal.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.trueseal_sync.GroupStatus as FfiGroupStatus
import uniffi.trueseal_sync.TrueSealException as FfiError

/**
 * Every core FFI error maps to exactly one public [TrueSealError] case
 * carrying the canonical code of trueseal-sync's shared-facts.json.
 * Pure JVM — constructing FFI error classes never loads the native lib.
 */
class TrueSealErrorMappingTest {

    @Test
    fun `all 14 FFI errors map to their canonical public case`() {
        val cases: List<Pair<FfiError, String>> = listOf(
            FfiError.InvalidRelayAddress("bad address") to "invalidRelayAddress",
            FfiError.InvalidNamespace("bad namespace") to "invalidNamespace",
            FfiError.Storage("disk full") to "storage",
            FfiError.Closed() to "closed",
            FfiError.NotMember(FfiGroupStatus.NOT_JOINED) to "notMember",
            FfiError.AlreadyInGroup() to "alreadyInGroup",
            FfiError.InvalidPairingToken() to "invalidPairingToken",
            FfiError.PairingClosed() to "pairingClosed",
            FfiError.GroupFull(32u) to "groupFull",
            FfiError.MemberNotFound() to "memberNotFound",
            FfiError.PayloadTooLarge(61440u) to "payloadTooLarge",
            FfiError.MessageHandlerAlreadySet() to "messageHandlerAlreadySet",
            FfiError.StoreTooNew(found = 9u, supported = 1u) to "storeTooNew",
            FfiError.ManifestVersionExhausted() to "manifestVersionExhausted",
        )

        assertEquals("the canonical list has exactly 14 errors", 14, cases.size)

        for ((ffi, canonicalCode) in cases) {
            val mapped = TrueSealError.map(ffi)
            assertEquals("$ffi must map to the $canonicalCode case", canonicalCode, mapped.code)
        }
    }

    @Test
    fun `payloads survive the mapping`() {
        val tooLarge = TrueSealError.map(FfiError.PayloadTooLarge(61440u))
            as TrueSealError.PayloadTooLarge
        assertEquals(61440u, tooLarge.max)

        val full = TrueSealError.map(FfiError.GroupFull(32u)) as TrueSealError.GroupFull
        assertEquals(32u, full.max)

        val tooNew = TrueSealError.map(FfiError.StoreTooNew(found = 3u, supported = 1u))
            as TrueSealError.StoreTooNew
        assertEquals(3u, tooNew.found)
        assertEquals(1u, tooNew.supported)

        val notMember = TrueSealError.map(FfiError.NotMember(FfiGroupStatus.DESTROYING))
            as TrueSealError.NotMember
        assertEquals(GroupStatus.Destroying, notMember.status)

        val invalid = TrueSealError.map(FfiError.InvalidRelayAddress("nope"))
            as TrueSealError.InvalidRelayAddress
        assertEquals("nope", invalid.msg)
    }

    @Test
    fun `the canonical error codes match shared-facts exactly`() {
        // Pinned against trueseal-sync docs/shared-facts.json "errors" (G12).
        val canonical = listOf(
            "invalidRelayAddress", "invalidNamespace", "storage", "closed",
            "notMember", "alreadyInGroup", "invalidPairingToken", "pairingClosed",
            "groupFull", "memberNotFound", "payloadTooLarge", "messageHandlerAlreadySet",
            "storeTooNew", "manifestVersionExhausted",
        )

        // Exactly 14 cases exist on the sealed hierarchy.
        val caseCount = TrueSealError::class.java.declaredClasses
            .count { it.simpleName != "Companion" }
        assertEquals(14, caseCount)

        // …and every canonical name is exposed as a `code`.
        val exposed = listOf(
            TrueSealError.InvalidRelayAddress("x").code,
            TrueSealError.InvalidNamespace("x").code,
            TrueSealError.Storage("x").code,
            TrueSealError.Closed().code,
            TrueSealError.NotMember(GroupStatus.NotJoined).code,
            TrueSealError.AlreadyInGroup().code,
            TrueSealError.InvalidPairingToken().code,
            TrueSealError.PairingClosed().code,
            TrueSealError.GroupFull(32u).code,
            TrueSealError.MemberNotFound().code,
            TrueSealError.PayloadTooLarge(61440u).code,
            TrueSealError.MessageHandlerAlreadySet().code,
            TrueSealError.StoreTooNew(found = 9u, supported = 1u).code,
            TrueSealError.ManifestVersionExhausted().code,
        ).sorted()
        assertEquals(canonical.sorted(), exposed)
    }

    @Test
    fun `there is no catch-all case`() {
        val names = TrueSealError::class.java.declaredClasses.map { it.simpleName }
        assertTrue(names.none { it.contains("Unknown") || it.contains("Other") || it.contains("PushFailed") })
    }
}
