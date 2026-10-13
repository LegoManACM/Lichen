package dev.lichen.core.store

import dev.lichen.core.CODE_POINT_ORDER
import dev.lichen.core.Canonical
import dev.lichen.core.asDouble
import dev.lichen.core.asString
import dev.lichen.core.dumps
import dev.lichen.core.obj
import dev.lichen.core.parseObject
import dev.lichen.core.stringArray
import dev.lichen.core.writeFileAtomic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

// Local history of direct messages, so conversations survive restarts. One copy per message id, each
// conversation ordered by timestamp. Messages are kept as the JsonObject they arrived as (signature and
// every signed field included) so they can be handed back in a chat sync and re-verified by the other
// side. Same dms.json as Python: {"convos": {peer: [msg]}, "receipts": {peer: {delivered, read_ts}}}.

const val HISTORY_LIMIT = 2000  // per conversation; oldest dropped first

private fun pyStr(e: JsonElement?): String = when {
    e == null || e is JsonNull -> "None"
    e is JsonPrimitive && !e.isString && e.content == "true" -> "True"
    e is JsonPrimitive && !e.isString && e.content == "false" -> "False"
    e is JsonPrimitive -> e.content
    else -> e.toString()
}

/** Stable id for messages stored before ids existed (same as dms.legacy_id). */
fun legacyId(m: JsonObject): String {
    val h = MessageDigest.getInstance("SHA-256").digest("${pyStr(m["from"])}|${pyStr(m["ts"])}|${pyStr(m["text"])}".toByteArray(Charsets.UTF_8))
    return "legacy-" + h.joinToString("") { "%02x".format(it) }.take(20)
}

private fun ts(m: JsonObject): Double = m["ts"].asDouble() ?: 0.0
private fun id(m: JsonObject): String = m["id"].asString() ?: ""

class DmStore(private val file: File? = null) {
    /** What we know about messages we sent to one peer: ids that reached them, and the newest read timestamp. */
    class Receipt(val delivered: MutableList<String> = mutableListOf(), var readTs: Double = 0.0)

    private val lock = Any()
    private val convos = LinkedHashMap<String, MutableList<JsonObject>>()  // peer pubkey -> messages, by ts
    private val ids = HashMap<String, MutableSet<String>>()
    private val receipts = LinkedHashMap<String, Receipt>()
    var onChange: (() -> Unit)? = null  // after any change (device sync hooks in here)

    init {
        if (file != null && file.exists()) {
            var data = parseObject(file.readText(Charsets.UTF_8)) ?: JsonObject(emptyMap())
            if ("convos" in data) {  // current format; older files were just {peer: [messages]}
                (data["receipts"] as? JsonObject)?.forEach { (peer, r) ->
                    val ro = r as? JsonObject ?: return@forEach
                    receipts[peer] = Receipt((ro["delivered"] as? JsonArray)?.mapNotNull { it.asString() }?.toMutableList() ?: mutableListOf(),
                        ro["read_ts"].asDouble() ?: 0.0)
                }
                data = data["convos"] as? JsonObject ?: JsonObject(emptyMap())
            }
            for ((peer, log) in data) {
                val msgs = (log as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?.map { m -> if ("id" in m) m else JsonObject(m + ("id" to JsonPrimitive(legacyId(m)))) } ?: continue
                convos[peer] = msgs.sortedBy { ts(it) }.toMutableList()  // stable, like Python's sorted
                ids[peer] = msgs.map { id(it) }.toMutableSet()
            }
        }
    }

    /** Store a message in the conversation with [peer]. False if we already had that id. */
    fun add(peer: String, message: JsonObject): Boolean {
        val mid = message["id"].asString() ?: throw IllegalArgumentException("message has no id")
        synchronized(lock) {
            val have = ids.getOrPut(peer) { mutableSetOf() }
            if (mid in have) return false
            addLocked(peer, message)
            save()
        }
        onChange?.invoke()
        return true
    }

    /** add() without any locking, saving or notifying; the caller does that. */
    private fun addLocked(peer: String, message: JsonObject) {
        val have = ids.getOrPut(peer) { mutableSetOf() }
        val log = convos.getOrPut(peer) { mutableListOf() }
        log.add(message)
        if (log.size > 1 && ts(log[log.size - 2]) > ts(message)) log.sortBy { ts(it) }  // arrived out of order
        have.add(id(message))
        if (log.size > HISTORY_LIMIT) {
            val drop = log.size - HISTORY_LIMIT
            for (i in 0 until drop) have.remove(id(log[i]))
            log.subList(0, drop).clear()
        }
    }

    fun has(peer: String, msgId: String): Boolean = synchronized(lock) { msgId in (ids[peer] ?: emptySet()) }

    fun ids(peer: String): List<String> = synchronized(lock) { convos[peer]?.map { id(it) } ?: emptyList() }

    /** The conversation with [peer], oldest first (a snapshot). */
    fun messages(peer: String): List<JsonObject> = synchronized(lock) { convos[peer]?.toList() ?: emptyList() }

    /** Peers we have a conversation with. */
    fun peers(): List<String> = synchronized(lock) { convos.filter { it.value.isNotEmpty() }.keys.toList() }

    /** Signed messages in this conversation that the other side ([have] = their ids) lacks, newest [limit]. */
    fun missingFor(peer: String, have: Set<String>, limit: Int = 500): List<JsonObject> = synchronized(lock) {
        (convos[peer] ?: emptyList<JsonObject>()).filter { id(it) !in have && "sig" in it }.takeLast(limit)
    }

    // --- receipts (for messages we sent) ------------------------------------------------------------

    fun markDelivered(peer: String, msgId: String) {
        val changed = synchronized(lock) {
            val r = receipts.getOrPut(peer) { Receipt() }
            if (msgId !in r.delivered) {
                r.delivered.add(msgId)
                while (r.delivered.size > HISTORY_LIMIT) r.delivered.removeAt(0)
                save(); true
            } else false
        }
        if (changed) onChange?.invoke()
    }

    /** They've read everything up to [uptoTs]. True if that's news. */
    fun markRead(peer: String, uptoTs: Double): Boolean {
        synchronized(lock) {
            val r = receipts.getOrPut(peer) { Receipt() }
            if (uptoTs <= r.readTs) return false
            r.readTs = uptoTs
            save()
        }
        onChange?.invoke()
        return true
    }

    /** "read", "delivered" or null (no news) for one of our messages to [peer]. */
    fun receipt(peer: String, message: JsonObject): String? = synchronized(lock) {
        val r = receipts[peer] ?: return null
        when {
            ts(message) <= r.readTs -> "read"
            id(message) in r.delivered -> "delivered"
            else -> null
        }
    }

    // --- syncing between your own devices -----------------------------------------------------------

    /**
     * Every conversation (the newest [keep] messages of each, if given) and our receipts, in a fixed
     * order, so two devices holding the same data produce the same bytes.
     */
    fun deviceState(keep: Int? = null): JsonObject = synchronized(lock) {
        fun newest(log: List<JsonObject>): List<JsonObject> {
            val s = log.sortedWith(compareBy<JsonObject> { ts(it) }.thenBy(CODE_POINT_ORDER) { id(it) })
            return if (keep != null && keep > 0) s.takeLast(keep) else s
        }
        obj {
            put("convos", JsonObject(convos.toSortedMap(CODE_POINT_ORDER).filter { it.value.isNotEmpty() }.mapValues { JsonArray(newest(it.value)) }))
            put("receipts", JsonObject(receipts.toSortedMap(CODE_POINT_ORDER).mapValues { (_, r) ->
                obj {
                    put("delivered", stringArray(r.delivered.sortedWith(CODE_POINT_ORDER)))
                    put("read_ts", if (r.readTs == 0.0) JsonPrimitive(0) else Canonical.pyFloat(r.readTs))
                }
            }))
        }
    }

    /**
     * Add what another device of ours has that we lack. The state arrives inside a message signed by our
     * own key, so it is as trustworthy as our own disk; we only check each message belongs to a
     * conversation between [me] and the peer. Returns how many messages were new. Doesn't call
     * [onChange] (a merge must not echo back to the device it came from).
     */
    fun mergeDeviceState(state: JsonElement?, me: String): Int {
        val st = state as? JsonObject ?: return 0
        var new = 0
        synchronized(lock) {
            (st["convos"] as? JsonObject)?.forEach { (peer, log) ->
                if (log !is JsonArray || peer == me) return@forEach
                for (m in log) {
                    val mo = m as? JsonObject ?: continue
                    val from = mo["from"].asString() ?: continue
                    val to = mo["to"].asString() ?: continue
                    val mid = mo["id"].asString() ?: continue
                    if (setOf(from, to) == setOf(me, peer) && mo["ts"].asDouble() != null && mid !in (ids[peer] ?: emptySet<String>())) {
                        addQuiet(peer, mo); new++
                    }
                }
            }
            var changed = false
            (st["receipts"] as? JsonObject)?.forEach { (peer, r) ->
                val ro = r as? JsonObject ?: return@forEach
                val mine = receipts.getOrPut(peer) { Receipt() }
                val extra = (ro["delivered"] as? JsonArray)?.mapNotNull { it.asString() }?.filter { it !in mine.delivered }?.distinct() ?: emptyList()
                if (extra.isNotEmpty()) {
                    mine.delivered.addAll(extra)
                    while (mine.delivered.size > HISTORY_LIMIT) mine.delivered.removeAt(0)
                    changed = true
                }
                val rt = ro["read_ts"].asDouble()
                if (rt != null && rt > mine.readTs) { mine.readTs = rt; changed = true }
            }
            if (new > 0 || changed) save()
        }
        return new
    }

    private fun addQuiet(peer: String, message: JsonObject) {
        val have = ids.getOrPut(peer) { mutableSetOf() }
        val log = convos.getOrPut(peer) { mutableListOf() }
        log.add(message)
        have.add(id(message))
        log.sortBy { ts(it) }
        if (log.size > HISTORY_LIMIT) {
            val drop = log.size - HISTORY_LIMIT
            for (i in 0 until drop) have.remove(id(log[i]))
            log.subList(0, drop).clear()
        }
    }

    fun save() = synchronized(lock) { saveLocked() }

    private fun saveLocked() {
        file?.let {
            writeFileAtomic(it, dumps(obj {
                put("convos", JsonObject(convos.mapValues { e -> JsonArray(e.value) }))
                put("receipts", JsonObject(receipts.mapValues { (_, r) ->
                    obj {
                        put("delivered", stringArray(r.delivered))
                        put("read_ts", if (r.readTs == 0.0) JsonPrimitive(0) else Canonical.pyFloat(r.readTs))
                    }
                }))
            }))
        }
    }
}
