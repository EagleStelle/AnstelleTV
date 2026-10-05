package tv.anstelle.webserver.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object Tokens {
    private val random = SecureRandom()

    /** 256-bit random token, URL-safe base64 without padding. */
    fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Uniform 6-digit PIN, zero-padded. */
    fun newPin(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    fun newRequestId(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** SHA-256 hex. Only this hash of a token is ever stored. */
    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    /** Compares secrets in constant time. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private const val HEX = "0123456789abcdef"
}
