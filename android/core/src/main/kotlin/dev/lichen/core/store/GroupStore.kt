package dev.lichen.core.store

import dev.lichen.core.Canonical
import dev.lichen.core.CODE_POINT_ORDER
import dev.lichen.core.EncKey
import dev.lichen.core.Identity
import dev.lichen.core.asBool
import dev.lichen.core.asDouble
import dev.lichen.core.asLong
import dev.lichen.core.asString
import dev.lichen.core.b64
import dev.lichen.core.decrypt
import dev.lichen.core.dumps
import dev.lichen.core.encrypt
import dev.lichen.core.obj
import dev.lichen.core.openSealed
import dev.lichen.core.parseObject
import dev.lichen.core.seal
import dev.lichen.core.secureRandom
import dev.lichen.core.unb64
import dev.lichen.core.verify
import dev.lichen.core.writeFileAtomic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

// Group chats, end-to-end encrypted (groups.py). Same groups.json as Python; see groups.py for the model:
// a group record is a member list signed by its owner, every change starts a key epoch whose key is sealed
// to each member in the record's keybox, messages are signed by their author and encrypted with the epoch
// key, and peers store/sync the ciphertext ("outer") whose id is a hash of it.

const val GROUP_FORMAT_VERSION = 2  // groups.json from before encryption is ignored
val HISTORY_MODES = listOf("private", "shared")  // private: new members only see messages from after they joined
const val GROUP_SYNC_LIMIT = 1000  // most recent messages a peer will hand over in one sync
val GROUP_STATUSES = setOf("pending", "accepted", "declined", "left", "removed", "deleted")

private fun str(s: String) = JsonPrimitive(s)

fun groupIdFor(owner: String, nonce: String): String =
    b64(MessageDigest.getInstance("SHA-256").digest("$owner:$nonce".toByteArray(Charsets.UTF_8))).take(22)

/** What an owner signs to hand [groupId] to [to] (index = position in the ownership chain). */
fun transferBody(groupId: String, to: String, index: Int): ByteArray = Canonical.encode(obj {
    put("type", str("group_transfer")); put("group", str(groupId)); put("to", str(to)); put("n", JsonPrimitive(index))
})

private fun aad(groupId: String, epoch: Int): ByteArray =
    Canonical.encode(obj { put("group", str(groupId)); put("epoch", JsonPrimitive(epoch)) })

private fun msgId(ct: String): String = b64(MessageDigest.getInstance("SHA-256").digest(ct.toByteArray(Charsets.UTF_8))).take(22)

private fun text(e: JsonElement?): String = (e as JsonPrimitive).takeIf { it !is JsonNull }!!.content

class GroupRecord(
    val groupId: String,
    val nonce: String,
    val name: String,
    val owner: String,
    val members: List<String>,
    val seq: Long,
    val settings: JsonObject,
    /** member pubkey -> when the owner (last) added them, owner's clock */
    val joined: Map<String, Double>,
    val epoch: Int,
    /** member pubkey -> sealed {epoch: key} */
    val keybox: Map<String, JsonObject>,
    var sig: String = "",
    /** Ownership can be handed over; the id stays hash(founder, nonce). Only present in the signed body once there are transfers. */
    val founder: String? = null,
    val transfers: List<JsonObject> = emptyList(),
) {
    fun body(): JsonObject = obj {
        put("group_id", str(groupId)); put("nonce", str(nonce)); put("name", str(name)); put("owner", str(owner))
        put("members", JsonArray(members.map { str(it) })); put("seq", JsonPrimitive(seq)); put("settings", settings)
        put("joined", JsonObject(joined.mapValues { Canonical.pyFloat(it.value) }))
        put("epoch", JsonPrimitive(epoch)); put("keybox", JsonObject(keybox))
        if (transfers.isNotEmpty()) {
            put("founder", founder?.let { str(it) } ?: JsonNull)
            put("transfers", JsonArray(transfers))
        }
    }

    val history: String get() = settings["history"].asString() ?: "private"
    val deleted: Boolean get() = settings["deleted"].asBool() == true
    val founderKey: String get() = founder ?: owner

    fun joinedAt(pubkey: String): Double = joined[pubkey] ?: Double.POSITIVE_INFINITY

    /** Each transfer is signed by the owner before it, starting from the founder, ending at owner. */
    fun chainOk(): Boolean {
        var prev = founderKey
        for ((i, t) in transfers.withIndex()) {
            val to = t["to"].let { if (it == null || it is JsonNull) "None" else (it as? JsonPrimitive)?.content ?: it.toString() }
            if (!verify(prev, transferBody(groupId, to, i), t["sig"].let { (it as? JsonPrimitive)?.content ?: "None" })) return false
            prev = to
        }
        return prev == owner
    }

    fun valid(): Boolean = groupId == groupIdFor(founderKey, nonce) && chainOk() && verify(owner, Canonical.encode(body()), sig)

    fun toJson(): JsonObject = JsonObject(body() + ("sig" to str(sig)))

    companion object {
        /** Null if malformed or the signature/id/ownership chain doesn't check out. */
        fun fromJson(e: JsonElement?): GroupRecord? {
            val d = e as? JsonObject ?: return null
            val rec = try {
                GroupRecord(
                    text(d["group_id"]), text(d["nonce"]), text(d["name"]), text(d["owner"]),
                    (d["members"] as JsonArray).map { text(it) }, d["seq"].asLong()!!, d["settings"] as JsonObject,
                    (d["joined"] as JsonObject).mapValuesTo(LinkedHashMap()) { text(it.value).toDouble() },
                    d["epoch"].asLong()!!.toInt(),
                    (d["keybox"] as JsonObject).mapValuesTo(LinkedHashMap()) { it.value as JsonObject },
                    text(d["sig"]),
                    d["founder"].asString()?.takeIf { it.isNotEmpty() },
                    (d["transfers"] as? JsonArray)?.map { it as JsonObject } ?: emptyList(),
                )
            } catch (ex: Exception) {
                return null
            }
            return rec.takeIf { it.valid() }
        }
    }
}

/** What we know about a key epoch of a group we host (or hosted): the history mode it started with, and when. */
data class EpochMeta(val mode: String, val start: Double) {
    fun toJson(): JsonObject = obj { put("mode", str(mode)); put("start", Canonical.pyFloat(start)) }
}

private fun epochMeta(e: JsonElement?): EpochMeta? {
    val o = e as? JsonObject ?: return null
    return EpochMeta(o["mode"].asString() ?: return null, o["start"].asDouble() ?: return null)
}

private class Entry(val outer: JsonObject, val inner: JsonObject) {
    fun toJson() = obj { put("outer", outer); put("inner", inner) }
}

class GroupStore(private val ident: Identity, private val enc: EncKey, private val file: File? = null) {
    private val lock = Any()
    private val groups = LinkedHashMap<String, GroupRecord>()
    private val keys = HashMap<String, MutableMap<Int, ByteArray>>()        // group -> epoch -> key (whatever we're entitled to)
    private val epochs = HashMap<String, MutableMap<Int, EpochMeta>>()      // owner only: group -> epoch -> {mode, start}
    /** Your membership: "pending" (invited), "accepted", "declined", "left", "removed" or "deleted". */
    private val status = HashMap<String, String>()
    private val statusTs = HashMap<String, Double>()                         // when our status last changed (newest wins across devices)
    private val logs = HashMap<String, LinkedHashMap<String, Entry>>()       // group -> msg id -> entry
    /** Called after anything changes (device sync hooks in here). */
    var onChange: (() -> Unit)? = null
    /** Called once after [mergeDeviceState] changed something (onChange is not: a merge is not news for our other devices). */
    var onRemoteChange: (() -> Unit)? = null
    private val merging = ThreadLocal<Boolean>()

    init {
        if (file != null && file.exists()) {
            val data = parseObject(file.readText(Charsets.UTF_8))
            if (data != null && data["version"].asLong() == GROUP_FORMAT_VERSION.toLong()) {
                (data["groups"] as? JsonObject)?.forEach { (gid, g) ->
                    try {
                        g as JsonObject
                        val rec = GroupRecord.fromJson(g["record"]) ?: return@forEach
                        groups[gid] = rec
                        keys[gid] = (g["keys"] as JsonObject).entries.associateTo(HashMap()) { it.key.toInt() to unb64(text(it.value)) }
                        epochs[gid] = ((g["epochs"] as? JsonObject) ?: JsonObject(emptyMap())).entries
                            .associateTo(HashMap()) { it.key.toInt() to epochMeta(it.value)!! }
                        val log = LinkedHashMap<String, Entry>()
                        (g["log"] as JsonArray).forEach { m ->
                            m as JsonObject
                            val outer = m["outer"] as JsonObject
                            log[outer["id"].asString()!!] = Entry(outer, m["inner"] as JsonObject)
                        }
                        logs[gid] = log
                        status[gid] = g["status"].asString() ?: "accepted"  // groups from before invites: joined
                        statusTs[gid] = g["status_ts"].asDouble() ?: 0.0
                    } catch (ex: Exception) {
                        groups.remove(gid)
                    }
                }
            }
        }
    }

    // --- records and keys ------------------------------------------------------------------------

    /** A current member who has accepted (owners accept their own groups). */
    fun joined(groupId: String): Boolean = synchronized(lock) {
        val rec = groups[groupId]
        rec != null && ident.pubkey in rec.members && status[groupId] == "accepted"
    }

    fun status(groupId: String): String? = synchronized(lock) { status[groupId] }

    fun statusTs(groupId: String): Double = synchronized(lock) { statusTs[groupId] ?: 0.0 }

    fun setStatus(groupId: String, status: String, ts: Double? = null) {
        synchronized(lock) {
            this.status[groupId] = status
            statusTs[groupId] = ts ?: Canonical.nowSeconds()
            save()
        }
        changed()
    }

    private fun changed() { if (merging.get() != true) onChange?.invoke() }

    /** Store [rec] if valid and newer, and pick up any keys it carries for us. */
    fun merge(rec: GroupRecord?): Boolean {
        if (rec == null || !rec.valid()) return false
        synchronized(lock) {
            val cur = groups[rec.groupId]
            if (cur != null && cur.seq >= rec.seq) return false
            groups[rec.groupId] = rec
            logs.getOrPut(rec.groupId) { LinkedHashMap() }
            val ks = keys.getOrPut(rec.groupId) { HashMap() }
            val box = rec.keybox[ident.pubkey]
            val opened = box?.let { openSealed(enc, it) }
            if (opened != null) {
                (parseObject(opened.toString(Charsets.UTF_8)))?.forEach { (e, k) -> ks[e.toInt()] = unb64(text(k)) }
            }
            save()
        }
        changed()
        return true
    }

    /** Forget a group's messages and keys (the record stays, so later updates are recognised). */
    fun purge(groupId: String, status: String = "deleted") {
        synchronized(lock) {
            logs[groupId] = LinkedHashMap()
            keys[groupId] = HashMap()
            epochs[groupId] = HashMap()
        }
        setStatus(groupId, status)
    }

    /**
     * Owner side: the next version of a group (or a new one), with a fresh epoch key sealed to every
     * member. [encKeys] maps each other member's pubkey to their X25519 key. [transfer] is the previous
     * owner's signed hand-over when we're taking the group over.
     */
    fun buildRecord(name: String, members: List<String>, encKeys: Map<String, String>, prev: GroupRecord?,
                    settings: JsonObject, transfer: JsonObject? = null): GroupRecord = synchronized(lock) {
        val now = Canonical.nowSeconds()
        val me = ident.pubkey
        val nonce = prev?.nonce ?: b64(ByteArray(12).also { secureRandom.nextBytes(it) })
        val founder = prev?.founderKey ?: me
        val transfers = (prev?.transfers ?: emptyList()) + listOfNotNull(transfer)
        val gid = groupIdFor(founder, nonce)
        val memberList = (members.toSet() + me).sortedWith(CODE_POINT_ORDER)
        val joined = LinkedHashMap<String, Double>()
        for (m in memberList) joined[m] = prev?.joined?.get(m) ?: now
        val epoch = (prev?.epoch ?: 0) + 1

        val ks = keys.getOrPut(gid) { HashMap() }
        val eps = epochs.getOrPut(gid) { HashMap() }
        val history = settings["history"].asString() ?: "private"
        ks[epoch] = ByteArray(32).also { secureRandom.nextBytes(it) }
        eps[epoch] = EpochMeta(history, now)
        val keybox = LinkedHashMap<String, JsonObject>()
        for (m in memberList) {
            if (m == me) continue
            // Epochs we hold a key for but no metadata (shouldn't happen after a proper hand-over) are
            // shared with nobody: better to withhold history than to leak it.
            val entitled = ks.filter { (e, _) ->
                val meta = eps[e]
                meta != null && (meta.start >= joined.getValue(m) || (history == "shared" && meta.mode == "shared"))
            }
            val payload = JsonObject(entitled.entries.associate { it.key.toString() to str(b64(it.value)) })
            keybox[m] = seal(encKeys.getValue(m), dumps(payload).toByteArray(Charsets.UTF_8))
        }
        val seq = maxOf(System.currentTimeMillis() * 1000, (prev?.seq ?: 0) + 1)
        val rec = GroupRecord(gid, nonce, name, me, memberList, seq, settings, joined, epoch, keybox,
            founder = if (transfers.isNotEmpty()) founder else null, transfers = transfers)
        rec.sig = ident.sign(Canonical.encode(rec.body()))
        rec
    }

    // --- messages --------------------------------------------------------------------------------

    /** Sign and encrypt a message under the group's current epoch. Returns the outer envelope. [extra] carries replies / reactions / edits. */
    fun sealMessage(groupId: String, text: String, extra: Map<String, JsonElement> = emptyMap()): JsonObject = synchronized(lock) {
        val rec = groups.getValue(groupId)
        val unsigned = JsonObject(mapOf(
            "group" to str(groupId), "from" to str(ident.pubkey), "ts" to Canonical.now(),
            "nonce" to str(b64(ByteArray(8).also { secureRandom.nextBytes(it) })), "text" to str(text),
            "private" to JsonPrimitive(rec.history == "private"),
        ) + extra)
        val inner = JsonObject(unsigned + ("sig" to str(ident.sign(Canonical.encode(unsigned)))))
        val box = encrypt(keys.getValue(groupId).getValue(rec.epoch), Canonical.encode(inner), aad(groupId, rec.epoch))
        JsonObject(mapOf("group" to str(groupId), "epoch" to JsonPrimitive(rec.epoch)) + box + ("id" to str(msgId(box["ct"].asString()!!))))
    }

    /** Decrypt, verify and store [outer]. Returns the inner message if it was new and valid. */
    fun addMessage(outer: JsonObject): JsonObject? {
        val gid: String; val epoch: Int; val ct: String
        try {
            gid = text(outer["group"]); epoch = outer["epoch"].asLong()!!.toInt(); ct = text(outer["ct"])
        } catch (e: Exception) {
            return null
        }
        val inner: JsonObject
        synchronized(lock) {
            val rec = groups[gid]
            val key = keys[gid]?.get(epoch)
            val id = outer["id"].asString()
            if (rec == null || key == null || id != msgId(ct) || id in (logs[gid] ?: emptyMap())) return null
            val plain = decrypt(key, outer, aad(gid, epoch)) ?: return null
            val parsed = try {
                val i = parseObject(plain.toString(Charsets.UTF_8))!!
                val body = JsonObject(i.filterKeys { it != "sig" })
                val ok = i["group"].asString() == gid && i["from"].asString() in rec.members && i["ts"].asDouble() != null &&
                    verify(i["from"].asString()!!, Canonical.encode(body), i["sig"].asString()!!)
                if (ok) i else null
            } catch (e: Exception) {
                null
            } ?: return null
            inner = parsed
            val cleanOuter = obj {
                put("group", str(gid)); put("epoch", JsonPrimitive(epoch)); put("n", outer["n"]!!); put("ct", str(ct)); put("id", str(id!!))
            }
            logs.getOrPut(gid) { LinkedHashMap() }[id] = Entry(cleanOuter, inner)
            save()
        }
        changed()
        return inner
    }

    fun has(groupId: String, msgId: String): Boolean = synchronized(lock) { msgId in (logs[groupId] ?: emptyMap()) }

    fun logIds(groupId: String): List<String> = synchronized(lock) { logs[groupId]?.keys?.toList() ?: emptyList() }

    /** Decrypted messages, oldest first, each with its message id. */
    fun messages(groupId: String): List<JsonObject> = synchronized(lock) {
        (logs[groupId]?.values ?: emptyList<Entry>())
            .map { JsonObject(it.inner + ("id" to it.outer["id"]!!)) }
            .sortedBy { it["ts"].asDouble() ?: 0.0 }
    }

    /**
     * Ciphertexts [requester] lacks. Private-era messages from before they joined are skipped: they
     * couldn't decrypt them anyway, and this avoids handing out even the ciphertext.
     */
    fun missingFor(groupId: String, have: Set<String>, requester: String): List<JsonObject> = synchronized(lock) {
        val since = groups[groupId]?.joinedAt(requester) ?: Double.POSITIVE_INFINITY
        val entries = (logs[groupId]?.values ?: emptyList<Entry>()).sortedBy { it.inner["ts"].asDouble() ?: 0.0 }
        val visible = entries.filter { it.outer["id"].asString() !in have &&
            (it.inner["private"].asBool() != true || (it.inner["ts"].asDouble() ?: 0.0) >= since) }.map { it.outer }
        visible.takeLast(GROUP_SYNC_LIMIT)
    }

    // --- key and epoch bookkeeping (ownership hand-over) -----------------------------------------

    /** Keys we hold for [groupId], by epoch. */
    fun keysOf(groupId: String): Map<Int, ByteArray> = synchronized(lock) { keys[groupId]?.toMap() ?: emptyMap() }

    fun epochsOf(groupId: String): Map<Int, EpochMeta> = synchronized(lock) { epochs[groupId]?.toMap() ?: emptyMap() }

    /** Adopt a key and its metadata (a hand-over from the previous owner). */
    fun putEpoch(groupId: String, epoch: Int, key: ByteArray, meta: EpochMeta) {
        synchronized(lock) {
            keys.getOrPut(groupId) { HashMap() }[epoch] = key
            epochs.getOrPut(groupId) { HashMap() }[epoch] = meta
            save()
        }
    }

    // --- lookup / persistence --------------------------------------------------------------------

    fun get(groupId: String): GroupRecord? = synchronized(lock) { groups[groupId] }

    fun find(query: String): List<GroupRecord> = synchronized(lock) { groups.values.filter { it.name == query || it.groupId.startsWith(query) } }

    fun all(): List<GroupRecord> = synchronized(lock) { groups.values.toList() }

    fun save() {
        if (file == null) return
        synchronized(lock) {
            val out = LinkedHashMap<String, JsonElement>()
            for ((gid, rec) in groups) {
                out[gid] = obj {
                    put("record", rec.toJson())
                    put("keys", JsonObject((keys[gid] ?: emptyMap()).entries.associate { it.key.toString() to str(b64(it.value)) }))
                    put("epochs", JsonObject((epochs[gid] ?: emptyMap()).entries.associate { it.key.toString() to it.value.toJson() }))
                    put("log", JsonArray((logs[gid]?.values ?: emptyList<Entry>()).map { it.toJson() }))
                    put("status", str(status[gid] ?: "pending")); put("status_ts", Canonical.pyFloat(statusTs[gid] ?: 0.0))
                }
            }
            writeFileAtomic(file, dumps(obj { put("version", JsonPrimitive(GROUP_FORMAT_VERSION)); put("groups", JsonObject(out)) }))
        }
    }

    // --- syncing between your own devices -----------------------------------------------------------

    /**
     * Everything another device of ours needs: records, keys, epoch history (if we host), our status
     * with its timestamp, and the message log (the newest [keep] of each group, if given).
     */
    fun deviceState(keep: Int? = null): JsonObject = synchronized(lock) {
        JsonObject(groups.entries.sortedWith { a, b -> CODE_POINT_ORDER.compare(a.key, b.key) }.associate { (gid, rec) ->
            val entries = (logs[gid]?.values ?: emptyList<Entry>()).sortedWith(
                compareBy<Entry> { it.inner["ts"].asDouble() ?: 0.0 }.thenBy { it.outer["id"].asString() ?: "" })
            val picked = if (keep != null && keep > 0) entries.takeLast(keep) else entries
            gid to obj {
                put("record", rec.toJson())
                put("keys", JsonObject((keys[gid] ?: emptyMap()).entries.associate { it.key.toString() to str(b64(it.value)) }))
                put("epochs", JsonObject((epochs[gid] ?: emptyMap()).entries.associate { it.key.toString() to it.value.toJson() }))
                put("status", str(status[gid] ?: "pending")); put("status_ts", Canonical.pyFloat(statusTs[gid] ?: 0.0))
                put("log", JsonArray(picked.map { it.outer }))
            }
        })
    }

    /** Merge another device's [deviceState]. Returns the ids of groups that changed for us. */
    fun mergeDeviceState(state: JsonElement?): List<String> {
        val changed = ArrayList<String>()
        val st = state as? JsonObject ?: return changed
        merging.set(true)
        try { mergeAll(st, changed) } finally { merging.set(false) }
        if (changed.isNotEmpty()) onRemoteChange?.invoke()
        return changed
    }

    private fun mergeAll(st: JsonObject, changed: MutableList<String>) {
        for ((gid, g) in st) {
            try {
                g as JsonObject
                val rec = GroupRecord.fromJson(g["record"])
                if (rec == null || rec.groupId != gid) continue
                fun fingerprint() = synchronized(lock) { Triple(groups[gid]?.seq, status[gid], logs[gid]?.size ?: 0) }
                val before = fingerprint()
                merge(rec)
                synchronized(lock) { logs.getOrPut(gid) { LinkedHashMap() } }
                val ts = g["status_ts"].asDouble() ?: 0.0
                val s = g["status"].asString()
                synchronized(lock) {
                    if (s != null && s in GROUP_STATUSES && (gid !in status || ts > (statusTs[gid] ?: 0.0))) {
                        status[gid] = s; statusTs[gid] = ts
                        if (s == "deleted") { logs[gid] = LinkedHashMap(); keys[gid] = HashMap(); epochs[gid] = HashMap() }
                    }
                }
                if (status(gid) == "deleted") continue  // gone for good: don't let a device that hasn't heard bring the keys back
                synchronized(lock) {
                    val ks = keys.getOrPut(gid) { HashMap() }
                    (g["keys"] as? JsonObject)?.forEach { (e, k) -> ks.putIfAbsent(e.toInt(), unb64(text(k))) }
                    val eps = epochs.getOrPut(gid) { HashMap() }
                    (g["epochs"] as? JsonObject)?.forEach { (e, m) -> epochMeta(m)?.let { eps.putIfAbsent(e.toInt(), it) } }
                }
                (g["log"] as? JsonArray)?.forEach { outer -> if (outer is JsonObject) addMessage(outer) }
                if (fingerprint() != before) changed.add(gid)
            } catch (e: Exception) {
                continue
            }
        }
        if (changed.isNotEmpty()) save()
    }
}
