package dev.lichen.core

import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.generators.SCrypt
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Private key files, optionally protected by a passphrase. A key file holds 32 raw bytes as plain
// base64, or `lichen-key-v1:{"salt","n","ct"}` (ChaCha20-Poly1305 under scrypt(passphrase, salt)).
// On the phone they're written without a passphrase: app-private storage is the protection.

const val KEY_PREFIX = "lichen-key-v1:"
internal const val SCRYPT_N = 1 shl 15  // ~0.1 s per unlock; slows down guessing

/** The key file is encrypted and no passphrase was given. */
class PassphraseNeeded(message: String = "key file is encrypted") : Exception(message)
class WrongPassphrase(message: String = "wrong passphrase") : Exception(message)

internal fun kdf(passphrase: String, salt: ByteArray): ByteArray =
    SCrypt.generate(passphrase.toByteArray(Charsets.UTF_8), salt, SCRYPT_N, 8, 1, 32)

fun isEncrypted(text: String): Boolean = text.startsWith(KEY_PREFIX)

/** The 32 secret bytes in a key file's [text]. Throws [PassphraseNeeded] / [WrongPassphrase]. */
fun readSecret(text: String, passphrase: String? = null): ByteArray {
    val t = text.trim()
    if (!t.startsWith(KEY_PREFIX)) return unb64(t)
    if (passphrase == null) throw PassphraseNeeded()
    val box = parseObject(t.removePrefix(KEY_PREFIX)) ?: throw WrongPassphrase("malformed key file")
    try {
        return chachaDecrypt(kdf(passphrase, unb64(box["salt"].asString()!!)), unb64(box["n"].asString()!!),
            unb64(box["ct"].asString()!!), "lichen-key".toByteArray())
    } catch (e: Exception) {
        throw WrongPassphrase()
    }
}

/** Atomically write [secret] to [file] (never leaves a half-written key file). No passphrase = plain base64. */
fun writeSecret(file: File, secret: ByteArray, passphrase: String? = null) {
    val text = if (passphrase.isNullOrEmpty()) b64(secret) else {
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val ct = chachaEncrypt(kdf(passphrase, salt), nonce, secret, "lichen-key".toByteArray())
        KEY_PREFIX + dumps(obj { put("salt", JsonPrimitive(b64(salt))); put("n", JsonPrimitive(b64(nonce))); put("ct", JsonPrimitive(b64(ct))) })
    }
    writeFileAtomic(file, text)
}

/** Write via a temp file + rename so a crash never leaves a truncated file. */
internal fun writeFileAtomic(file: File, text: String) {
    file.absoluteFile.parentFile?.mkdirs()
    val tmp = File(file.absolutePath + ".tmp")
    tmp.writeText(text, Charsets.UTF_8)
    try {
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
