package tv.anstelle.core.data.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PinHashTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `pbkdf2 matches the published HMAC-SHA256 vectors`() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(PinHash.pbkdf2("password".toByteArray(), "salt".toByteArray(), 1, 32)),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(PinHash.pbkdf2("password".toByteArray(), "salt".toByteArray(), 4096, 32)),
        )
        // Two blocks (RFC 7914, section 11).
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(PinHash.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1, 64)),
        )
    }

    @Test
    fun `a stored pin verifies only itself`() {
        val stored = PinHash.hash("2580")
        assertTrue(stored.startsWith("pbkdf2$"))
        assertTrue(PinHash.verify("2580", stored))
        assertFalse(PinHash.verify("2581", stored))
        assertFalse(PinHash.verify("258", stored))
    }

    @Test
    fun `each hash has its own salt`() {
        assertNotEquals(PinHash.hash("1111"), PinHash.hash("1111"))
    }

    @Test
    fun `only four digits make a pin, and unreadable hashes never match`() {
        assertFailsWith<IllegalArgumentException> { PinHash.hash("12a4") }
        assertFailsWith<IllegalArgumentException> { PinHash.hash("12345") }
        assertFalse(PinHash.verify("1234", "pbkdf2$0$00$00"))
        assertFalse(PinHash.verify("1234", "sha1$10$00$00"))
        assertFalse(PinHash.verify("1234", "garbage"))
    }
}
