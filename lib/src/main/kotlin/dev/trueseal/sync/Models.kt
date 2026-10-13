package dev.trueseal.sync

import uniffi.trueseal_sync.AdmissionDropReason as FfiAdmissionDropReason
import uniffi.trueseal_sync.ConnectionState as FfiConnectionState
import uniffi.trueseal_sync.DeliveryIssue as FfiDeliveryIssue
import uniffi.trueseal_sync.GroupStatus as FfiGroupStatus
import uniffi.trueseal_sync.JoinRequest as FfiJoinRequest
import uniffi.trueseal_sync.Member as FfiMember
import uniffi.trueseal_sync.RelayAddress as FfiRelayAddress
import uniffi.trueseal_sync.SendFailedReason as FfiSendFailedReason
import uniffi.trueseal_sync.StatusReason as FfiStatusReason
import uniffi.trueseal_sync.TrueSealEvent as FfiTrueSealEvent
import uniffi.trueseal_sync.TrueSealMessage as FfiTrueSealMessage
import uniffi.trueseal_sync.TruesealInfo as FfiTruesealInfo
import uniffi.trueseal_sync.UnreadableReason as FfiUnreadableReason

/**
 * Public models for the TrueSeal SDK (sync ADR-0028).
 *
 * Every sum type exposes its canonical camelCase name as [code] — the
 * exact strings of trueseal-sync's generated `docs/shared-facts.json`.
 * The G12 parity check compares these across the Swift, Kotlin and TS
 * SDKs; the Kotlin declaration names follow Kotlin idiom, the codes carry
 * the canonical names. The mapping tests pin every one.
 */

// ── Value types ─────────────────────────────────────────────────────────────

/** A Sync Group member. `id` is stable and opaque; `name` is auto-generated
 *  from the public key and is a display label, not an identity proof. */
data class Member(val id: String, val name: String)

/** A device asking to join inside the open Pairing Window (ADR-0023).
 *  Admit it with [TrueSeal.accept]. There is no reject — an unaccepted
 *  request disappears when the window closes. */
data class JoinRequest(val id: String, val name: String)

/** The typed form of a Relay Address (ADR-0028). Parse the string form
 *  with [TrueSeal.parseRelayAddress]. The ports default to the protocol
 *  defaults 7700/7701 (signature parity with the Swift reference). */
data class RelayAddress(
    val host: String,
    /** The relay's 32-byte X25519 public key. */
    val publicKey: ByteArray,
    val receivePort: UShort = 7700u,
    val pushPort: UShort = 7701u,
) {
    override fun equals(other: Any?): Boolean =
        other is RelayAddress &&
            host == other.host &&
            publicKey.contentEquals(other.publicKey) &&
            receivePort == other.receivePort &&
            pushPort == other.pushPort

    override fun hashCode(): Int =
        31 * (31 * (31 * host.hashCode() + publicKey.contentHashCode()) + receivePort.hashCode()) + pushPort.hashCode()
}

/** An incoming message. `id` is stable across relay re-delivery — a caller
 *  sees it twice only after a crash mid-handling (at-least-once, ADR-0026).
 *  `from.id` is the same identifier as in [TrueSeal.members]. */
data class TrueSealMessage(val id: String, val from: Member, val body: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is TrueSealMessage &&
            id == other.id &&
            from == other.from &&
            body.contentEquals(other.body)

    override fun hashCode(): Int =
        31 * (31 * id.hashCode() + from.hashCode()) + body.contentHashCode()
}

/** Version reporting (ADR-0022/0030). */
data class TruesealInfo(
    val sdkVersion: String,
    val transportVersion: UInt,
    val endToEndVersion: UInt,
)

// ── Enums (canonical name carried as `code`) ────────────────────────────────

/** Group membership state (ADR-0028 `status`). */
enum class GroupStatus(val code: String) {
    NotJoined("notJoined"),
    PendingJoin("pendingJoin"),
    Member("member"),
    Leaving("leaving"),
    Destroying("destroying"),
}

/** Why an asynchronous `statusChanged` event fired (ADR-0028). */
enum class StatusReason(val code: String) {
    Created("created"),
    Joined("joined"),
    Left("left"),
    Removed("removed"),
    Destroyed("destroyed"),
    StoreReset("storeReset"),
}

/** Why an incoming message is unreadable (ADR-0034 §3). */
enum class UnreadableReason(val code: String) {
    Malformed("malformed"),
    SenderOutdated("senderOutdated"),
    Expired("expired"),
    DroppedWhileHeld("droppedWhileHeld"),
}

/** Why the library gave up sending an outgoing message (ADR-0034 §3).
 *  Every reason is permanent — the message is never retried. */
enum class SendFailedReason(val code: String) {
    TooLarge("tooLarge"),
    Malformed("malformed"),
    Expired("expired"),
}

/** Why a Pending Membership Change re-application was dropped (ADR-0027). */
enum class AdmissionDropReason(val code: String) {
    GroupFull("groupFull"),
}

// ── Sum types ───────────────────────────────────────────────────────────────

/** Relay connectivity (ADR-0028 `connection`). */
sealed class ConnectionState {
    abstract val code: String

    /** Never connected since open — the initial value, never an event. */
    object Connecting : ConnectionState() {
        override val code = "connecting"
    }

    /** The Receive Session is up. */
    object Connected : ConnectionState() {
        override val code = "connected"
    }

    /** The Receive Session dropped; the library reconnects automatically. */
    object Disconnected : ConnectionState() {
        override val code = "disconnected"
    }

    /** The Relay refused our Transport Version (ADR-0022). The outbox stays
     *  queued; the library keeps retrying in case the Relay is upgraded. */
    data class RelayVersionUnsupported(val min: UInt, val max: UInt) : ConnectionState() {
        override val code = "relayVersionUnsupported"
    }
}

/** Events delivered to [TrueSeal.onEvent] listeners (ADR-0028). */
sealed class TrueSealEvent {
    abstract val code: String

    /** An asynchronous group-status transition. Synchronous transitions
     *  (join → pendingJoin, leave → leaving, destroyGroup → destroying)
     *  raise no event — the caller learns them from the call's success. */
    data class StatusChanged(val status: GroupStatus, val reason: StatusReason) : TrueSealEvent() {
        override val code = "statusChanged"
    }

    /** The whole current member list, not a delta (ADR-0027: membership
     *  can flicker while concurrent changes settle). */
    data class MembersChanged(val members: List<Member>) : TrueSealEvent() {
        override val code = "membersChanged"
    }

    /** A device requested to join inside the open Pairing Window (ADR-0023). */
    data class JoinRequestReceived(val request: JoinRequest) : TrueSealEvent() {
        override val code = "joinRequest"
    }

    /** The relay link transitioned. */
    data class ConnectionChanged(val connection: ConnectionState) : TrueSealEvent() {
        override val code = "connectionChanged"
    }

    /** A Pending Membership Change re-application was dropped (ADR-0027).
     *  The joiner stays in Pending Join. */
    data class AdmissionDropped(val name: String, val reason: AdmissionDropReason) : TrueSealEvent() {
        override val code = "admissionDropped"
    }
}

/** Delivery issues delivered to [TrueSeal.onDeliveryIssue] listeners
 *  (ADR-0026, as amended by ADR-0034). Optional; the delivery promise
 *  does not depend on observing these. */
sealed class DeliveryIssue {
    abstract val code: String

    /** An incoming message was rejected and will never reach a handler. */
    data class Unreadable(val reason: UnreadableReason) : DeliveryIssue() {
        override val code = "unreadable"
    }

    /** An authenticated-but-unacceptable control message was acked and
     *  dropped (ADR-0034 §3). */
    object Unauthorized : DeliveryIssue() {
        override val code = "unauthorized"
    }

    /** A message carries a newer End-to-End Version than this build
     *  supports; it is held unacked on the Relay (up to the cap) so an
     *  upgrade recovers it (ADR-0022). */
    data class HeldForUpgrade(val version: UInt) : DeliveryIssue() {
        override val code = "heldForUpgrade"
    }

    /** The app's message handler failed on all 5 attempts; the message
     *  was acked and dropped (ADR-0026). */
    data class HandlerGaveUp(val messageId: String, val error: String) : DeliveryIssue() {
        override val code = "handlerGaveUp"
    }

    /** An outgoing message was dropped without delivery. [messageId] is
     *  the ID [TrueSeal.send] returned. */
    data class SendFailed(val messageId: String, val reason: SendFailedReason) : DeliveryIssue() {
        override val code = "sendFailed"
    }
}

// ── FFI → public mappings (internal) ────────────────────────────────────────

internal fun FfiMember.toPublic() = Member(id = id, name = name)

internal fun FfiJoinRequest.toPublic() = JoinRequest(id = id, name = name)

internal fun FfiTrueSealMessage.toPublic() =
    TrueSealMessage(id = id, from = from.toPublic(), body = body)

internal fun FfiTruesealInfo.toPublic() =
    TruesealInfo(
        sdkVersion = sdkVersion,
        transportVersion = transportVersion,
        endToEndVersion = endToEndVersion,
    )

internal fun FfiRelayAddress.toPublic() =
    RelayAddress(host = host, publicKey = publicKey, receivePort = receivePort, pushPort = pushPort)

internal fun RelayAddress.toFfi() =
    FfiRelayAddress(host = host, publicKey = publicKey, receivePort = receivePort, pushPort = pushPort)

internal fun FfiGroupStatus.toPublic() = when (this) {
    FfiGroupStatus.NOT_JOINED -> GroupStatus.NotJoined
    FfiGroupStatus.PENDING_JOIN -> GroupStatus.PendingJoin
    FfiGroupStatus.MEMBER -> GroupStatus.Member
    FfiGroupStatus.LEAVING -> GroupStatus.Leaving
    FfiGroupStatus.DESTROYING -> GroupStatus.Destroying
}

internal fun FfiStatusReason.toPublic() = when (this) {
    FfiStatusReason.CREATED -> StatusReason.Created
    FfiStatusReason.JOINED -> StatusReason.Joined
    FfiStatusReason.LEFT -> StatusReason.Left
    FfiStatusReason.REMOVED -> StatusReason.Removed
    FfiStatusReason.DESTROYED -> StatusReason.Destroyed
    FfiStatusReason.STORE_RESET -> StatusReason.StoreReset
}

internal fun FfiUnreadableReason.toPublic() = when (this) {
    FfiUnreadableReason.MALFORMED -> UnreadableReason.Malformed
    FfiUnreadableReason.SENDER_OUTDATED -> UnreadableReason.SenderOutdated
    FfiUnreadableReason.EXPIRED -> UnreadableReason.Expired
    FfiUnreadableReason.DROPPED_WHILE_HELD -> UnreadableReason.DroppedWhileHeld
}

internal fun FfiSendFailedReason.toPublic() = when (this) {
    FfiSendFailedReason.TOO_LARGE -> SendFailedReason.TooLarge
    FfiSendFailedReason.MALFORMED -> SendFailedReason.Malformed
    FfiSendFailedReason.EXPIRED -> SendFailedReason.Expired
}

internal fun FfiAdmissionDropReason.toPublic() = when (this) {
    FfiAdmissionDropReason.GROUP_FULL -> AdmissionDropReason.GroupFull
}

internal fun FfiConnectionState.toPublic(): ConnectionState = when (this) {
    is FfiConnectionState.Connecting -> ConnectionState.Connecting
    is FfiConnectionState.Connected -> ConnectionState.Connected
    is FfiConnectionState.Disconnected -> ConnectionState.Disconnected
    is FfiConnectionState.RelayVersionUnsupported ->
        ConnectionState.RelayVersionUnsupported(min = min, max = max)
}

internal fun FfiTrueSealEvent.toPublic(): TrueSealEvent = when (this) {
    is FfiTrueSealEvent.StatusChanged ->
        TrueSealEvent.StatusChanged(status = status.toPublic(), reason = reason.toPublic())
    is FfiTrueSealEvent.MembersChanged ->
        TrueSealEvent.MembersChanged(members = members.map { it.toPublic() })
    is FfiTrueSealEvent.JoinRequest ->
        TrueSealEvent.JoinRequestReceived(request = request.toPublic())
    is FfiTrueSealEvent.ConnectionChanged ->
        TrueSealEvent.ConnectionChanged(connection = connection.toPublic())
    is FfiTrueSealEvent.AdmissionDropped ->
        TrueSealEvent.AdmissionDropped(name = name, reason = reason.toPublic())
}

internal fun FfiDeliveryIssue.toPublic(): DeliveryIssue = when (this) {
    is FfiDeliveryIssue.Unreadable ->
        DeliveryIssue.Unreadable(reason = reason.toPublic())
    is FfiDeliveryIssue.Unauthorized ->
        DeliveryIssue.Unauthorized
    is FfiDeliveryIssue.HeldForUpgrade ->
        DeliveryIssue.HeldForUpgrade(version = version)
    is FfiDeliveryIssue.HandlerGaveUp ->
        DeliveryIssue.HandlerGaveUp(messageId = messageId, error = error)
    is FfiDeliveryIssue.SendFailed ->
        DeliveryIssue.SendFailed(messageId = messageId, reason = reason.toPublic())
}
