package dev.trueseal.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceivedBlobTest {

    @Test
    fun `text returns UTF-8 string when payload is valid UTF-8`() {
        val blob = ReceivedBlob(
            data = "Hello, trueseal!".toByteArray(Charsets.UTF_8),
            senderPublicKey = ByteArray(32)
        )
        assertEquals("Hello, trueseal!", blob.text)
    }

    @Test
    fun `text returns null for non-UTF-8 bytes`() {
        val blob = ReceivedBlob(
            data = byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            senderPublicKey = ByteArray(32)
        )
        assertNull(blob.text)
    }

    @Test
    fun `senderPublicKey is preserved`() {
        val key = ByteArray(32) { it.toByte() }
        val blob = ReceivedBlob(data = ByteArray(0), senderPublicKey = key)
        assertEquals(key.toList(), blob.senderPublicKey.toList())
    }
}
