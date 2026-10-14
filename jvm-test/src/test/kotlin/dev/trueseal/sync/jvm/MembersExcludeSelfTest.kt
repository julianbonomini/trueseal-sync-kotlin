package dev.trueseal.sync.jvm

import dev.trueseal.sync.Member
import dev.trueseal.sync.Storage
import dev.trueseal.sync.TrueSeal
import dev.trueseal.sync.TrueSealEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * D31 (the Kotlin twin of swift#19 / D29; found by trueseal-e2e#22's
 * Scenario Suite): ADR-0028 — `members` lists every OTHER member. The
 * core's `membersChanged` event payload carries the full manifest list,
 * SELF INCLUDED, while the core's `members()` getter excludes self. The
 * event path stored the payload verbatim, so public `members` flipped to
 * self-INCLUDED the moment an event arrived (e2e probe: a.members == 2 in
 * a 2-device group).
 *
 * This lane has no live relay, so the end-to-end repro stays where it was
 * found (e2e#22, plus swift#19's live-relay regression test). What is
 * pinned HERE is the filtering property itself: a MembersChanged event
 * driven through the real dispatch path (internal applyEvent) must never
 * expose self in the public flow — RED on the unfixed verbatim-store
 * code, green after the me.id filter.
 */
class MembersExcludeSelfTest {

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
            org.junit.Assume.assumeTrue(
                "JVM native lane unavailable (need Linux + jvm-test/native/libtrueseal_sync.so)",
                laneAvailable,
            )
        }
    }

    @Test
    fun `a membersChanged event never exposes self in members`() = runBlocking {
        val dir = Files.createTempDirectory("trueseal-jvm-lane-members").toFile()
        // open() is infallible (SYNC-15): identity + flows work without
        // connectivity, which is all this synthetic-event test needs.
        val relay = "trueseal://${"cc".repeat(32)}@127.0.0.1:17700?push=17701"
        val ts = TrueSeal.open(relay = relay, storage = Storage.directory(dir))
        try {
            val me = ts.me.value
            assertTrue(me.id.isNotEmpty())
            val other = Member(id = "other-device", name = "Other Device")

            // Drive the REAL dispatch path with the payload shape the core
            // actually sends: the full manifest list, SELF INCLUDED.
            ts.applyEvent(TrueSealEvent.MembersChanged(listOf(me, other)))

            val after = ts.members.value
            assertFalse(
                "members must exclude self after membersChanged (ADR-0028; e2e#22 found self included)",
                after.any { it.id == me.id },
            )
            assertEquals(
                "a 2-device group reports exactly the OTHER member",
                listOf(other.id),
                after.map { it.id },
            )

            // Repeated events keep the invariant (no re-introduction).
            ts.applyEvent(TrueSealEvent.MembersChanged(listOf(me, other, Member(id = "third", name = "Third"))))
            assertFalse(ts.members.value.any { it.id == me.id })
            assertEquals(listOf(other.id, "third"), ts.members.value.map { it.id })
        } finally {
            ts.close()
            dir.deleteRecursively()
        }
    }
}
