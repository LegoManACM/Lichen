package dev.lichen.core

import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.LichenSetup
import dev.lichen.core.api.Settings
import dev.lichen.core.api.SetupResult
import dev.lichen.core.api.Strangers
import dev.lichen.core.store.Phonebook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Data-folder setup on the file system: same file names/formats as the Python node's data folder, plus
 * settings.json for what Python keeps in lichen.yaml. Key files get no passphrase here (app-private storage).
 */
open class FileSetup(private val logSink: (String) -> Unit = {}) : LichenSetup {
    override fun hasIdentity(dataDir: File): Boolean = File(dataDir, "key").exists()

    override fun settings(dataDir: File): Settings? {
        val f = File(dataDir, "settings.json")
        val o = (if (f.exists()) parseObject(f.readText(Charsets.UTF_8)) else null) ?: return null
        val strangers = Strangers.entries.firstOrNull { it.name.lowercase() == o["strangers"].asString() } ?: Strangers.REQUESTS
        return Settings(o["name"].asString() ?: return null, (o["hubs"] as? JsonArray)?.mapNotNull { it.asString() } ?: emptyList(), strangers)
    }

    override fun saveSettings(dataDir: File, settings: Settings) {
        writeFileAtomic(File(dataDir, "settings.json"), dumps(obj {
            put("name", JsonPrimitive(settings.name))
            put("hubs", stringArray(settings.hubs))
            if (settings.strangers != Strangers.REQUESTS) put("strangers", JsonPrimitive(settings.strangers.name.lowercase()))
        }))
    }

    override fun create(dataDir: File, settings: Settings) {
        check(!hasIdentity(dataDir)) { "$dataDir already has an identity" }
        dataDir.mkdirs()
        // enckey first: `key` existing is what says "this folder has an identity".
        EncKey.loadOrCreate(File(dataDir, "enckey"))
        Identity.loadOrCreate(File(dataDir, "key"))
        deviceId(dataDir)
        saveSettings(dataDir, settings)
    }

    override fun import(dataDir: File, exportText: String, passphrase: String): SetupResult {
        return try {
            val yaml = importIdentity(exportText, dataDir, passphrase)
            deviceId(dataDir)
            val cfg = yaml?.let { parseLichenYaml(it) }
            val pubkey = Identity.fromSeed(readSecret(File(dataDir, "key").readText())).pubkey
            // Exports from older versions carry no lichen.yaml: fall back on our own phonebook record.
            val mine = Phonebook(File(dataDir, "phonebook.json")).get(pubkey)
            val name = cfg?.name?.takeIf { it.isNotEmpty() } ?: mine?.name ?: short(pubkey)
            val hubs = cfg?.relay?.takeIf { it.isNotEmpty() }
                ?: mine?.addrs?.filter { it.startsWith("relay:") }?.map { it.removePrefix("relay:") } ?: emptyList()
            Settings(name, hubs).also { saveSettings(dataDir, it) }.let { SetupResult.Ok(it) }
        } catch (e: IllegalArgumentException) {
            SetupResult.Failed(e.message ?: "not a Lichen identity export")
        } catch (e: IllegalStateException) {
            SetupResult.Failed(e.message ?: "this phone already has an identity")
        }
    }

    override fun open(dataDir: File): ChatClient = dev.lichen.core.node.NodeClient(dataDir, logSink)
}

/** The two options we need from a lichen.yaml. */
data class LichenYaml(val name: String?, val relay: List<String>)

/**
 * A deliberately tiny reader for `name:` and `relay:` (a scalar, a `[a, b]` flow list or a `- item` block
 * list) in a lichen.yaml; everything else, comments included, is ignored. No YAML library on purpose.
 */
fun parseLichenYaml(text: String): LichenYaml {
    var name: String? = null
    val relay = mutableListOf<String>()
    var inRelay = false
    for (raw in text.lines()) {
        val line = stripComment(raw).trimEnd()
        if (line.isBlank()) continue
        val trimmed = line.trimStart()
        if (inRelay && trimmed.startsWith("-") && (trimmed.length == 1 || trimmed[1].isWhitespace())) {
            unquote(trimmed.substring(1).trim()).takeIf { it.isNotEmpty() }?.let { relay.add(it) }
            continue
        }
        val indented = line[0].isWhitespace()
        if (indented) continue  // a nested value of something else
        inRelay = false
        val colon = line.indexOf(':')
        if (colon < 0) continue
        val key = line.substring(0, colon).trim().trim('"', '\'')
        val value = line.substring(colon + 1).trim()
        when (key) {
            "name" -> name = unquote(value).takeIf { it.isNotEmpty() }
            "relay" -> when {
                value.isEmpty() -> inRelay = true
                value.startsWith("[") -> relay.addAll(value.removePrefix("[").substringBeforeLast("]").split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() })
                else -> unquote(value).takeIf { it.isNotEmpty() }?.let { relay.add(it) }
            }
        }
    }
    return LichenYaml(name, relay)
}

/** Cut a trailing `# comment` (a # at the start or after whitespace, outside quotes). */
private fun stripComment(s: String): String {
    var quote: Char? = null
    for (i in s.indices) {
        val c = s[i]
        when {
            quote != null -> if (c == quote) quote = null
            c == '"' || c == '\'' -> quote = c
            c == '#' && (i == 0 || s[i - 1].isWhitespace()) -> return s.substring(0, i)
        }
    }
    return s
}

private fun unquote(s: String): String =
    if (s.length >= 2 && (s[0] == '"' || s[0] == '\'') && s.last() == s[0]) s.substring(1, s.length - 1) else s
