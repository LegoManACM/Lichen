package dev.lichen.core

import kotlinx.serialization.json.JsonObject
import java.io.File

// Using one identity on several devices. The phone becomes you by importing `lichen --export`'s file:
// an encrypted bundle of keys, state files and a lichen.yaml (see devices.py).

const val EXPORT_PREFIX = "lichen-identity-v1:"
val KEY_FILES = listOf("key", "enckey")
val STATE_FILES = listOf("trust.json", "phonebook.json", "groups.json", "dms.json", "outbox.json")
const val LINKED = "linked"  // marker: this identity lives on more than one device

/** This device's id: random, made once per data folder, never part of an export. */
fun deviceId(dataDir: File): String {
    val f = File(dataDir, "device")
    if (f.exists()) return f.readText().trim()
    dataDir.mkdirs()
    return b64(ByteArray(6).also { secureRandom.nextBytes(it) }).also { f.writeText(it) }
}

fun isLinked(dataDir: File): Boolean = File(dataDir, LINKED).exists()

fun markLinked(dataDir: File) {
    dataDir.mkdirs()
    File(dataDir, LINKED).writeText("1")
}

/** Decrypt an export's text into its bundle {"keys","files","config"?}. Throws IllegalArgumentException if it isn't an export or the passphrase is wrong. */
fun openExport(text: String, passphrase: String): JsonObject {
    val t = text.trim()
    require(t.startsWith(EXPORT_PREFIX)) { "not a Lichen identity export" }
    val box = parseObject(t.removePrefix(EXPORT_PREFIX)) ?: throw IllegalArgumentException("not a Lichen identity export")
    val plain = try {
        chachaDecrypt(kdf(passphrase, unb64(box["salt"].asString()!!)), unb64(box["n"].asString()!!),
            unb64(box["ct"].asString()!!), "lichen-identity".toByteArray())
    } catch (e: Exception) {
        throw IllegalArgumentException("wrong passphrase for this export")
    }
    return parseObject(plain.toString(Charsets.UTF_8)) ?: throw IllegalArgumentException("not a Lichen identity export")
}

/**
 * Set up [dataDir] from an export: state files verbatim, keys written (without passphrase unless
 * [keyPassphrase]), marked linked. Returns the bundled lichen.yaml text, if any. Throws
 * IllegalStateException if the folder already has an identity, IllegalArgumentException for a bad
 * export or passphrase.
 */
fun importIdentity(text: String, dataDir: File, passphrase: String, keyPassphrase: String? = null): String? {
    check(!File(dataDir, "key").exists()) { "$dataDir already has an identity" }
    val bundle = openExport(text, passphrase)
    val keys = bundle["keys"] as? JsonObject ?: throw IllegalArgumentException("export has no keys")
    val secrets = KEY_FILES.associateWith { unb64(keys[it].asString() ?: throw IllegalArgumentException("export has no $it")) }
    dataDir.mkdirs()
    val files = bundle["files"] as? JsonObject
    for (name in STATE_FILES) files?.get(name).asString()?.let { File(dataDir, name).writeText(it, Charsets.UTF_8) }
    // Keys last, `key` the very last: its presence is what says "this folder has an identity".
    for (name in KEY_FILES.reversed()) writeSecret(File(dataDir, name), secrets.getValue(name), keyPassphrase)
    markLinked(dataDir)
    return bundle["config"].asString()
}
