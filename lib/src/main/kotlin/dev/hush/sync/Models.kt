package dev.hush.sync

// ── ReceivedBlob ──────────────────────────────────────────────────────────────

/**
 * A blob delivered to this device from another Sync Group member.
 *
 * The raw bytes are whatever the sender passed to [HushSyncClient.publish].
 * Use [text] for clipboard payloads encoded as UTF-8.
 */
data class ReceivedBlob(
    /** Raw application payload. */
    val data: ByteArray,
    /**
     * The sender's 32-byte X25519 noise public key.
     * Stable per device — use as a sender identity token.
     */
    val senderPublicKey: ByteArray,
) {
    /** Convenience: interprets [data] as UTF-8 text. Returns null if not valid UTF-8. */
    val text: String?
        get() = try {
            val decoded = data.toString(Charsets.UTF_8)
            // Round-trip check: re-encoding must produce the same bytes.
            if (decoded.toByteArray(Charsets.UTF_8).contentEquals(data)) decoded else null
        } catch (_: Exception) {
            null
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceivedBlob) return false
        return data.contentEquals(other.data) && senderPublicKey.contentEquals(other.senderPublicKey)
    }

    override fun hashCode(): Int = 31 * data.contentHashCode() + senderPublicKey.contentHashCode()
}

// ── SyncMember ────────────────────────────────────────────────────────────────

/**
 * A remote device that is a current member of the Sync Group.
 */
data class SyncMember(
    /** Opaque stable identifier derived from the member's signing public key. */
    val id: String,
    /** Auto-generated human-readable name (e.g. "AmberFalcon"). */
    val name: String,
)

// ── PairingRequest ────────────────────────────────────────────────────────────

/**
 * An incoming request from another device that wants to join the Sync Group.
 *
 * Obtained from [HushSyncClient.pairingRequests].
 * Pass to [HushSyncClient.acceptPairingRequest] to admit the device.
 */
data class PairingRequest(
    /** Opaque token — pass back to [HushSyncClient.acceptPairingRequest]. Never interpret. */
    val token: String,
    /** Auto-generated name for the requesting device. */
    val deviceName: String,
)

// ── MemberEvent ───────────────────────────────────────────────────────────────

/**
 * Lifecycle events for Sync Group membership.
 *
 * Delivered via [HushSyncClient.memberEvents].
 */
sealed class MemberEvent {
    /** A new device was admitted to the Sync Group. */
    data class Joined(val member: SyncMember) : MemberEvent()

    /**
     * A device was removed via Soft Removal.
     * Does **not** fire when the local device is removed — see [RemovedSelf].
     */
    data class Left(val member: SyncMember) : MemberEvent()

    /** The local device was excluded from the Sync Group by another member. */
    object RemovedSelf : MemberEvent()

    /**
     * Any member triggered Destroy Group. Session is now terminal.
     * Reconstruct [HushSyncClient] with the same namespace to start fresh.
     */
    object GroupDestroyed : MemberEvent()
}

// ── ConnectionState ───────────────────────────────────────────────────────────

/**
 * Informational relay connection state.
 *
 * Delivered via [HushSyncClient.connectionState].
 * The library queues outbox messages and reconnects automatically —
 * use for UI indicators only, never gate on this before calling [HushSyncClient.publish].
 */
enum class ConnectionState { Connected, Disconnected }
