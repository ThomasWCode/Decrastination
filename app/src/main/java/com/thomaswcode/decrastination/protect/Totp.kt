package com.thomaswcode.decrastination.protect

import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time codes (RFC 6238, as every authenticator app makes them): the parent
 * override decided on 8 Oct (Q17). Arming shows a secret once, as a QR code, for your dad's
 * authenticator; afterwards a code he reads out applies one pending change at once. The secret
 * lives only in the encrypted store and is never shown again. Pure.
 */
object Totp {
    const val STEP_MS = 30_000L
    const val DIGITS = 6
    private const val SECRET_BYTES = 20
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun newSecret(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    fun step(now: Long): Long = now / STEP_MS

    /** The code for [step]: HMAC-SHA1, dynamically truncated (RFC 4226 §5.3). */
    fun code(secret: ByteArray, step: Long, digits: Int = DIGITS): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /** The step [code] belongs to, allowing for a phone clock [window] steps either way, or null. */
    fun matchingStep(secret: ByteArray, code: String, now: Long, window: Int = 1): Long? {
        val wanted = code.filter { it.isDigit() }
        if (wanted.length != DIGITS) return null
        val current = step(now)
        return (-window..window).map { current + it }.firstOrNull { code(secret, it) == wanted }
    }

    fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    fun fromBase32(text: String): ByteArray {
        val clean = text.uppercase().filter { it in ALPHABET }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /** What the authenticator app scans: `otpauth://totp/…`. */
    fun uri(secret: ByteArray, account: String = "Thomas's phone", issuer: String = "Decrastination"): String {
        fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=${base32(secret)}&issuer=${enc(issuer)}&algorithm=SHA1&digits=$DIGITS&period=${STEP_MS / 1000}"
    }

    /** The secret split into fours, for typing into an authenticator by hand. */
    fun readable(secret: ByteArray): String = base32(secret).chunked(4).joinToString(" ")
}

/**
 * Code entry's memory, kept with the app's state so a restart doesn't reset it: the last step a
 * code was accepted for (each code works once), and wrong guesses (three lock entry for an hour).
 */
@Serializable
data class CodeLock(val lastUsedStep: Long = -1, val wrong: Int = 0, val lockedUntil: Long = 0) {

    sealed interface Result {
        data class Accepted(val step: Long) : Result
        data class Wrong(val triesLeft: Int) : Result
        data class Locked(val until: Long) : Result
        data object Reused : Result
    }

    fun attempt(secret: ByteArray, code: String, now: Long): Pair<Result, CodeLock> {
        if (now < lockedUntil) return Result.Locked(lockedUntil) to this
        val step = Totp.matchingStep(secret, code, now)
        return when {
            step == null -> {
                val wrong = wrong + 1
                if (wrong >= MAX_WRONG) Result.Locked(now + LOCK_MS) to copy(wrong = 0, lockedUntil = now + LOCK_MS)
                else Result.Wrong(MAX_WRONG - wrong) to copy(wrong = wrong)
            }
            step <= lastUsedStep -> Result.Reused to this
            else -> Result.Accepted(step) to CodeLock(lastUsedStep = step)
        }
    }

    companion object {
        const val MAX_WRONG = 3
        const val LOCK_MS = 60 * 60_000L
    }
}
