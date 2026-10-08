package tv.anstelle.core.data.profiles

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Profile PINs as salted PBKDF2-HMAC-SHA256, stored as `pbkdf2$iterations$salt$hash` in hex.
 * The key derivation is written out over [Mac] because the platform's
 * `PBKDF2WithHmacSHA256` only arrived in API 26, and Fire OS 6 is API 25.
 */
object PinHash {
    const val PIN_LENGTH = 4
    private const val ITERATIONS = 20_000
    private const val SALT_BYTES = 16
    private const val KEY_BYTES = 32
    private const val PREFIX = "pbkdf2"

    fun isValidPin(pin: String) = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    fun hash(pin: String, random: SecureRandom = SecureRandom()): String {
        require(isValidPin(pin)) { "A PIN is $PIN_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return listOf(PREFIX, ITERATIONS.toString(), salt.hex(), pbkdf2(pin.toByteArray(), salt, ITERATIONS, KEY_BYTES).hex()).joinToString("$")
    }

    /** Whether [pin] is the one [stored] was made from. False for anything unreadable. */
    fun verify(pin: String, stored: String): Boolean {
        if (!isValidPin(pin)) return false
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = parts[2].unhex() ?: return false
        val expected = parts[3].unhex() ?: return false
        return MessageDigest.isEqual(expected, pbkdf2(pin.toByteArray(), salt, iterations, expected.size))
    }

    /** PBKDF2 (RFC 8018) with HMAC-SHA256. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val out = ByteArray(length)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val take = minOf(t.size, length - offset)
            t.copyInto(out, offset, 0, take)
            offset += take
            block++
        }
        return out
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun String.unhex(): ByteArray? {
        if (length % 2 != 0 || isEmpty()) return null
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null }
    }
}
