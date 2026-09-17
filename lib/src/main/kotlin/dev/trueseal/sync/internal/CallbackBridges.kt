package dev.trueseal.sync.internal

import dev.trueseal.sync.*
import kotlinx.coroutines.channels.SendChannel
import uniffi.trueseal_sync.*

// Internal callback bridge objects.
//
// Each class implements one of the UniFFI callback interfaces and forwards events
// into a Kotlin Channel that backs the public Flow exposed by TruesealSyncClient.
// None of these types are public — they live entirely behind the package boundary.

// ── Blob ─────────────────────────────────────────────────────────────────────

internal class BlobCallbackHandler(
    private val channel: SendChannel<ReceivedBlob>,
) : MessageCallback {
    override fun `onMessage`(blob: ByteArray, senderNoisePub: ByteArray, messageId: String) {
        channel.trySend(
            ReceivedBlob(data = blob, senderPublicKey = senderNoisePub, messageId = messageId)
        )
    }
}

// ── Removed from group ────────────────────────────────────────────────────────

internal class MemberRemovedCallbackHandler(
    private val channel: SendChannel<MemberEvent>,
) : RemovedFromGroupCallback {
    override fun `onRemovedFromGroup`() {
        channel.trySend(MemberEvent.RemovedSelf)
    }
}

// ── Group destroyed ───────────────────────────────────────────────────────────

internal class GroupDestroyedCallbackHandler(
    private val memberChannel:     SendChannel<MemberEvent>,
    private val blobChannel:       SendChannel<ReceivedBlob>,
    private val pairingChannel:    SendChannel<PairingRequest>,
    private val connectionChannel: SendChannel<ConnectionState>,
) : GroupDestroyedCallback {
    override fun `onGroupDestroyed`() {
        memberChannel.trySend(MemberEvent.GroupDestroyed)
        // Close all channels so every Flow collector reaches onCompletion.
        memberChannel.close()
        blobChannel.close()
        pairingChannel.close()
        connectionChannel.close()
    }
}

// ── Pairing request ───────────────────────────────────────────────────────────

internal class PairingRequestCallbackHandler(
    private val channel: SendChannel<PairingRequest>,
) : MemberRequestCallback {
    override fun `onMemberRequest`(token: String, name: String) {
        channel.trySend(PairingRequest(token = token, deviceName = name))
    }
}

// ── Member joined ─────────────────────────────────────────────────────────────

internal class MemberJoinedCallbackHandler(
    private val channel: SendChannel<MemberEvent>,
) : MemberJoinedCallback {
    override fun `onMemberJoined`(memberId: String, memberName: String) {
        channel.trySend(MemberEvent.Joined(SyncMember(id = memberId, name = memberName)))
    }
}

// ── Member left ───────────────────────────────────────────────────────────────

internal class MemberLeftCallbackHandler(
    private val channel: SendChannel<MemberEvent>,
) : MemberLeftCallback {
    override fun `onMemberLeft`(memberId: String, memberName: String) {
        channel.trySend(MemberEvent.Left(SyncMember(id = memberId, name = memberName)))
    }
}

// ── Connection changed ────────────────────────────────────────────────────────

internal class ConnectionChangedCallbackHandler(
    private val channel: SendChannel<ConnectionState>,
) : ConnectionChangedCallback {
    override fun `onConnectionChanged`(connected: Boolean) {
        channel.trySend(if (connected) ConnectionState.Connected else ConnectionState.Disconnected)
    }
}
