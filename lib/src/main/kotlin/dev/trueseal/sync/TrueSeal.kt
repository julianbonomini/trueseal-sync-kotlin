package dev.trueseal.sync

import dev.trueseal.sync.internal.EventListenerBridge
import dev.trueseal.sync.internal.IssueListenerBridge
import dev.trueseal.sync.internal.MessageHandlerBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.trueseal_sync.Subscription as FfiSubscription
import uniffi.trueseal_sync.TrueSeal as FfiTrueSeal
import uniffi.trueseal_sync.TrueSealException as FfiError

/**
 * The canonical TrueSeal entry type (sync ADR-0028).
 *
 * One object represents this device. It outlives group membership: after
 * removal, [leave] or [destroyGroup] the library wipes the namespace,
 * rotates to a fresh identity and the same object reaches
 * [GroupStatus.NotJoined], ready to pair again. [close] stops the
 * connection and the handlers but keeps Session State on disk; every call
 * after [close] fails with [TrueSealError.Closed].
 *
 * State ([status], [me], [members], [connection]) is a [StateFlow] held in
 * lockstep with the core: `statusChanged` events fire only for the
 * asynchronous transitions, so synchronous operations refresh the flows
 * from the initiating call (ADR-0028 statusChanged mapping).
 *
 * ## Pairing
 *
 * One device calls [startPairing] and hands the token to the other device
 * out-of-band (QR, share sheet). That device calls [join]. The initiating
 * device receives `joinRequest` events and admits with [accept].
 *
 * ## Receiving
 *
 * [onMessage] registers THE message handler; registering it starts
 * receiving (ADR-0026). The handler is awaited — a message is acked only
 * after it completes.
 */
class TrueSeal private constructor(
    private val core: FfiTrueSeal,
) : java.io.Closeable {

    private val stateLock = Any()

    private val _status = MutableStateFlow(mapErrors { core.status().toPublic() })
    private val _me = MutableStateFlow(mapErrors { core.me().toPublic() })
    private val _members = MutableStateFlow(mapErrors { core.members().map { it.toPublic() } })
    private val _connection = MutableStateFlow(mapErrors { core.connection().toPublic() })

    /** Group membership state. `statusChanged` events fire only for the
     *  asynchronous transitions (ADR-0028). */
    val status: StateFlow<GroupStatus> = _status

    /** This device's current identity. Changes when the identity rotates
     *  (removal, leave, Destroy Group). */
    val me: StateFlow<Member> = _me

    /** Every other current member (empty unless `status == Member`). */
    val members: StateFlow<List<Member>> = _members

    /** Relay connectivity. */
    val connection: StateFlow<ConnectionState> = _connection

    /**
     * Keeps the StateFlows in lockstep with the core. The core buffers
     * events raised before the first listener registers, so no transition
     * is lost between [open] and this subscription.
     *
     * Listeners fire under the core's listener-bus lock and must NOT
     * re-enter the core (ffi.rs), so this listener only publishes event
     * payloads to the flows. The one core read it needs — `me` after a
     * status change (the identity may have rotated: removal, leave,
     * Destroy Group) — is hopped OFF the listener thread and guarded:
     * after [close], `me()` throws [TrueSealError.Closed], and a
     * teardown-racing event (the identity-rotation settle thread can fire
     * `statusChanged` seconds into teardown) must never make a listener
     * throw into the FFI, which UniFFI turns into a core-thread panic.
     * (Same shape as the Swift reference's `Task { @MainActor … try? }`
     * hop.)
     */
    private val stateSubscription: Subscription = mapErrors {
        core.onEvent(
            EventListenerBridge { event -> applyEvent(event) },
        )
    }.let(::Subscription)

    /**
     * Applies one core event to the state flows. Extracted from the
     * listener so the JVM lane can drive synthetic events through the real
     * dispatch path (the lane has no live relay — the end-to-end repro of
     * the members self-inclusion bug lives in trueseal-e2e#22's suite and
     * swift#19's live-relay test; here we pin the filtering property
     * itself).
     */
    internal fun applyEvent(event: TrueSealEvent) {
        when (event) {
            is TrueSealEvent.StatusChanged -> {
                synchronized(stateLock) {
                    _status.value = event.status
                    if (event.status != GroupStatus.Member) _members.value = emptyList()
                }
                CoroutineScope(Dispatchers.Default).launch {
                    runCatching { mapErrors { core.me().toPublic() } }
                        .onSuccess { refreshed ->
                            synchronized(stateLock) { _me.value = refreshed }
                        }
                }
            }
            is TrueSealEvent.MembersChanged -> synchronized(stateLock) {
                _members.value = event.members
            }
            is TrueSealEvent.ConnectionChanged -> synchronized(stateLock) {
                _connection.value = event.connection
            }
            is TrueSealEvent.JoinRequestReceived,
            is TrueSealEvent.AdmissionDropped -> Unit
        }
    }

    /**
     * Re-reads state from the core. Called after synchronous transitions
     * (join → pendingJoin, leave → leaving, destroyGroup → destroying,
     * cancelJoin → notJoined) raise NO event by design (ADR-0028) —
     * without this the flows would lag the call the app just made.
     */
    private fun refreshState() = synchronized(stateLock) {
        runCatching { mapErrors { core.status().toPublic() } }.onSuccess { _status.value = it }
        runCatching { mapErrors { core.me().toPublic() } }.onSuccess { _me.value = it }
        runCatching { mapErrors { core.members().map { m -> m.toPublic() } } }
            .onSuccess { _members.value = it }
        runCatching { mapErrors { core.connection().toPublic() } }.onSuccess { _connection.value = it }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Stops the connection and the handlers, keeping Session State on
     *  disk. Every call after [close] fails with [TrueSealError.Closed].
     *  Idempotent. In-flight handlers run to completion; their messages
     *  stay unacked and are re-delivered on the next [open] (at-least-once,
     *  ADR-0026). */
    override fun close() {
        stateSubscription.cancel()
        mapErrors { core.close() }
    }

    // ── Sending ───────────────────────────────────────────────────────────

    /**
     * Queues [body] for fan-out to every member and returns the Message ID
     * once it is durably in the outbox (ADR-0026). Delivery is at least
     * once, in per-sender order, and continues in the background — a
     * permanent failure arrives as a `sendFailed` delivery issue carrying
     * the same ID.
     *
     * @throws TrueSealError.PayloadTooLarge immediately if the body exceeds
     *   the effective limit (ADR-0025).
     * @throws TrueSealError.NotMember unless `status == Member`.
     */
    @Throws(TrueSealError::class)
    fun send(body: ByteArray): String = mapErrors { core.send(body) }

    // ── Receiving ─────────────────────────────────────────────────────────

    /**
     * Registers the single message handler; registering it starts
     * receiving (ADR-0026). The handler is awaited: the message is acked
     * only after it completes, and a throw is a handler attempt (5 tries,
     * then `handlerGaveUp`). Registration is one-shot per object — a
     * second call fails with [TrueSealError.MessageHandlerAlreadySet] and
     * cancelling the returned [Subscription] does not re-enable it.
     *
     * The handler may suspend (the core awaits it) but must not park
     * forever: a handler parked past the core's liveness window (~75 s)
     * tears the session down around it.
     */
    @Throws(TrueSealError::class)
    fun onMessage(handler: suspend (TrueSealMessage) -> Unit): Subscription =
        mapErrors { core.onMessage(MessageHandlerBridge(handler)) }.let(::Subscription)

    /**
     * Registers an event listener. Any number of listeners; events raised
     * before the first registration are buffered (ADR-0028).
     *
     * Listeners must RETURN PROMPTLY: events and delivery issues are
     * delivered through one FFI listener bus, so a listener that parks (a
     * blocking wait, a runBlocking on an occupied dispatcher) wedges event
     * delivery for the whole object — and a park outlasting the core's
     * liveness window (~75 s) tears the session down around it (ADR-0026's
     * best-effort dedup case). Dispatch to a coroutine asynchronously;
     * never block inside a listener, and never call back into this object
     * synchronously from one. A listener throw is contained at the FFI
     * boundary (it can never panic the core thread) — but a throwing
     * listener is an app bug; fix it rather than rely on containment.
     */
    @Throws(TrueSealError::class)
    fun onEvent(listener: (TrueSealEvent) -> Unit): Subscription =
        mapErrors { core.onEvent(EventListenerBridge(listener)) }.let(::Subscription)

    /**
     * Registers a delivery-issue listener (optional; ADR-0026). The same
     * prompt-return rule as [onEvent] applies — events and issues share
     * the one listener bus.
     */
    @Throws(TrueSealError::class)
    fun onDeliveryIssue(listener: (DeliveryIssue) -> Unit): Subscription =
        mapErrors { core.onDeliveryIssue(IssueListenerBridge(listener)) }.let(::Subscription)

    // ── Pairing (admitting side) ──────────────────────────────────────────

    /**
     * Opens the pairing window and returns the Pairing Token for the
     * joiner (ADR-0023). Join requests arrive as `joinRequest` events.
     * The window stays open until you [accept] a request, call
     * [cancelPairing], or the session restarts — there is no timer and
     * the token does not expire. Anyone who sees the token while the
     * window is open can request to join; treat it as secret and close
     * the window promptly.
     */
    @Throws(TrueSealError::class)
    fun startPairing(): String = mapErrors { core.startPairing() }.also { refreshState() }

    /**
     * Admits a join request by its [JoinRequest.id].
     *
     * @throws TrueSealError.PairingClosed if the window is closed or the
     *   request id is unknown.
     * @throws TrueSealError.GroupFull at the Maximum Group Size (ADR-0027).
     * @throws TrueSealError.ManifestVersionExhausted if the group's version
     *   space is spent (SYNC-10).
     *
     * There is no reject — the protocol sends no decline (ADR-0023).
     */
    @Throws(TrueSealError::class)
    fun accept(requestId: String) {
        mapErrors { core.accept(requestId) }
        refreshState()
    }

    /** Closes the pairing window. Unaccepted requests disappear with it. */
    @Throws(TrueSealError::class)
    fun cancelPairing() {
        mapErrors { core.cancelPairing() }
        refreshState()
    }

    // ── Pairing (joining side) ────────────────────────────────────────────

    /**
     * Joins the group named by the Pairing Token, moving this device to
     * [GroupStatus.PendingJoin] until the first manifest arrives (ADR-0023).
     *
     * @throws TrueSealError.InvalidPairingToken if the token is malformed
     *   or its secret is spent.
     * @throws TrueSealError.AlreadyInGroup unless `status == NotJoined`.
     */
    @Throws(TrueSealError::class)
    fun join(token: String) {
        mapErrors { core.join(token) }
        refreshState()
    }

    /** Abandons a pending join, back to [GroupStatus.NotJoined]. */
    @Throws(TrueSealError::class)
    fun cancelJoin() {
        mapErrors { core.cancelJoin() }
        refreshState()
    }

    // ── Membership ────────────────────────────────────────────────────────

    /** Removes a member by id (any member may remove; ADR-0027). The
     *  removed device wipes and rotates automatically. */
    @Throws(TrueSealError::class)
    fun remove(memberId: String) {
        mapErrors { core.remove(memberId) }
        refreshState()
    }

    /** Leaves the group: [GroupStatus.Leaving] now, [GroupStatus.NotJoined]
     *  with reason `left` once the relay has accepted the Leave (ADR-0027). */
    @Throws(TrueSealError::class)
    fun leave() {
        mapErrors { core.leave() }
        refreshState()
    }

    /** Destroys the group for every current member (ADR-0029). Durable and
     *  forwarded; honoured from any past or present member. */
    @Throws(TrueSealError::class)
    fun destroyGroup() {
        mapErrors { core.destroyGroup() }
        refreshState()
    }

    // ── Cancel handle ─────────────────────────────────────────────────────

    /** Cancel handle returned by every `on…` registration (ADR-0028). */
    class Subscription internal constructor(
        private val core: FfiSubscription,
    ) {
        fun cancel() = core.cancel()
    }

    companion object {

        /**
         * Opens (or creates) Session State under [storage] and connects to
         * the relay in the background. Returns before the connection is
         * up; [send] works immediately via the outbox (ADR-0026).
         *
         * @param relay the Relay Address string,
         *   `trueseal://<relay public key hex>@host[:receivePort][?push=pushPort]`.
         *   The relay CLI prints it (`trueseal-relay address --host <host>`).
         * @param storage where Session State lives. Excluded from backups
         *   (ADR-0032); data at rest is protected by the OS sandbox.
         * @param namespace isolates independent groups on one device.
         * @param maxPayloadBytes optional operator-lowered limit; clamped
         *   at the protocol ceiling (61,440).
         */
        @Throws(TrueSealError::class)
        suspend fun open(
            relay: String,
            storage: Storage,
            namespace: String = "default",
            maxPayloadBytes: UInt? = null,
        ): TrueSeal = withContext(Dispatchers.IO) {
            val core = mapErrors {
                FfiTrueSeal.`open`(
                    relay = relay,
                    baseDir = storage.path,
                    namespace = namespace,
                    maxPayloadBytes = maxPayloadBytes,
                )
            }
            TrueSeal(core)
        }

        /** [open] with the typed Relay Address form. */
        @Throws(TrueSealError::class)
        suspend fun open(
            relay: RelayAddress,
            storage: Storage,
            namespace: String = "default",
            maxPayloadBytes: UInt? = null,
        ): TrueSeal = withContext(Dispatchers.IO) {
            val core = mapErrors {
                FfiTrueSeal.`openWithAddress`(
                    relay = relay.toFfi(),
                    baseDir = storage.path,
                    namespace = namespace,
                    maxPayloadBytes = maxPayloadBytes,
                )
            }
            TrueSeal(core)
        }

        /** Version reporting (ADR-0022/0030). */
        val info: TruesealInfo
            get() = uniffi.trueseal_sync.truesealInfo().toPublic()

        /** The Protocol Size Limit on a [send] body, in bytes (ADR-0025). */
        val maxPayloadBytes: UInt
            get() = uniffi.trueseal_sync.truesealMaxPayloadBytes()

        /** The Maximum Group Size (ADR-0027). */
        val maxGroupSize: UInt
            get() = uniffi.trueseal_sync.truesealMaxGroupSize()

        /**
         * Parses a Relay Address string into its typed form. Strict grammar
         * (ADR-0028): the scheme is mandatory, the key is exactly 64 hex
         * chars (uppercase accepted), the host never contains `@`, IPv6
         * hosts must be bracketed, `push=` is the only query parameter.
         *
         * @throws TrueSealError.InvalidRelayAddress
         */
        @Throws(TrueSealError::class)
        fun parseRelayAddress(input: String): RelayAddress =
            mapErrors { uniffi.trueseal_sync.truesealParseRelayAddress(input).toPublic() }
    }
}

/** Maps the core's typed errors to the public [TrueSealError] cases. */
internal inline fun <T> mapErrors(block: () -> T): T =
    try {
        block()
    } catch (e: FfiError) {
        throw TrueSealError.map(e)
    }
