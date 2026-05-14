package dev.hush.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.hush_sync.SessionException

class HushSyncErrorTest {

    @Test
    fun `InvalidKeyLength maps to invalidRelayPublicKey`() {
        val ex = SessionException.InvalidKeyLength(expected = 32u, got = 10u)
        assertEquals(HushSyncError.InvalidRelayPublicKey, HushSyncError.from(ex))
    }

    @Test
    fun `InvalidRelayPublicKey maps to invalidRelayPublicKey`() {
        val ex = SessionException.InvalidRelayPublicKey()
        assertEquals(HushSyncError.InvalidRelayPublicKey, HushSyncError.from(ex))
    }

    @Test
    fun `InvalidNamespace maps with message`() {
        val ex = SessionException.InvalidNamespace(msg = "bad chars in 'foo bar'")
        assertEquals(HushSyncError.InvalidNamespace("bad chars in 'foo bar'"), HushSyncError.from(ex))
    }

    @Test
    fun `PushFailed maps with message`() {
        val ex = SessionException.PushFailed(msg = "connection refused")
        assertEquals(HushSyncError.PushFailed("connection refused"), HushSyncError.from(ex))
    }

    @Test
    fun `InvalidToken maps to InvalidPairingToken`() {
        val ex = SessionException.InvalidToken()
        assertEquals(HushSyncError.InvalidPairingToken, HushSyncError.from(ex))
    }

    @Test
    fun `NotInGroup maps to NotInGroup`() {
        val ex = SessionException.NotInGroup()
        assertEquals(HushSyncError.NotInGroup, HushSyncError.from(ex))
    }

    @Test
    fun `MemberNotFound maps to MemberNotFound`() {
        val ex = SessionException.MemberNotFound()
        assertEquals(HushSyncError.MemberNotFound, HushSyncError.from(ex))
    }

    @Test
    fun `GroupDestroyed maps to GroupDestroyed`() {
        val ex = SessionException.GroupDestroyed()
        assertEquals(HushSyncError.GroupDestroyed, HushSyncError.from(ex))
    }
}
