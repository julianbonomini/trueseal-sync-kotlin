package dev.trueseal.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural pins on the public surface (ADR-0028 / issue #4):
 * the entry type is `TrueSeal` with the canonical operations, and the
 * generated UniFFI types never leak onto it. Java reflection only —
 * no kotlin-reflect, no native load.
 */
class ApiSurfaceTest {

    private val trueSealClass: Class<*> = Class.forName("dev.trueseal.sync.TrueSeal")

    @Test
    fun `the public type is TrueSeal with the canonical operations`() {
        val methods = trueSealClass.declaredMethods.map { it.name }.toSet()
        val canonical = setOf(
            "close", "send", "onMessage", "onEvent", "onDeliveryIssue",
            "startPairing", "accept", "cancelPairing",
            "join", "cancelJoin", "remove", "leave", "destroyGroup",
        )
        for (name in canonical) {
            assertTrue("TrueSeal must declare $name", methods.contains(name))
        }
    }

    @Test
    fun `open, info, limits and parseRelayAddress live on the companion`() {
        val companion = trueSealClass.declaredClasses.first { it.simpleName == "Companion" }
        val methods = companion.declaredMethods.map { it.name }.toSet()
        // `open` takes a `UInt?` parameter — Kotlin name-mangles functions
        // with inline-class parameters (`open-<hash>`), so match the prefix.
        assertTrue(
            "companion must declare open; found: ${methods.sorted()}",
            methods.any { it == "open" || it.startsWith("open-") },
        )
        for (expected in listOf("parseRelayAddress", "getInfo", "getMaxPayloadBytes", "getMaxGroupSize")) {
            assertTrue("companion must declare $expected; found: ${methods.sorted()}", methods.contains(expected))
        }
    }

    @Test
    fun `send returns the Message ID as a String (no wrapper)`() {
        val send = trueSealClass.declaredMethods.first { it.name == "send" }
        assertEquals(String::class.java, send.returnType)
    }

    @Test
    fun `accept takes a request id`() {
        val accept = trueSealClass.declaredMethods.first { it.name == "accept" }
        assertEquals(listOf(String::class.java), accept.parameterTypes.toList())
    }

    @Test
    fun `no generated UniFFI type appears on the public surface`() {
        val publicMethods = trueSealClass.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        val methodLeaks = publicMethods
            .flatMap { listOf(it.returnType) + it.parameterTypes }
            .map { it.name }
            .filter { it.startsWith("uniffi.") }
        // Kotlin generates a public SYNTHETIC constructor (private ctor +
        // DefaultConstructorMarker) so the companion can call the private
        // primary constructor — compiler machinery, not public API.
        val constructorLeaks = trueSealClass.declaredConstructors
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .flatMap { it.parameterTypes.toList() }
            .map { it.name }
            .filter { it.startsWith("uniffi.") }
        assertTrue(
            "FFI types leaked: ${methodLeaks + constructorLeaks}; " +
                "public methods were: ${publicMethods.map { m -> m.name + m.parameterTypes.map { it.simpleName } }}",
            (methodLeaks + constructorLeaks).isEmpty(),
        )
    }

    @Test
    fun `state is exposed as StateFlows`() {
        for (property in listOf("getStatus", "getMe", "getMembers", "getConnection")) {
            val getter = trueSealClass.declaredMethods.firstOrNull { it.name == property }
            assertTrue("missing $property", getter != null)
            assertEquals(
                "kotlinx.coroutines.flow.StateFlow",
                getter!!.returnType.name,
            )
        }
    }

    @Test
    fun `the old API is gone`() {
        for (old in listOf(
            "dev.trueseal.sync.TruesealSyncClient",
            "dev.trueseal.sync.TruesealSyncError",
            "dev.trueseal.sync.ReceivedBlob",
        )) {
            try {
                Class.forName(old)
                throw AssertionError("$old must not exist on the public surface")
            } catch (expected: ClassNotFoundException) {
                // gone, as required
            }
        }
    }
}
