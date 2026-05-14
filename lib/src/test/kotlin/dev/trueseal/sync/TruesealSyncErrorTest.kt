package dev.trueseal.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.trueseal_sync.SessionException

class TruesealSyncErrorTest {

    @Test
    fun `InvalidKeyLength maps to invalidRelayPublicKey`() {
        val ex = SessionException.InvalidKeyLength(expected = 32u, got = 10u)
        assertEquals(TruesealSyncError.InvalidRelayPublicKey, TruesealSyncError.from(ex))
    }

    @Test
    fun `InvalidRelayPublicKey maps to invalidRelayPublicKey`() {
        val ex = SessionException.InvalidRelayPublicKey()
        assertEquals(TruesealSyncError.InvalidRelayPublicKey, TruesealSyncError.from(ex))
    }

    @Test
    fun `InvalidNamespace maps with message`() {
        val ex = SessionException.InvalidNamespace(msg = "bad chars in 'foo bar'")
        assertEquals(TruesealSyncError.InvalidNamespace("bad chars in 'foo bar'"), TruesealSyncError.from(ex))
    }

    @Test
    fun `PushFailed maps with message`() {
        val ex = SessionException.PushFailed(msg = "connection refused")
        assertEquals(TruesealSyncError.PushFailed("connection refused"), TruesealSyncError.from(ex))
    }

    @Test
    fun `InvalidToken maps to InvalidPairingToken`() {
        val ex = SessionException.InvalidToken()
        assertEquals(TruesealSyncError.InvalidPairingToken, TruesealSyncError.from(ex))
    }

    @Test
    fun `NotInGroup maps to NotInGroup`() {
        val ex = SessionException.NotInGroup()
        assertEquals(TruesealSyncError.NotInGroup, TruesealSyncError.from(ex))
    }

    @Test
    fun `MemberNotFound maps to MemberNotFound`() {
        val ex = SessionException.MemberNotFound()
        assertEquals(TruesealSyncError.MemberNotFound, TruesealSyncError.from(ex))
    }

    @Test
    fun `GroupDestroyed maps to GroupDestroyed`() {
        val ex = SessionException.GroupDestroyed()
        assertEquals(TruesealSyncError.GroupDestroyed, TruesealSyncError.from(ex))
    }
}
