package dev.trueseal.sync

import org.junit.Assert.assertEquals
import org.junit.Test
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
 * Every FFI model, enum, event and issue case maps to its public
 * counterpart carrying the canonical code of shared-facts.json. Pure JVM
 * — constructing FFI data classes never loads the native lib.
 */
class ModelMappingTest {

    @Test
    fun `every GroupStatus maps with the canonical code`() {
        val cases = mapOf(
            FfiGroupStatus.NOT_JOINED to "notJoined",
            FfiGroupStatus.PENDING_JOIN to "pendingJoin",
            FfiGroupStatus.MEMBER to "member",
            FfiGroupStatus.LEAVING to "leaving",
            FfiGroupStatus.DESTROYING to "destroying",
        )
        assertEquals(FfiGroupStatus.values().size, cases.size)
        for ((ffi, code) in cases) assertEquals(code, ffi.toPublic().code)
    }

    @Test
    fun `every StatusReason maps with the canonical code`() {
        val cases = mapOf(
            FfiStatusReason.CREATED to "created",
            FfiStatusReason.JOINED to "joined",
            FfiStatusReason.LEFT to "left",
            FfiStatusReason.REMOVED to "removed",
            FfiStatusReason.DESTROYED to "destroyed",
            FfiStatusReason.STORE_RESET to "storeReset",
        )
        assertEquals(FfiStatusReason.values().size, cases.size)
        for ((ffi, code) in cases) assertEquals(code, ffi.toPublic().code)
    }

    @Test
    fun `every UnreadableReason maps with the canonical code`() {
        val cases = mapOf(
            FfiUnreadableReason.MALFORMED to "malformed",
            FfiUnreadableReason.SENDER_OUTDATED to "senderOutdated",
            FfiUnreadableReason.EXPIRED to "expired",
            FfiUnreadableReason.DROPPED_WHILE_HELD to "droppedWhileHeld",
        )
        assertEquals(FfiUnreadableReason.values().size, cases.size)
        for ((ffi, code) in cases) assertEquals(code, ffi.toPublic().code)
    }

    @Test
    fun `every SendFailedReason maps with the canonical code`() {
        val cases = mapOf(
            FfiSendFailedReason.TOO_LARGE to "tooLarge",
            FfiSendFailedReason.MALFORMED to "malformed",
            FfiSendFailedReason.EXPIRED to "expired",
        )
        assertEquals(FfiSendFailedReason.values().size, cases.size)
        for ((ffi, code) in cases) assertEquals(code, ffi.toPublic().code)
    }

    @Test
    fun `every AdmissionDropReason maps with the canonical code`() {
        val cases = mapOf(FfiAdmissionDropReason.GROUP_FULL to "groupFull")
        assertEquals(FfiAdmissionDropReason.values().size, cases.size)
        for ((ffi, code) in cases) assertEquals(code, ffi.toPublic().code)
    }

    @Test
    fun `every ConnectionState maps with the canonical code`() {
        assertEquals("connecting", FfiConnectionState.Connecting.toPublic().code)
        assertEquals("connected", FfiConnectionState.Connected.toPublic().code)
        assertEquals("disconnected", FfiConnectionState.Disconnected.toPublic().code)
        val unsupported = FfiConnectionState.RelayVersionUnsupported(min = 1u, max = 2u).toPublic()
        assertEquals("relayVersionUnsupported", unsupported.code)
        assertEquals(ConnectionState.RelayVersionUnsupported(min = 1u, max = 2u), unsupported)
    }

    @Test
    fun `every TrueSealEvent case maps with the canonical code`() {
        val member = FfiMember(id = "m1", name = "Member One")
        val cases: List<Pair<FfiTrueSealEvent, String>> = listOf(
            FfiTrueSealEvent.StatusChanged(FfiGroupStatus.MEMBER, FfiStatusReason.JOINED) to "statusChanged",
            FfiTrueSealEvent.MembersChanged(listOf(member)) to "membersChanged",
            FfiTrueSealEvent.JoinRequest(FfiJoinRequest(id = "r1", name = "Joiner")) to "joinRequest",
            FfiTrueSealEvent.ConnectionChanged(FfiConnectionState.Connected) to "connectionChanged",
            FfiTrueSealEvent.AdmissionDropped(name = "Joiner", reason = FfiAdmissionDropReason.GROUP_FULL) to "admissionDropped",
        )
        assertEquals("the canonical list has exactly 5 events", 5, cases.size)
        for ((ffi, code) in cases) assertEquals(code, ffi.toPublic().code)
    }

    @Test
    fun `event payloads survive the mapping`() {
        val status = FfiTrueSealEvent
            .StatusChanged(FfiGroupStatus.NOT_JOINED, FfiStatusReason.STORE_RESET)
            .toPublic() as TrueSealEvent.StatusChanged
        assertEquals(GroupStatus.NotJoined, status.status)
        assertEquals(StatusReason.StoreReset, status.reason)

        val members = FfiTrueSealEvent
            .MembersChanged(listOf(FfiMember(id = "m1", name = "One")))
            .toPublic() as TrueSealEvent.MembersChanged
        assertEquals(listOf(Member(id = "m1", name = "One")), members.members)

        val join = FfiTrueSealEvent
            .JoinRequest(FfiJoinRequest(id = "req-1", name = "New Phone"))
            .toPublic() as TrueSealEvent.JoinRequestReceived
        assertEquals(JoinRequest(id = "req-1", name = "New Phone"), join.request)
    }

    @Test
    fun `every DeliveryIssue case maps with the canonical code`() {
        val cases: List<Pair<FfiDeliveryIssue, String>> = listOf(
            FfiDeliveryIssue.Unreadable(FfiUnreadableReason.MALFORMED) to "unreadable",
            FfiDeliveryIssue.Unauthorized to "unauthorized",
            FfiDeliveryIssue.HeldForUpgrade(version = 2u) to "heldForUpgrade",
            FfiDeliveryIssue.HandlerGaveUp(messageId = "tsm1_x", error = "boom") to "handlerGaveUp",
            FfiDeliveryIssue.SendFailed(messageId = "tsm1_x", reason = FfiSendFailedReason.TOO_LARGE) to "sendFailed",
        )
        assertEquals("the canonical list has exactly 5 delivery issues", 5, cases.size)
        for ((ffi, code) in cases) assertEquals(code, ffi.toPublic().code)
    }

    @Test
    fun `issue payloads survive the mapping`() {
        val unreadable = FfiDeliveryIssue
            .Unreadable(FfiUnreadableReason.DROPPED_WHILE_HELD)
            .toPublic() as DeliveryIssue.Unreadable
        assertEquals(UnreadableReason.DroppedWhileHeld, unreadable.reason)

        val gaveUp = FfiDeliveryIssue
            .HandlerGaveUp(messageId = "tsm1_abc", error = "boom")
            .toPublic() as DeliveryIssue.HandlerGaveUp
        assertEquals("tsm1_abc", gaveUp.messageId)
        assertEquals("boom", gaveUp.error)

        val failed = FfiDeliveryIssue
            .SendFailed(messageId = "tsm1_def", reason = FfiSendFailedReason.EXPIRED)
            .toPublic() as DeliveryIssue.SendFailed
        assertEquals("tsm1_def", failed.messageId)
        assertEquals(SendFailedReason.Expired, failed.reason)
    }

    @Test
    fun `value types round-trip`() {
        val message = FfiTrueSealMessage(
            id = "tsm1_x",
            from = FfiMember(id = "m1", name = "One"),
            body = byteArrayOf(1, 2, 3),
        ).toPublic()
        assertEquals("tsm1_x", message.id)
        assertEquals(Member(id = "m1", name = "One"), message.from)
        assertEquals(listOf<Byte>(1, 2, 3), message.body.toList())

        val info = FfiTruesealInfo(
            sdkVersion = "0.6.0",
            transportVersion = 1u,
            endToEndVersion = 1u,
        ).toPublic()
        assertEquals(TruesealInfo("0.6.0", 1u, 1u), info)

        val address = RelayAddress(
            host = "relay.example.com",
            publicKey = ByteArray(32) { it.toByte() },
            receivePort = 7700u,
            pushPort = 7701u,
        )
        assertEquals(address, address.toFfi().toPublic())
    }
}
