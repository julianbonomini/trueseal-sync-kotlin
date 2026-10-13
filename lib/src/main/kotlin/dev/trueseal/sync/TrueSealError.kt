package dev.trueseal.sync

import uniffi.trueseal_sync.TrueSealException as FfiError

/**
 * Every error the TrueSeal SDK can raise (sync ADR-0028, as amended by
 * ADR-0032 and SYNC-15). Exactly 14 cases, one-to-one with the core —
 * there is no catch-all.
 *
 * Each case carries its canonical camelCase name as [code] — the exact
 * strings of trueseal-sync's generated `docs/shared-facts.json`. The G12
 * parity check compares these across the Swift, Kotlin and TS SDKs.
 */
sealed class TrueSealError(message: String) : Exception(message) {

    abstract val code: String

    /** The Relay Address string is malformed (ADR-0028). Nothing was dialed. */
    class InvalidRelayAddress(val msg: String) : TrueSealError("Invalid relay address: $msg") {
        override val code = "invalidRelayAddress"
    }

    /** The namespace is empty or outside `[a-zA-Z0-9_-]+`. */
    class InvalidNamespace(val msg: String) : TrueSealError("Invalid namespace: $msg") {
        override val code = "invalidNamespace"
    }

    /** A durable write failed — the operation did not commit and no
     *  in-memory state changed. The caller may retry. */
    class Storage(val msg: String) :
        TrueSealError("Storage error (nothing was committed; retry is safe): $msg") {
        override val code = "storage"
    }

    /** The object was closed (`close()`, ADR-0028). Every call after
     *  `close()` fails with this; Session State is kept on disk. */
    class Closed : TrueSealError("This TrueSeal object is closed.") {
        override val code = "closed"
    }

    /** The operation needs group membership the device does not currently
     *  hold — sending while `notJoined`, `pendingJoin`, `leaving` or
     *  `destroying`, for example. Carries the status the call saw. */
    class NotMember(val status: GroupStatus) :
        TrueSealError("Operation requires group membership (current status: ${status.code}).") {
        override val code = "notMember"
    }

    /** `join()` while this device already holds a Group Manifest — one
     *  Sync Group per namespace (ADR-0023). */
    class AlreadyInGroup : TrueSealError("This device is already in a group.") {
        override val code = "alreadyInGroup"
    }

    /** The Pairing Token is malformed, or its Pairing Secret was already
     *  spent (ADR-0023). */
    class InvalidPairingToken :
        TrueSealError("The pairing token is invalid or already used.") {
        override val code = "invalidPairingToken"
    }

    /** The Pairing Window is closed, or the Join Request ID is unknown —
     *  an unaccepted request disappears when the window closes (ADR-0023).
     *  There is no timer; the token does not expire. */
    class PairingClosed : TrueSealError("The pairing window is closed.") {
        override val code = "pairingClosed"
    }

    /** The admission would exceed the Maximum Group Size (ADR-0027).
     *  The Pairing Window stays open. */
    class GroupFull(val max: UInt) : TrueSealError("The group is full (max $max).") {
        override val code = "groupFull"
    }

    /** `remove(memberId)` named an id that is not a current member. */
    class MemberNotFound : TrueSealError("No member with that ID.") {
        override val code = "memberNotFound"
    }

    /** The body exceeds the effective Protocol Size Limit (ADR-0025).
     *  Nothing was queued; retrying the same body always fails. */
    class PayloadTooLarge(val max: UInt) :
        TrueSealError("The payload exceeds the limit of $max bytes.") {
        override val code = "payloadTooLarge"
    }

    /** A second `onMessage` registration. Registration is one-shot per
     *  object (ADR-0028); cancelling the returned [TrueSeal.Subscription]
     *  does not re-enable it. */
    class MessageHandlerAlreadySet :
        TrueSealError("A message handler is already registered.") {
        override val code = "messageHandlerAlreadySet"
    }

    /** Session State on disk was written by a newer release (ADR-0022/0032).
     *  Nothing was touched; upgrade the SDK to open it. */
    class StoreTooNew(val found: UInt, val supported: UInt) :
        TrueSealError("Session State was written by a newer release (found v$found, this SDK supports v$supported). Upgrade the SDK.") {
        override val code = "storeTooNew"
    }

    /** The current manifest's version cannot be incremented (SYNC-10) —
     *  the group is frozen for good and its devices must pair again. */
    class ManifestVersionExhausted :
        TrueSealError("The group's manifest version space is exhausted; the devices must pair again.") {
        override val code = "manifestVersionExhausted"
    }

    internal companion object {
        fun map(error: FfiError): TrueSealError = when (error) {
            is FfiError.InvalidRelayAddress -> InvalidRelayAddress(error.msg)
            is FfiError.InvalidNamespace -> InvalidNamespace(error.msg)
            is FfiError.Storage -> Storage(error.msg)
            is FfiError.Closed -> Closed()
            is FfiError.NotMember -> NotMember(error.status.toPublic())
            is FfiError.AlreadyInGroup -> AlreadyInGroup()
            is FfiError.InvalidPairingToken -> InvalidPairingToken()
            is FfiError.PairingClosed -> PairingClosed()
            is FfiError.GroupFull -> GroupFull(error.max)
            is FfiError.MemberNotFound -> MemberNotFound()
            is FfiError.PayloadTooLarge -> PayloadTooLarge(error.max)
            is FfiError.MessageHandlerAlreadySet -> MessageHandlerAlreadySet()
            is FfiError.StoreTooNew -> StoreTooNew(found = error.found, supported = error.supported)
            is FfiError.ManifestVersionExhausted -> ManifestVersionExhausted()
        }
    }
}
