package dev.trueseal.sync

import android.content.Context
import dev.trueseal.sync.internal.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import uniffi.trueseal_sync.TruesealFfiSession
import uniffi.trueseal_sync.SessionException
import java.io.File

/**
 * The entry point for the TruesealSync Android SDK.
 *
 * Wraps [TruesealFfiSession] (UniFFI-generated) with an idiomatic Kotlin/Coroutines API.
 * No FFI types, raw bytes, or Noise Protocol concepts appear in the public surface.
 *
 * ## Lifecycle
 *
 * ```kotlin
 * val client = TruesealSyncClient(
 *     context        = applicationContext,
 *     relayHost      = "relay.example.com",
 *     relayPublicKey = Base64.decode("<32-byte relay key, base64>", Base64.DEFAULT),
 * )
 * ```
 *
 * Construction is **infallible with respect to relay connectivity** — the client
 * connects in the background and queues any outbox messages until the relay is reached.
 * The only throwing conditions are invalid arguments (key length, namespace).
 *
 * Call [close] when the client is no longer needed (e.g. `onDestroy`). This destroys
 * the underlying Rust session, closes all Flow channels, and frees native resources.
 * Prefer `use { }` for scoped lifetimes.
 *
 * ## Pairing
 *
 * One device calls [generatePairingToken] and hands the token to the other device
 * out-of-band (QR code, share sheet, etc.). The other device calls [joinGroup].
 * The initiating device accepts via [pairingRequests] + [acceptPairingRequest].
 *
 * ## Publishing & receiving
 *
 * ```kotlin
 * client.publish("Hello from Android".toByteArray())
 *
 * client.blobs
 *     .onEach { blob -> println(blob.text ?: "<binary>") }
 *     .launchIn(lifecycleScope)
 * ```
 */
class TruesealSyncClient(
    context: Context,
    relayHost: String,
    relayPublicKey: ByteArray,
    storageDirectory: File = context.filesDir.resolve("TruesealSync"),
    namespace: String = "default",
) : java.io.Closeable {

    // ── Private channels (class-level so close() can reach them) ─────────────

    private val blobChannel       = Channel<ReceivedBlob>(Channel.UNLIMITED)
    private val memberChannel     = Channel<MemberEvent>(Channel.UNLIMITED)
    private val pairingChannel    = Channel<PairingRequest>(Channel.UNLIMITED)
    private val connectionChannel = Channel<ConnectionState>(Channel.UNLIMITED)

    // ── Private state ─────────────────────────────────────────────────────────

    private val session: TruesealFfiSession

    // ── Public Flows ──────────────────────────────────────────────────────────

    /**
     * Incoming blobs pushed to this device by other Sync Group members.
     *
     * Collect with `client.blobs.collect { … }` or `launchIn(scope)`.
     * The flow completes after [destroyGroup] is called (local or remote) or after [close].
     */
    val blobs: Flow<ReceivedBlob>           = blobChannel.receiveAsFlow()

    /**
     * Sync Group membership lifecycle events.
     *
     * Delivers [MemberEvent] values as they arrive.
     * Completes when the group is destroyed or [close] is called.
     */
    val memberEvents: Flow<MemberEvent>     = memberChannel.receiveAsFlow()

    /**
     * Incoming pairing requests from devices that scanned this device's token.
     *
     * Collect and call [acceptPairingRequest] to admit each device.
     * Call [cancelPairing] to close the window without admitting anyone.
     */
    val pairingRequests: Flow<PairingRequest> = pairingChannel.receiveAsFlow()

    /**
     * Informational relay connection state changes.
     *
     * The library reconnects automatically — use for UI indicators only.
     * Never gate on this before calling [publish].
     */
    val connectionState: Flow<ConnectionState> = connectionChannel.receiveAsFlow()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        storageDirectory.mkdirs()

        // Build ALL callback handlers before calling create().
        //
        // The Rust session starts its tokio runtime during create() and may fire
        // member events (e.g. from outbox replay on reconnect) on a background thread
        // before control returns to Kotlin. Pre-creating handlers ensures every event
        // is captured in the buffered channels from the first possible moment.
        //
        // Note: setOnMemberRequest / setOnMemberJoined / setOnMemberLeft are separate
        // FFI setters (not constructor args) — there is a theoretical window between
        // create() returning and those setters being called. In practice this window
        // is sub-millisecond and all three events require multi-hop network activity
        // to trigger, but be aware of it for reconnect-replay scenarios.
        val blobHandler       = BlobCallbackHandler(blobChannel)
        val removedHandler    = MemberRemovedCallbackHandler(memberChannel)
        val destroyedHandler  = GroupDestroyedCallbackHandler(memberChannel, blobChannel, pairingChannel, connectionChannel)
        val connHandler       = ConnectionChangedCallbackHandler(connectionChannel)
        val pairingHandler    = PairingRequestCallbackHandler(pairingChannel)
        val joinedHandler     = MemberJoinedCallbackHandler(memberChannel)
        val leftHandler       = MemberLeftCallbackHandler(memberChannel)

        try {
            session = TruesealFfiSession.`create`(
                baseDir             = storageDirectory.absolutePath,
                namespace           = namespace,
                relayHost           = relayHost,
                relayPub            = relayPublicKey,
                onMessage           = blobHandler,
                onRemovedFromGroup  = removedHandler,
                onGroupDestroyed    = destroyedHandler,
                onConnectionChanged = connHandler,
            )
        } catch (e: SessionException) {
            throw TruesealSyncError.from(e)
        }

        session.`setOnMemberRequest`(pairingHandler)
        session.`setOnMemberJoined`(joinedHandler)
        session.`setOnMemberLeft`(leftHandler)
    }

    // ── Pairing ───────────────────────────────────────────────────────────────

    /**
     * Open a pairing window and return an opaque pairing token.
     *
     * Pass the token to the joining device out-of-band (QR code, share sheet, etc.).
     * That device calls [joinGroup] with it. This device then receives a [PairingRequest]
     * via [pairingRequests] and must call [acceptPairingRequest] to complete the ceremony.
     *
     * @return Base64url-encoded pairing token. Single-use.
     */
    fun generatePairingToken(): String = session.`pairingToken`()

    /**
     * Join a Sync Group as the responding device.
     *
     * Decodes the initiator's pairing token and pushes a `Pair` message via the relay.
     * The initiating device will receive a [PairingRequest] and must call
     * [acceptPairingRequest] to finalise membership.
     *
     * @param token Token obtained from the initiating device (QR scan, etc.).
     * @throws TruesealSyncError.InvalidPairingToken if the token is malformed or expired.
     */
    @Throws(TruesealSyncError::class)
    fun joinGroup(token: String) {
        try {
            session.`joinGroup`(token)
        } catch (e: SessionException) {
            throw TruesealSyncError.from(e)
        }
    }

    /**
     * Admit a device that sent a pairing request.
     *
     * Call after receiving a [PairingRequest] from [pairingRequests].
     * Silently ignored if the token is unknown or the window has closed.
     *
     * @param request The request received from [pairingRequests].
     */
    fun acceptPairingRequest(request: PairingRequest) {
        try {
            session.`acceptMember`(request.token)
        } catch (e: SessionException) {
            throw TruesealSyncError.from(e)
        }
    }

    /**
     * Close the pairing window without admitting any device.
     */
    fun cancelPairing() {
        try {
            session.`cancelPairing`()
        } catch (e: SessionException) {
            throw TruesealSyncError.from(e)
        }
    }

    // ── Publishing ────────────────────────────────────────────────────────────

    /**
     * Encrypt [data] and fan it out to every current Sync Group member.
     *
     * If the relay is unreachable the blob is durably queued in the local outbox
     * and delivered automatically on reconnect. Do **not** retry on [TruesealSyncError.PushFailed]
     * — the blob is already queued.
     *
     * @param data Application payload. The relay never sees the plaintext.
     * @throws TruesealSyncError.NotInGroup if pairing has not completed.
     * @throws TruesealSyncError.GroupDestroyed if the group has been destroyed.
     */
    @Throws(TruesealSyncError::class)
    fun publish(data: ByteArray) {
        try {
            session.`send`(data)
        } catch (e: SessionException) {
            throw TruesealSyncError.from(e)
        }
    }

    /**
     * Convenience: publish a UTF-8 string.
     *
     * @throws TruesealSyncError Same as [publish].
     */
    @Throws(TruesealSyncError::class)
    fun publish(text: String) = publish(text.toByteArray(Charsets.UTF_8))

    // ── Members ───────────────────────────────────────────────────────────────

    /**
     * Stable opaque identifier for the local device.
     *
     * Identical to the `id` this device has in a remote peer's [members] list.
     *
     * **Note:** each access makes an FFI call. Cache if read frequently.
     */
    val localNodeId: String get() = session.`localNodeId`()

    /**
     * Auto-generated display name for the local device.
     *
     * Identical to the `name` this device has in a remote peer's [members] list.
     *
     * **Note:** each access makes an FFI call. Cache if read frequently.
     */
    val localDeviceName: String get() = session.`localDeviceName`()

    /**
     * Current remote Sync Group members (excludes the local device).
     *
     * Empty until the first pairing completes.
     *
     * **Note:** each access makes an FFI call and returns a new snapshot list.
     * Cache the result if you need a stable reference for the same operation.
     */
    val members: List<SyncMember>
        get() = session.`members`().map { SyncMember(id = it.id, name = it.name) }

    /**
     * Remove a device from the Sync Group (Soft Removal).
     *
     * Issues a new Group Manifest excluding the target and propagates it to all
     * remaining members. The removed device fires [MemberEvent.RemovedSelf].
     *
     * @param member A value obtained from [members].
     * @throws TruesealSyncError.NotInGroup
     * @throws TruesealSyncError.MemberNotFound
     */
    @Throws(TruesealSyncError::class)
    fun removeMember(member: SyncMember) {
        try {
            session.`removeMember`(member.id)
        } catch (e: SessionException) {
            throw TruesealSyncError.from(e)
        }
    }

    // ── Destroy Group ─────────────────────────────────────────────────────────

    /**
     * Destroy the Sync Group.
     *
     * Pushes a `Revoke` message to every member, rotates this device's keypair,
     * and wipes local session state. Every device fires [MemberEvent.GroupDestroyed].
     * All flows complete after this call.
     *
     * Use for security incidents (stolen/compromised device). For routine member
     * removal, prefer [removeMember].
     *
     * After calling this, create a new [TruesealSyncClient] with the same namespace to
     * start fresh with a new identity. Note: you should also call [close] on this
     * instance to release native resources.
     */
    fun destroyGroup() {
        try {
            session.`destroyGroup`()
        } catch (e: SessionException) {
            throw TruesealSyncError.from(e)
        }
    }

    // ── Closeable ─────────────────────────────────────────────────────────────

    /**
     * Release all resources held by this client.
     *
     * Closes the underlying Rust session (stops background tasks, flushes the outbox)
     * and completes all public [Flow]s. Safe to call multiple times.
     *
     * Call from `onDestroy` or use the `use { }` block:
     * ```kotlin
     * TruesealSyncClient(...).use { client ->
     *     client.publish("hello")
     * }
     * ```
     */
    override fun close() {
        // Close channels first so any in-flight callbacks trySend() silently.
        blobChannel.close()
        memberChannel.close()
        pairingChannel.close()
        connectionChannel.close()
        // Destroy the native session — stops background threads and flushes outbox.
        session.destroy()
    }
}
