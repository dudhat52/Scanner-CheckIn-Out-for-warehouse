package com.idlogistics.scannercheckout

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Admin PIN: salted PBKDF2 hash, 5 wrong attempts = 5-minute lockout. */
object PinManager {
    const val MIN_LENGTH = 6
    private const val MAX_FAILS = 5
    private const val LOCK_MS = 5 * 60_000L
    private const val ITERATIONS = 10_000

    fun isSet(s: AppSettings) = !s.pinHash.isNullOrEmpty() && !s.pinSalt.isNullOrEmpty()

    fun lockedRemainingMs(s: AppSettings): Long =
        (s.pinLockUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    fun verify(s: AppSettings, pin: String): Boolean {
        if (lockedRemainingMs(s) > 0) return false
        val salt = s.pinSalt ?: return false
        val expected = s.pinHash ?: return false
        val ok = MessageDigest.isEqual(
            hash(pin, salt).toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
        if (ok) {
            s.pinFails = 0
        } else {
            val fails = s.pinFails + 1
            if (fails >= MAX_FAILS) {
                s.pinFails = 0
                s.pinLockUntil = System.currentTimeMillis() + LOCK_MS
            } else {
                s.pinFails = fails
            }
        }
        return ok
    }

    fun set(s: AppSettings, pin: String) {
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        s.pinSalt = salt
        s.pinHash = hash(pin, salt)
        s.pinFails = 0
        s.pinLockUntil = 0L
    }

    fun isValidNewPin(pin: String) = pin.length >= MIN_LENGTH && pin.all { it.isDigit() }

    private fun hash(pin: String, saltB64: String): String {
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
