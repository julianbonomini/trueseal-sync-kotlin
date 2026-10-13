package dev.trueseal.sync.internal

import dev.trueseal.sync.DeliveryIssue
import dev.trueseal.sync.TrueSealEvent
import dev.trueseal.sync.TrueSealMessage
import dev.trueseal.sync.toPublic
import uniffi.trueseal_sync.HandlerException
import uniffi.trueseal_sync.TrueSealEventListener
import uniffi.trueseal_sync.TrueSealIssueListener
import uniffi.trueseal_sync.TrueSealMessageHandler
import uniffi.trueseal_sync.TrueSealMessage as FfiTrueSealMessage
import uniffi.trueseal_sync.TrueSealEvent as FfiTrueSealEvent
import uniffi.trueseal_sync.DeliveryIssue as FfiDeliveryIssue

/**
 * Internal bridges between the UniFFI callback interfaces and the public
 * Kotlin API. Never public (ADR-0028: no FFI types on the surface).
 */

/**
 * The one message handler. The core AWAITS it (ADR-0026): the Blob is
 * acked only after [handler] completes. A throw becomes
 * [HandlerException.Failed], which the core treats as a failed handler
 * attempt (5 tries with backoff, then `handlerGaveUp`).
 *
 * The handler runs on a core thread. It may suspend — the core awaits the
 * future — but it must not block forever: a handler parked past the
 * core's liveness window (~75 s) tears the session down around it
 * (ADR-0026's best-effort dedup case).
 */
internal class MessageHandlerBridge(
    private val handler: suspend (TrueSealMessage) -> Unit,
) : TrueSealMessageHandler {
    override suspend fun `onMessage`(message: FfiTrueSealMessage) {
        try {
            handler(message.toPublic())
        } catch (t: Throwable) {
            throw HandlerException.Failed(t.message ?: t.toString())
        }
    }
}

/**
 * Event listener bridge. Listeners fire under the core's listener-bus
 * lock: they must RETURN PROMPTLY and must not call back into the
 * [dev.trueseal.sync.TrueSeal] object synchronously (see the prompt-return
 * rule on [dev.trueseal.sync.TrueSeal.onEvent]).
 */
internal class EventListenerBridge(
    private val listener: (TrueSealEvent) -> Unit,
) : TrueSealEventListener {
    override fun `onEvent`(event: FfiTrueSealEvent) {
        listener(event.toPublic())
    }
}

/** Delivery-issue listener bridge. Same prompt-return rule as events. */
internal class IssueListenerBridge(
    private val listener: (DeliveryIssue) -> Unit,
) : TrueSealIssueListener {
    override fun `onDeliveryIssue`(issue: FfiDeliveryIssue) {
        listener(issue.toPublic())
    }
}
