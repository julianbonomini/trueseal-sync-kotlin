package dev.trueseal.sync.internal

import dev.trueseal.sync.DeliveryIssue
import dev.trueseal.sync.TrueSealEvent
import dev.trueseal.sync.UnreadableReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.trueseal_sync.ConnectionState as FfiConnectionState
import uniffi.trueseal_sync.DeliveryIssue as FfiDeliveryIssue
import uniffi.trueseal_sync.HandlerException
import uniffi.trueseal_sync.Member as FfiMember
import uniffi.trueseal_sync.TrueSealEvent as FfiTrueSealEvent
import uniffi.trueseal_sync.TrueSealMessage as FfiTrueSealMessage
import uniffi.trueseal_sync.UnreadableReason as FfiUnreadableReason

/**
 * The bridges are the SDK's boundary with the core's callback threads:
 * an exception escaping an event/issue listener becomes a core-thread
 * panic via UniFFI, while a message-handler throw is the CONTRACT (a
 * failed attempt, ADR-0026). Pure JVM — no native load.
 *
 * The close-during-event teardown race itself (a `statusChanged` fired
 * seconds into close) is driven by the native core and can't be
 * reproduced on the JVM without the .so — it is pinned for the KOTLIN-2
 * lane (#10). What is pinned here is the boundary rule the race violated:
 * nothing a listener does may throw back into the FFI.
 */
class BridgeTest {

    @Test
    fun `event listener delivery is mapped`() {
        val received = mutableListOf<TrueSealEvent>()
        val bridge = EventListenerBridge { received.add(it) }

        bridge.`onEvent`(FfiTrueSealEvent.ConnectionChanged(FfiConnectionState.Connected))

        assertEquals(
            TrueSealEvent.ConnectionChanged(dev.trueseal.sync.ConnectionState.Connected),
            received.single(),
        )
    }

    @Test
    fun `a throwing event listener is contained — nothing escapes to the FFI`() {
        val bridge = EventListenerBridge { throw IllegalStateException("app bug") }

        // Must not throw: UniFFI would turn it into a core-thread panic.
        bridge.`onEvent`(FfiTrueSealEvent.ConnectionChanged(FfiConnectionState.Disconnected))
    }

    @Test
    fun `a throwing issue listener is contained — nothing escapes to the FFI`() {
        val bridge = IssueListenerBridge { throw IllegalStateException("app bug") }

        bridge.`onDeliveryIssue`(FfiDeliveryIssue.Unauthorized)
    }

    @Test
    fun `issue listener delivery is mapped`() {
        val received = mutableListOf<DeliveryIssue>()
        val bridge = IssueListenerBridge { received.add(it) }

        bridge.`onDeliveryIssue`(FfiDeliveryIssue.Unreadable(FfiUnreadableReason.EXPIRED))

        assertEquals(DeliveryIssue.Unreadable(UnreadableReason.Expired), received.single())
    }

    @Test
    fun `a message handler throw becomes HandlerException-Failed — the ADR-0026 attempt signal`() =
        runTest {
            val bridge = MessageHandlerBridge { throw IllegalStateException("handler broke") }
            val message = FfiTrueSealMessage(
                id = "tsm1_x",
                from = FfiMember(id = "m1", name = "One"),
                body = byteArrayOf(1),
            )

            val thrown = runCatching { bridge.`onMessage`(message) }.exceptionOrNull()

            assertTrue(thrown is HandlerException.Failed)
            assertEquals("handler broke", (thrown as HandlerException.Failed).msg)
        }

    @Test
    fun `a successful message handler completes normally`() =
        runTest {
            var handled: String? = null
            val bridge = MessageHandlerBridge { handled = it.id }
            val message = FfiTrueSealMessage(
                id = "tsm1_ok",
                from = FfiMember(id = "m1", name = "One"),
                body = byteArrayOf(1),
            )

            bridge.`onMessage`(message)

            assertEquals("tsm1_ok", handled)
        }
}
