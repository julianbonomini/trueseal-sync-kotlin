package dev.hush.sync

import android.content.Context
import dev.hush.sync.internal.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import uniffi.hush_sync.HushFfiSession
import uniffi.hush_sync.SessionException
import java.io.File

/**
 * The entry point for the HushSync Android SDK.
 *
 * Wraps [HushFfiSession] (UniFFI-generated) with an idiomatic Kotlin/Coroutines API.
 * No FFI types, raw bytes, or Noise Protocol concepts appear in the public surface.
 *
 * ## Lifecycle
 *
 * ```kotlin
 * val client = HushSyncClient(
 *     context       = applicationContext,
 *     relayHost     = "relay.example.com",
 *     relayPublicKey = Base64.decode("<32-byte relay key, base64>", Base64.DEFAULT),
 * )
 * ```
 *
 * Construction is **infallible with respect to relay connectivity** — the client
 * connects in the background and queues any outbox messages until the relay is reached.
 * The only throwing conditions are invalid arguments (key length, namespace).
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
class HushSyncClient(
    context: Context,
    relayHost: String,
    relayPublicKey: ByteArray,
    storageDirectory: File = context.filesDir.resolve("HushSync"),
    namespace: String = "default",
) {

    // ── Private state ─────────────────────────────────────────────────────────

    private val session: HushFfiSession

    // ── Public Flows ──────────────────────────────────────────────────────────

    /**
     * Incoming blobs pushed to this device by other Sync Group members.
     *
     * Collect with `client.blobs.collect { … }` or `launchIn(scope)`.
     * The flow completes only after [destroyGroup] is called (local or remote).
     */
    val blobs: Flow<ReceivedBlob>

    /**
     * Sync Group membership lifecycle events.
     *
     * Delivers [MemberEvent] values as they arrive.
     * Completes when the group is destroyed.
     */
    val memberEvents: Flow<MemberEvent>

    /**
     * Incoming pairing requests from devices that scanned this device's token.
     *
     * Collect and call [acceptPairingRequest] to admit each device.
     * Call [cancelPairing] to close the window without admitting anyone.
     */
    val pairingRequests: Flow<PairingRequest>

    /**
     * Informational relay connection state changes.
     *
     * The library reconnects automatically — use for UI indicators only.
     * Never gate on this before calling [publish].
     */
    val connectionState: Flow<ConnectionState>

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        storageDirectory.mkdirs()

        val blobChannel       = Channel<ReceivedBlob>(Channel.UNLIMITED)
        val memberChannel     = Channel<MemberEvent>(Channel.UNLIMITED)
        val pairingChannel    = Channel<PairingRequest>(Channel.UNLIMITED)
        val connectionChannel = Channel<ConnectionState>(Channel.UNLIMITED)

        blobs           = blobChannel.receiveAsFlow()
        memberEvents    = memberChannel.receiveAsFlow()
        pairingRequests = pairingChannel.receiveAsFlow()
        connectionState = connectionChannel.receiveAsFlow()

        try {
            session = HushFfiSession.`create`(
                baseDir              = storageDirectory.absolutePath,
                namespace            = namespace,
                relayHost            = relayHost,
                relayPub             = relayPublicKey,
                onMessage            = BlobCallbackHandler(blobChannel),
                onRemovedFromGroup   = MemberRemovedCallbackHandler(memberChannel),
                onGroupDestroyed     = GroupDestroyedCallbackHandler(memberChannel),
                onConnectionChanged  = ConnectionChangedCallbackHandler(connectionChannel),
            )
        } catch (e: SessionException) {
            throw HushSyncError.from(e)
        }

        session.`setOnMemberRequest`(PairingRequestCallbackHandler(pairingChannel))
        session.`setOnMemberJoined`(MemberJoinedCallbackHandler(memberChannel))
        session.`setOnMemberLeft`(MemberLeftCallbackHandler(memberChannel))
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
     * @throws HushSyncError.InvalidPairingToken if the token is malformed or expired.
     */
    @Throws(HushSyncError::class)
    fun joinGroup(token: String) {
        try {
            session.`joinGroup`(token)
        } catch (e: SessionException) {
            throw HushSyncError.from(e)
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
        session.`acceptMember`(request.token)
    }

    /**
     * Close the pairing window without admitting any device.
     */
    fun cancelPairing() = session.`cancelPairing`()

    // ── Publishing ────────────────────────────────────────────────────────────

    /**
     * Encrypt [data] and fan it out to every current Sync Group member.
     *
     * If the relay is unreachable the blob is durably queued in the local outbox
     * and delivered automatically on reconnect. Do **not** retry on [HushSyncError.PushFailed]
     * — the blob is already queued.
     *
     * @param data Application payload. The relay never sees the plaintext.
     * @throws HushSyncError.NotInGroup if pairing has not completed.
     * @throws HushSyncError.GroupDestroyed if the group has been destroyed.
     */
    @Throws(HushSyncError::class)
    fun publish(data: ByteArray) {
        try {
            session.`send`(data)
        } catch (e: SessionException) {
            throw HushSyncError.from(e)
        }
    }

    /**
     * Convenience: publish a UTF-8 string.
     *
     * @throws HushSyncError Same as [publish].
     */
    @Throws(HushSyncError::class)
    fun publish(text: String) = publish(text.toByteArray(Charsets.UTF_8))

    // ── Members ───────────────────────────────────────────────────────────────

    /**
     * Stable opaque identifier for the local device.
     *
     * Identical to the `id` this device has in a remote peer's [members] list.
     */
    val localNodeId: String get() = session.`localNodeId`()

    /**
     * Auto-generated display name for the local device.
     *
     * Identical to the `name` this device has in a remote peer's [members] list.
     */
    val localDeviceName: String get() = session.`localDeviceName`()

    /**
     * Current remote Sync Group members (excludes the local device).
     *
     * Empty until the first pairing completes.
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
     * @throws HushSyncError.NotInGroup
     * @throws HushSyncError.MemberNotFound
     */
    @Throws(HushSyncError::class)
    fun removeMember(member: SyncMember) {
        try {
            session.`removeMember`(member.id)
        } catch (e: SessionException) {
            throw HushSyncError.from(e)
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
     * After calling this, create a new [HushSyncClient] with the same namespace to
     * start fresh with a new identity.
     */
    fun destroyGroup() = session.`destroyGroup`()
}
