package dev.lichen.core.store

import dev.lichen.core.Canonical
import dev.lichen.core.CODE_POINT_ORDER
import dev.lichen.core.asDouble
import dev.lichen.core.asString
import dev.lichen.core.b64
import dev.lichen.core.dumps
import dev.lichen.core.obj
import dev.lichen.core.parseObject
import dev.lichen.core.secureRandom
import dev.lichen.core.writeFileAtomic
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

// Things you sent that haven't reached their recipient yet (they were offline). Kept already sealed to
// the recipient and retried later; shared between your devices so whichever is on can deliver. Items
// that got through are remembered for a month so other devices drop their copy. Same outbox.json as Python.

private const val MAX_AGE = 30 * 24 * 3600.0  // give up after a month

class Outbox(private val file: File? = null) {
    /** One queued message. [msg] is the sealed, signed message to hand over; [ts] when it was queued. */
    data class Item(val to: String, val msg: JsonObject, val ts: Double) {
        fun toJson(): JsonObject = obj { put("to", kotlinx.serialization.json.JsonPrimitive(to)); put("msg", msg); put("ts", Canonical.pyFloat(ts)) }

        companion object {
            fun fromJson(e: JsonElement?): Item? {
                val o = e as? JsonObject ?: return null
                return Item(o["to"].asString() ?: return null, o["msg"] as? JsonObject ?: return null, o["ts"].asDouble() ?: return null)
            }
        }
    }

    private val lock = Any()
    private val items = LinkedHashMap<String, Item>()  // id -> item
    private val done = LinkedHashMap<String, Double>()  // id -> when it got through (or was refused)
    var onChange: (() -> Unit)? = null

    init {
        if (file != null && file.exists()) {
            val data = parseObject(file.readText(Charsets.UTF_8)) ?: JsonObject(emptyMap())
            val its = data["items"] as? JsonObject
            val dn = data["done"] as? JsonObject
            // Older files were just {id: item}.
            val itemSrc = if (its != null && dn != null) its else data
            itemSrc.forEach { (id, v) -> Item.fromJson(v)?.let { items[id] = it } }
            dn?.takeIf { its != null }?.forEach { (id, v) -> v.asDouble()?.let { done[id] = it } }
        }
        expire()
    }

    /**
     * Keep [msg] until it reaches [to]. The item id is what hubs record in their IOUs, so the recipient
     * can tell IOUs at several hubs are for the same thing. Returns the id.
     */
    fun add(to: String, msg: JsonObject, itemId: String? = null): String {
        val id = itemId ?: b64(ByteArray(9).also { secureRandom.nextBytes(it) })
        val added = synchronized(lock) {
            if (id !in items && id !in done) {
                items[id] = Item(to, msg, Canonical.nowSeconds())
                save(); true
            } else false
        }
        if (added) onChange?.invoke()
        return id
    }

    /** Queued (id, item) pairs, for one recipient or all. */
    fun forPeer(to: String? = null): List<Pair<String, Item>> =
        synchronized(lock) { items.filter { to == null || it.value.to == to }.map { it.key to it.value } }

    fun peers(): Set<String> = synchronized(lock) { items.values.map { it.to }.toSet() }

    fun size(): Int = synchronized(lock) { items.size }

    /** Is [itemId] still waiting to be delivered? */
    fun has(itemId: String): Boolean = synchronized(lock) { itemId in items }

    /** It got through (or was refused): drop it and remember that for a month. */
    fun remove(itemId: String) {
        val had = synchronized(lock) {
            if (items.remove(itemId) != null) { done[itemId] = Canonical.nowSeconds(); save(); true } else false
        }
        if (had) onChange?.invoke()
    }

    fun expire() {
        synchronized(lock) {
            val cutoff = Canonical.nowSeconds() - MAX_AGE
            val a = items.entries.removeIf { it.value.ts < cutoff }
            val b = done.entries.removeIf { it.value < cutoff }
            if (a || b) save()
        }
    }

    // --- syncing between your own devices -----------------------------------------------------------

    fun deviceState(): JsonObject = synchronized(lock) {
        obj {
            put("items", JsonObject(items.toSortedMap(CODE_POINT_ORDER).mapValues { it.value.toJson() }))
            put("done", JsonObject(done.toSortedMap(CODE_POINT_ORDER).mapValues { Canonical.pyFloat(it.value) }))
        }
    }

    /** Take on what our other devices still owe, drop what got through anywhere. Returns items added or dropped here. */
    fun mergeDeviceState(state: JsonElement?): Int {
        val st = state as? JsonObject ?: return 0
        var changed = 0
        var dirty = false
        synchronized(lock) {
            (st["done"] as? JsonObject)?.forEach { (id, v) ->
                val ts = v.asDouble() ?: return@forEach
                // Two devices may both have delivered it: keep the earlier time so they end up identical.
                if (ts < (done[id] ?: Double.POSITIVE_INFINITY)) { done[id] = ts; dirty = true }
                if (items.remove(id) != null) changed++
            }
            (st["items"] as? JsonObject)?.forEach { (id, v) ->
                val item = Item.fromJson(v) ?: return@forEach
                if (id !in items && id !in done) { items[id] = item; changed++ }
            }
            if (changed > 0 || dirty) save()
        }
        return changed
    }

    fun save() = synchronized(lock) { saveLocked() }

    private fun saveLocked() {
        file?.let {
            writeFileAtomic(it, dumps(obj {
                put("items", JsonObject(items.mapValues { e -> e.value.toJson() }))
                put("done", JsonObject(done.mapValues { e -> Canonical.pyFloat(e.value) }))
            }))
        }
    }
}
