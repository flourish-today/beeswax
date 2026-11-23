// SPDX-License-Identifier: CC0-1.0
package org.getflourish.beeswax

import android.content.Context
import android.os.PersistableBundle
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// Only a salted hash of the code is kept, in Beeswax's private storage, which Android won't let Settings
// clear for a device owner. 16 characters from a 32-character alphabet is 80 random bits, far too many to
// guess, so there's no limit on tries.
object Code {
    // Lowercase letters and digits, without 0, 1, l and o, which are easy to mix up.
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    const val SALT = "salt"
    const val HASH = "hash"
    private const val WAIT_LEFT = "wait_left"
    private const val WAIT_SEEN = "wait_seen"

    // Four groups of four, like k7mq-x3tp-9wfh-r2ne.
    fun create(): String {
        val random = SecureRandom()
        return (1..16).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("").chunked(4).joinToString("-")
    }

    // Clears everything first, so a new code also ends a recovery wait.
    @Synchronized
    fun save(context: Context, code: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit().clear()
            .putString(SALT, encode(salt))
            .putString(HASH, encode(hash(code, salt)))
            .commit()
    }

    // A user Beeswax creates starts with the same code. Only the salt and hash are handed over, never the code.
    fun copy(context: Context) = PersistableBundle().apply {
        putString(SALT, prefs(context).getString(SALT, null))
        putString(HASH, prefs(context).getString(HASH, null))
    }

    @Synchronized
    fun paste(context: Context, salt: String, hash: String) {
        prefs(context).edit().clear().putString(SALT, salt).putString(HASH, hash).commit()
    }

    fun same(code: String, typed: String) = normalize(code) == normalize(typed)

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    // Lost code recovery is off unless it's turned on in More settings, which needs the code.
    @Synchronized
    fun startWait(context: Context, minutes: Int) {
        prefs(context).edit()
            .putLong(WAIT_LEFT, minutes * 60_000L)
            .putLong(WAIT_SEEN, SystemClock.elapsedRealtime())
            .commit()
    }

    @Synchronized
    fun cancelWait(context: Context) {
        prefs(context).edit().remove(WAIT_LEFT).remove(WAIT_SEEN).commit()
    }

    // The milliseconds left, or null when no wait is running. Counted on the clock that runs from when the
    // phone starts, which can't be set, so changing the date doesn't shorten the wait. After a restart that
    // clock is at most the time since the last count, so the wait can only run long (time switched off
    // isn't counted), never short. Guard counts every minute the screen is on.
    @Synchronized
    fun waitLeft(context: Context): Long? {
        val prefs = prefs(context)
        if (!prefs.contains(WAIT_LEFT)) return null
        val now = SystemClock.elapsedRealtime()
        val passed = (now - prefs.getLong(WAIT_SEEN, now)).coerceAtLeast(0)
        val left = (prefs.getLong(WAIT_LEFT, 0) - passed).coerceAtLeast(0)
        prefs.edit().putLong(WAIT_LEFT, left).putLong(WAIT_SEEN, now).commit()
        return left
    }

    fun matches(context: Context, typed: String): Boolean {
        val salt = prefs(context).getString(SALT, null) ?: return false
        val stored = prefs(context).getString(HASH, null) ?: return false
        return MessageDigest.isEqual(hash(typed, decode(salt)), decode(stored))
    }

    private fun hash(code: String, salt: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update(salt)
        digest(normalize(code).toByteArray())
    }

    // Capitals, spaces and dashes don't matter when typing the code.
    private fun normalize(code: String) = code.lowercase().filter { it != '-' && !it.isWhitespace() }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String) = Base64.getDecoder().decode(text)

    private fun prefs(context: Context) = context.getSharedPreferences("code", Context.MODE_PRIVATE)
}
