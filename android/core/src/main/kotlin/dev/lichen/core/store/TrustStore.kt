package dev.lichen.core.store

import dev.lichen.core.Canonical
import dev.lichen.core.asDouble
import dev.lichen.core.asString
import dev.lichen.core.dumps
import dev.lichen.core.obj
import dev.lichen.core.parseObject
import dev.lichen.core.writeFileAtomic
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

// Who you accept things from, and what you call them. Every key is trusted, unknown (default) or blocked.
// Same trust.json as Python. Thread-safe; [onChange] runs after a local change (not after a device merge,
// so syncing doesn't echo back and forth).

val TRUST_LEVELS = listOf("trusted", "unknown", "blocked")

class TrustStore(private val file: File? = null) {
    private val lock = Any()
    private val levels = LinkedHashMap<String, String>()
    private val nicknames = LinkedHashMap<String, String>()
    private val verified = LinkedHashMap<String, Double>()  // key -> when you verified it
    private val changed = LinkedHashMap<String, Double>()   // key -> when anything about it last changed (device sync)
    var onChange: (() -> Unit)? = null

    init {
        if (file != null && file.exists()) {
            val data = parseObject(file.readText(Charsets.UTF_8)) ?: JsonObject(emptyMap())
            // Format from before nicknames was just {key: level}.
            val lv = if ("levels" in data) data["levels"] as? JsonObject else data
            lv?.forEach { (k, v) -> v.asString()?.takeIf { it in TRUST_LEVELS }?.let { levels[k] = it } }
            (data["nicknames"] as? JsonObject)?.forEach { (k, v) -> v.asString()?.let { nicknames[k] = it } }
            (data["verified"] as? JsonObject)?.forEach { (k, v) -> v.asDouble()?.let { verified[k] = it } }
            (data["changed"] as? JsonObject)?.forEach { (k, v) -> v.asDouble()?.let { changed[k] = it } }
        }
    }

    fun get(pubkey: String): String = synchronized(lock) { levels[pubkey] ?: "unknown" }

    fun set(pubkey: String, level: String) {
        require(level in TRUST_LEVELS) { "trust level must be one of ${TRUST_LEVELS.joinToString(", ")}" }
        touch(pubkey) { if (level == "unknown") levels.remove(pubkey) else levels[pubkey] = level }
    }

    fun trusted(pubkey: String): Boolean = get(pubkey) == "trusted"
    fun blocked(pubkey: String): Boolean = get(pubkey) == "blocked"

    /** Drop anything from keys that aren't trusted (Settings → strangers: ignore). Not saved here: settings.json has it. */
    @Volatile var ignoreStrangers = false

    /** Drop what [pubkey] sends us: blocked, or a stranger while we ignore strangers. */
    fun dropped(pubkey: String): Boolean = get(pubkey).let { it == "blocked" || (ignoreStrangers && it != "trusted") }

    fun nickname(pubkey: String): String? = synchronized(lock) { nicknames[pubkey] }

    /** All nicknames we've given, key -> nickname. */
    fun allNicknames(): Map<String, String> = synchronized(lock) { nicknames.toMap() }

    /** Set (or with null/empty clear) our nickname for a key. Throws IllegalArgumentException if invalid or taken. */
    fun setNickname(pubkey: String, nickname: String?) {
        touch(pubkey) {
            if (!nickname.isNullOrEmpty()) {
                require(nickname.none { it.isWhitespace() } && '#' !in nickname) { "nicknames can't contain spaces or #" }
                require(nicknames.none { (k, n) -> n.lowercase() == nickname.lowercase() && k != pubkey }) { "you already call someone else '$nickname'" }
                nicknames[pubkey] = nickname
            } else nicknames.remove(pubkey)
        }
    }

    fun isVerified(pubkey: String): Boolean = synchronized(lock) { pubkey in verified }

    /** Mark a key verified at [whenSeconds] (epoch seconds), or clear with null. */
    fun setVerified(pubkey: String, whenSeconds: Double?) {
        touch(pubkey) { if (whenSeconds != null && whenSeconds != 0.0) verified[pubkey] = whenSeconds else verified.remove(pubkey) }
    }

    /** Every key we've set a level for (trusted or blocked). */
    fun keys(level: String): List<String> = synchronized(lock) { levels.filter { it.value == level }.keys.toList() }

    private fun touch(pubkey: String, change: () -> Unit) {
        synchronized(lock) {
            change()  // may throw before anything is recorded
            changed[pubkey] = Canonical.nowSeconds()
            save()
        }
        onChange?.invoke()
    }

    // --- syncing between your own devices -----------------------------------------------------------

    fun deviceState(): JsonObject = synchronized(lock) {
        val keys = (levels.keys + nicknames.keys + verified.keys + changed.keys)
        JsonObject(keys.associateWith { k ->
            obj {
                put("level", JsonPrimitive(levels[k] ?: "unknown"))
                put("nickname", nicknames[k]?.let { JsonPrimitive(it) } ?: JsonNull)
                put("verified", verified[k]?.let { Canonical.pyFloat(it) } ?: JsonNull)
                put("changed", Canonical.pyFloat(changed[k] ?: 0.0))
            }
        })
    }

    /** Adopt another device's view of each key it changed more recently. Returns how many. */
    fun mergeDeviceState(state: JsonElement?): Int {
        val st = state as? JsonObject ?: return 0
        var n = 0
        synchronized(lock) {
            for ((k, el) in st) {
                val e = el as? JsonObject ?: continue
                val ch = e["changed"].asDouble() ?: continue
                val level = e["level"].asString()
                if (ch <= (changed[k] ?: 0.0) || level == null || level !in TRUST_LEVELS) continue
                if (level == "unknown") levels.remove(k) else levels[k] = level
                e["nickname"].asString()?.takeIf { it.isNotEmpty() }?.let { nicknames[k] = it } ?: nicknames.remove(k)
                e["verified"].asDouble()?.takeIf { it != 0.0 }?.let { verified[k] = it } ?: verified.remove(k)
                changed[k] = ch
                n++
            }
            if (n > 0) save()
        }
        return n
    }

    fun save() = synchronized(lock) { saveLocked() }

    private fun saveLocked() {
        file?.let {
            writeFileAtomic(it, dumps(obj {
                put("levels", JsonObject(levels.mapValues { e -> JsonPrimitive(e.value) }))
                put("nicknames", JsonObject(nicknames.mapValues { e -> JsonPrimitive(e.value) }))
                put("verified", JsonObject(verified.mapValues { e -> Canonical.pyFloat(e.value) }))
                put("changed", JsonObject(changed.mapValues { e -> Canonical.pyFloat(e.value) }))
            }))
        }
    }
}
