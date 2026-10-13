package dev.lichen.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.File

// Encryption. Ed25519 (Identity.kt) proves who wrote something; this hides what it says.
// seal/openSealed: anonymous public-key box for direct messages and group keys.
// encrypt/decrypt: symmetric ChaCha20-Poly1305 with a 32-byte key.

/** A node's X25519 key. The public half is published in its signed contact record. */
class EncKey private constructor(private val key: X25519PrivateKeyParameters) {
    val pub: String = b64(key.generatePublicKey().encoded)

    /** The 32-byte private key (what the `enckey` file holds). */
    fun seed(): ByteArray = key.encoded

    internal fun agree(peerPub: ByteArray): ByteArray {
        val a = X25519Agreement()
        a.init(key)
        val out = ByteArray(a.agreementSize)
        a.calculateAgreement(X25519PublicKeyParameters(peerPub, 0), out, 0)
        return out
    }

    companion object {
        fun generate(): EncKey = EncKey(X25519PrivateKeyParameters(secureRandom))
        fun fromSeed(seed: ByteArray): EncKey = EncKey(X25519PrivateKeyParameters(seed, 0))
        fun loadOrCreate(file: File, passphrase: String? = null): EncKey {
            if (file.exists()) return fromSeed(readSecret(file.readText(), passphrase))
            return generate().also { writeSecret(file, it.seed(), passphrase) }
        }
    }
}

internal fun chachaEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
    val c = ChaCha20Poly1305()
    c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
    val out = ByteArray(c.getOutputSize(plaintext.size))
    val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
    c.doFinal(out, n)
    return out
}

/** Throws (InvalidCipherTextException etc.) when the tag doesn't verify. */
internal fun chachaDecrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray?): ByteArray {
    val c = ChaCha20Poly1305()
    c.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
    val out = ByteArray(c.getOutputSize(ct.size))
    val n = c.processBytes(ct, 0, ct.size, out, 0)
    return out.copyOf(n + c.doFinal(out, n))
}

private fun boxKey(shared: ByteArray, epk: ByteArray, rpk: ByteArray): ByteArray {
    val h = HKDFBytesGenerator(SHA256Digest())
    h.init(HKDFParameters(shared, null, "lichen-seal-v1".toByteArray() + epk + rpk))  // null salt = zero salt
    return ByteArray(32).also { h.generateBytes(it, 0, 32) }
}

/** Encrypt [plaintext] so only the holder of [recipientPub]'s EncKey can read it. Box = {"epk","n","ct"}. */
fun seal(recipientPub: String, plaintext: ByteArray): JsonObject {
    val rpk = unb64(recipientPub)
    val eph = EncKey.generate()
    val epk = unb64(eph.pub)
    val key = boxKey(eph.agree(rpk), epk, rpk)
    val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
    return obj {
        put("epk", JsonPrimitive(b64(epk)))
        put("n", JsonPrimitive(b64(nonce)))
        put("ct", JsonPrimitive(b64(chachaEncrypt(key, nonce, plaintext, null))))
    }
}

/** Open a sealed box; null if it is malformed or not for us (never throws). */
fun openSealed(me: EncKey, box: JsonElement?): ByteArray? = try {
    val o = box as JsonObject
    val epk = unb64(o["epk"].asString()!!)
    val rpk = unb64(me.pub)
    chachaDecrypt(boxKey(me.agree(epk), epk, rpk), unb64(o["n"].asString()!!), unb64(o["ct"].asString()!!), null)
} catch (e: Exception) {
    null
}

/** A fresh random 32-byte symmetric key. */
fun newKey(): ByteArray = ByteArray(32).also { secureRandom.nextBytes(it) }

/** Symmetric ChaCha20-Poly1305 with associated data. Box = {"n","ct"}. */
fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): JsonObject {
    val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
    return obj {
        put("n", JsonPrimitive(b64(nonce)))
        put("ct", JsonPrimitive(b64(chachaEncrypt(key, nonce, plaintext, aad))))
    }
}

/** Inverse of [encrypt]; null on any failure (wrong key, wrong aad, tampering, malformed). */
fun decrypt(key: ByteArray, box: JsonElement?, aad: ByteArray): ByteArray? = try {
    val o = box as JsonObject
    chachaDecrypt(key, unb64(o["n"].asString()!!), unb64(o["ct"].asString()!!), aad)
} catch (e: Exception) {
    null
}
