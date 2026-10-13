package dev.lichen.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// Ed25519 identities. A person *is* their public key; addresses are just where they currently live.

internal val secureRandom = SecureRandom()

/** URL-safe base64 without padding. */
fun b64(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

/** Inverse of [b64]; padding is optional. Throws IllegalArgumentException on garbage. */
fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trimEnd('='))

fun short(pubkey: String): String = pubkey.take(8)

/** A key in readable groups, for comparing out loud: e.g. 3fK9 xQ2m 7aLp Wc1d. */
fun fingerprint(pubkey: String): String =
    (0 until 16 step 4).joinToString(" ") { if (it >= pubkey.length) "" else pubkey.substring(it, minOf(it + 4, pubkey.length)) }

/** Same 20 digits on both sides of a conversation; compare them to check you have the right key. */
fun safetyCode(a: String, b: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(listOf(a, b).sortedWith(CODE_POINT_ORDER).joinToString("|").toByteArray(Charsets.UTF_8))
    val digits = BigInteger(1, digest.copyOf(12)).toString().padStart(29, '0').takeLast(20)
    return digits.chunked(5).joinToString(" ")
}

class Identity private constructor(private val key: Ed25519PrivateKeyParameters) {
    /** b64 of the raw 32-byte public key: this *is* the person. */
    val pubkey: String = b64(key.generatePublicKey().encoded)

    /** The 32-byte private seed (what the `key` file holds). */
    fun seed(): ByteArray = key.encoded

    /** Detached signature, b64. */
    fun sign(data: ByteArray): String {
        val s = Ed25519Signer()
        s.init(true, key)
        s.update(data, 0, data.size)
        return b64(s.generateSignature())
    }

    companion object {
        fun generate(): Identity = Identity(Ed25519PrivateKeyParameters(secureRandom))
        fun fromSeed(seed: ByteArray): Identity = Identity(Ed25519PrivateKeyParameters(seed, 0))

        /** Load the key file, or make a new identity and write it. */
        fun loadOrCreate(file: File, passphrase: String? = null): Identity {
            if (file.exists()) return fromSeed(readSecret(file.readText(), passphrase))
            return generate().also { writeSecret(file, it.seed(), passphrase) }
        }
    }
}

/** True if [sig] is a valid signature of [data] by [pubkey]. Never throws: bad input just isn't valid. */
fun verify(pubkey: String, data: ByteArray, sig: String): Boolean = try {
    val v = Ed25519Signer()
    v.init(false, Ed25519PublicKeyParameters(unb64(pubkey), 0))
    v.update(data, 0, data.size)
    v.verifySignature(unb64(sig))
} catch (e: Exception) {
    false
}
