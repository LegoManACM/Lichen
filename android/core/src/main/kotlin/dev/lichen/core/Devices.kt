package dev.lichen.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * Everything another device needs to be this identity, encrypted with [passphrase]: the text of a
 * `lichen --export` file (same format as devices.py), so `lichen --import` and this app's import both read it.
 * [config] is a lichen.yaml to travel with it. The caller marks the folder linked once the file is saved.
 */
fun exportIdentity(dataDir: File, passphrase: String, config: String? = null, keyPassphrase: String? = null): String {
    require(passphrase.isNotEmpty()) { "an export needs a passphrase: anyone with the file and passphrase can be you" }
    require(File(dataDir, "key").exists()) { "no identity in $dataDir" }
    val bundle = obj {
        put("keys", obj { for (name in KEY_FILES) put(name, JsonPrimitive(b64(readSecret(File(dataDir, name).readText(), keyPassphrase)))) })
        put("files", obj {
            for (name in STATE_FILES) File(dataDir, name).takeIf { it.exists() }?.let { put(name, JsonPrimitive(it.readText(Charsets.UTF_8))) }
        })
        if (!config.isNullOrEmpty()) put("config", JsonPrimitive(config))
    }
    val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
    val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
    val ct = chachaEncrypt(kdf(passphrase, salt), nonce, dumps(bundle).toByteArray(Charsets.UTF_8), "lichen-identity".toByteArray())
    return EXPORT_PREFIX + dumps(obj {
        put("salt", JsonPrimitive(b64(salt))); put("n", JsonPrimitive(b64(nonce))); put("ct", JsonPrimitive(b64(ct)))
    })
}

/** A lichen.yaml holding just [name] and [hubs] (`relay:`), for an export. */
fun lichenYaml(name: String, hubs: List<String>): String = buildString {
    append("name: ").append(yamlString(name)).append('\n')
    if (hubs.isNotEmpty()) {
        append("relay:\n")
        for (h in hubs) append("  - ").append(yamlString(h)).append('\n')
    }
}

/** A YAML scalar that both PyYAML and [parseLichenYaml] read back as [s]. */
private fun yamlString(s: String): String = when {
    '\'' !in s -> "'$s'"
    '"' !in s && '\\' !in s -> "\"$s\""
    else -> "'" + s.replace("'", "") + "'"
}
