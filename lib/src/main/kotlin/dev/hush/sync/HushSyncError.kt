package dev.hush.sync

import uniffi.hush_sync.SessionException

/**
 * All errors surfaced by [HushSyncClient].
 *
 * Maps one-to-one with the Rust `SessionError` variants; no FFI types leak through.
 */
sealed class HushSyncError : Exception() {

    /** Relay URL missing a host component. */
    object InvalidRelayUrl : HushSyncError()

    /** The relay public key was not exactly 32 bytes. */
    object InvalidRelayPublicKey : HushSyncError()

    /** The namespace string contains illegal characters or is empty. Valid pattern: `[a-zA-Z0-9_-]+` */
    data class InvalidNamespace(val reason: String) : HushSyncError()

    /**
     * Push (blob fan-out) failed. The blob was durably queued in the outbox
     * and will be retried on reconnect — this error is informational.
     */
    data class PushFailed(val reason: String) : HushSyncError()

    /** The pairing token was malformed or expired. */
    object InvalidPairingToken : HushSyncError()

    /** An operation that requires group membership was attempted before pairing. */
    object NotInGroup : HushSyncError()

    /** The target member ID was not found in the current Group Manifest. */
    object MemberNotFound : HushSyncError()

    /**
     * The group has been destroyed. Reconstruct [HushSyncClient] with the same
     * namespace to start fresh with a new identity.
     */
    object GroupDestroyed : HushSyncError()

    override val message: String
        get() = when (this) {
            is InvalidRelayUrl         -> "Invalid relay URL — must include a host."
            is InvalidRelayPublicKey   -> "Relay public key must be exactly 32 bytes."
            is InvalidNamespace        -> "Invalid namespace: $reason"
            is PushFailed              -> "Push failed: $reason"
            is InvalidPairingToken     -> "The pairing token is invalid or has expired."
            is NotInGroup              -> "Not in any Sync Group — complete pairing first."
            is MemberNotFound          -> "Member not found in the current Sync Group."
            is GroupDestroyed          -> "The Sync Group has been destroyed."
        }

    companion object {
        /** Map a UniFFI [SessionException] to a [HushSyncError]. Internal use only. */
        internal fun from(e: SessionException): HushSyncError = when (e) {
            is SessionException.InvalidKeyLength      -> InvalidRelayPublicKey
            is SessionException.InvalidRelayPublicKey -> InvalidRelayPublicKey
            is SessionException.InvalidNamespace      -> InvalidNamespace(e.`msg`)
            is SessionException.PushFailed            -> PushFailed(e.`msg`)
            is SessionException.InvalidToken          -> InvalidPairingToken
            is SessionException.NotInGroup            -> NotInGroup
            is SessionException.MemberNotFound        -> MemberNotFound
            is SessionException.GroupDestroyed        -> GroupDestroyed
        }
    }
}
