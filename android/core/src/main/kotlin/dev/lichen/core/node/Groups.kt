package dev.lichen.core.node

import dev.lichen.core.Canonical
import dev.lichen.core.Identity
import dev.lichen.core.Record
import dev.lichen.core.asDouble
import dev.lichen.core.asBool
import dev.lichen.core.asString
import dev.lichen.core.b64
import dev.lichen.core.obj
import dev.lichen.core.short
import dev.lichen.core.store.EpochMeta
import dev.lichen.core.store.GroupRecord
import dev.lichen.core.store.GroupStore
import dev.lichen.core.store.HISTORY_MODES
import dev.lichen.core.store.Phonebook
import dev.lichen.core.store.TrustStore
import dev.lichen.core.store.transferBody
import dev.lichen.core.stringArray
import dev.lichen.core.unb64
import dev.lichen.core.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

// The node side of group chats (the groups section of node.py): creating and managing groups, sending,
// catching up (group_sync), and handling group_update / group_leave / group_msg / group_sync / group_handoff.
// The crypto and storage live in store/GroupStore.kt. Node wires one of these up and dispatches to [handle].

const val LEAVE_MAX_AGE = 7 * 24 * 3600.0  // a leave request may wait in our outbox while the owner is away

/** What the node core gives [Groups] to work with (all of it is Node's own machinery). */
class GroupsHost(
    /** Deliver something worth delivering later: "delivered", "waiting" (outbox + IOU) or null (refused). */
    val deliver: suspend (pubkey: String, msg: JsonObject, itemId: String) -> String?,
    val requestPeer: suspend (pubkey: String, msg: JsonObject) -> JsonObject?,
    /** Their X25519 key (looking it up if needed); null if they never published one. */
    val encKey: suspend (pubkey: String) -> String?,
    val sendSealed: suspend (kind: String, pubkey: String, fields: Map<String, JsonElement>) -> Pair<String?, JsonObject>,
    val openSealed: (msg: JsonObject) -> JsonObject?,
    val seen: (pubkey: String) -> Unit,
    val spawn: (block: suspend () -> Unit) -> Unit,
    val log: (String) -> Unit,
)

sealed interface GroupEvent {
    /** A group message from someone else ([inner] includes its "id"); [live] is false when it came from catch-up. */
    data class Message(val group: GroupRecord, val author: Record?, val inner: JsonObject, val live: Boolean) : GroupEvent
    data class Invite(val group: GroupRecord, val inviter: String) : GroupEvent
    data class Deleted(val group: GroupRecord) : GroupEvent
    data class NewOwner(val group: GroupRecord, val previous: String) : GroupEvent
}

private fun str(s: String) = JsonPrimitive(s)
private fun failed(error: String): JsonObject = obj { put("ok", JsonPrimitive(false)); put("error", str(error)) }
private val OK: JsonObject = obj { put("ok", JsonPrimitive(true)) }
private fun JsonObject?.truthy(key: String): Boolean = this?.get(key).asBool() == true

class Groups(
    val store: GroupStore,
    private val ident: Identity,
    private val phonebook: Phonebook,
    private val trust: TrustStore,
    private val host: GroupsHost,
) {
    /** Called for things the UI cares about (messages, invites, deletion, new owner). */
    @Volatile var onEvent: ((GroupEvent) -> Unit)? = null

    private fun name(pk: String): String = phonebook.get(pk)?.name ?: short(pk)

    private fun emit(e: GroupEvent) { onEvent?.invoke(e) }

    private suspend fun requireEnc(pk: String): String =
        host.encKey(pk) ?: throw IllegalArgumentException("no encryption key for ${short(pk)} yet (they need to come online once)")

    private fun owned(groupId: String): GroupRecord {
        val g = store.get(groupId)
        if (g == null || g.owner != ident.pubkey) throw IllegalArgumentException("only the group owner can change the group")
        return g
    }

    private suspend fun deliverAll(targets: Collection<String>, msg: JsonObject, item: String) {
        // One item id per recipient: the outbox is keyed by id, so a shared id would queue only the first absentee.
        coroutineScope { targets.map { pk -> async { host.deliver(pk, msg, "$item:$pk") } }.awaitAll() }
    }

    private fun updateMsg(g: GroupRecord) = obj { put("type", str("group_update")); put("group", g.toJson()) }

    private fun settingsOf(history: String) = obj { put("history", str(history)) }

    // --- managing groups -------------------------------------------------------------------------

    private suspend fun publish(name: String, members: Collection<String>, prev: GroupRecord?, settings: JsonObject,
                                notify: Collection<String>? = null): GroupRecord {
        val others = (members.toSet() - ident.pubkey).toList().sorted()
        val encKeys = others.associateWith { requireEnc(it) }  // raises before anything changes
        val group = store.buildRecord(name, others, encKeys, prev, settings)
        store.merge(group)
        val targets = (notify ?: group.members).filter { it != ident.pubkey }
        deliverAll(targets, updateMsg(group), "group_update:${group.groupId}:${group.seq}")
        return group
    }

    suspend fun createGroup(name: String, members: Collection<String>, history: String = "private"): GroupRecord {
        require(history in HISTORY_MODES) { "history must be one of ${HISTORY_MODES.joinToString(", ")}" }
        require(name.isNotBlank()) { "a group needs a name" }
        val others = (members.toSet() - ident.pubkey).toList().sorted()
        val encKeys = others.associateWith { requireEnc(it) }  // fail before anything is created
        val group = store.buildRecord(name.trim(), others, encKeys, null, settingsOf(history))
        store.setStatus(group.groupId, "accepted")  // it's ours
        store.merge(group)
        deliverAll(others, updateMsg(group), "group_update:${group.groupId}:${group.seq}")
        return group
    }

    /** Accept an invite: join, then catch up on whatever history we're allowed to read. */
    suspend fun acceptGroup(groupId: String): Int {
        val g = store.get(groupId)
        if (g == null || ident.pubkey !in g.members) throw IllegalArgumentException("no invite for that group")
        store.setStatus(groupId, "accepted")
        return syncGroup(groupId, quiet = true)
    }

    /** Leave (or decline an invite to) a group: stop taking part and ask the owner to remove us. */
    suspend fun leaveGroup(groupId: String, status: String = "left") {
        val g = store.get(groupId) ?: throw IllegalArgumentException("unknown group")
        if (g.owner == ident.pubkey) {
            val others = g.members.filter { it != ident.pubkey }
            if (others.isEmpty()) throw IllegalArgumentException("you're the host of #${g.name} and its only member: delete it to get rid of it")
            throw IllegalArgumentException("you're the host of #${g.name}. Choose a successor to own it, or delete it for everyone")
        }
        store.setStatus(groupId, status)
        val req = obj { put("group", str(groupId)); put("from", str(ident.pubkey)); put("ts", Canonical.now()) }
        val leave = obj { put("type", str("group_leave")); put("req", req); put("sig", str(ident.sign(Canonical.encode(req)))) }
        host.deliver(g.owner, leave, "group_leave:$groupId:${req["ts"]}")
    }

    /**
     * Hand a group we own to another member. We sign the hand-over and send it, with the group's key
     * history, sealed to them; their node then republishes the group as its owner (dropping us if we're
     * leaving). Returns "delivered" / "waiting" (they're offline: it's in our outbox) / null.
     */
    suspend fun transferGroup(groupId: String, newOwner: String, leave: Boolean = false): String? {
        val g = owned(groupId)
        if (newOwner == ident.pubkey || newOwner !in g.members) throw IllegalArgumentException("the new owner has to be another member of the group")
        val transfer = obj { put("to", str(newOwner)); put("sig", str(ident.sign(transferBody(groupId, newOwner, g.transfers.size)))) }
        val keys = store.keysOf(groupId)
        val epochs = JsonObject(store.epochsOf(groupId).filterKeys { it in keys }.entries.associate { (e, meta) ->
            e.toString() to obj {
                put("mode", str(meta.mode)); put("start", Canonical.pyFloat(meta.start)); put("key", str(b64(keys.getValue(e))))
            }
        })
        val handoff = obj { put("group", g.toJson()); put("transfer", transfer); put("epochs", epochs); put("leave", JsonPrimitive(leave)) }
        val (result, _) = host.sendSealed("group_handoff", newOwner, mapOf("handoff" to handoff))
        if (result != null && leave) store.setStatus(groupId, "left")
        return result
    }

    /** Delete a group for everyone: a final, signed "deleted" update. Honest members' nodes forget its messages and keys. */
    suspend fun deleteGroup(groupId: String) {
        val old = owned(groupId)
        publish(old.name, old.members, old, JsonObject(old.settings + ("deleted" to JsonPrimitive(true))))
        store.purge(groupId)
    }

    /** We were handed a group: check the hand-over, adopt its key history, republish as owner. */
    private suspend fun takeOver(inner: JsonObject) {
        val h = inner["handoff"] as? JsonObject ?: JsonObject(emptyMap())
        var group = GroupRecord.fromJson(h["group"])
        val transfer = h["transfer"] as? JsonObject ?: JsonObject(emptyMap())
        val from = inner["from"].asString()
        if (group == null || group.owner != from || ident.pubkey !in group.members || transfer["to"].asString() != ident.pubkey ||
            !verify(group.owner, transferBody(group.groupId, ident.pubkey, group.transfers.size), transfer["sig"].asString() ?: "None")) {
            host.log("ignored an invalid group hand-over")
            return
        }
        val gid = group.groupId
        val sentTransfers = group.transfers.size
        store.merge(group)  // no-op if we already have a newer copy
        group = store.get(gid)!!
        if (group.owner != from || group.transfers.size != sentTransfers) {
            host.log("ignored a stale hand-over of #${group.name}")  // ownership moved on since it was sent
            return
        }
        (h["epochs"] as? JsonObject)?.forEach { (e, meta) ->
            try {
                meta as JsonObject
                store.putEpoch(gid, e.toInt(), unb64(meta["key"].asString()!!), EpochMeta(meta["mode"].asString()!!, meta["start"].asDouble()!!))
            } catch (_: Exception) { }
        }
        val leaving = if (h["leave"].asBool() == true) group.owner else null
        val encKeys = LinkedHashMap<String, String>()
        for (m in group.members) {
            if (m == ident.pubkey || m == leaving) continue
            val k = host.encKey(m)
            if (k != null) encKeys[m] = k else host.log("no key for ${name(m)} yet; they'll need re-adding to #${group.name}")
        }
        val new = store.buildRecord(group.name, encKeys.keys.toList(), encKeys, group, group.settings,
            transfer = obj { put("to", str(ident.pubkey)); put("sig", transfer["sig"]!!) })
        store.setStatus(gid, "accepted")
        store.merge(new)
        host.log("${name(from!!)} made you the owner of #${group.name}")
        emit(GroupEvent.NewOwner(new, from))
        deliverAll(group.members.filter { it != ident.pubkey }, updateMsg(new), "group_update:$gid:${new.seq}")
    }

    /** Invites we haven't answered yet. */
    fun pendingGroups(): List<GroupRecord> =
        store.all().filter { store.status(it.groupId) == "pending" && ident.pubkey in it.members }

    suspend fun addMember(groupId: String, pubkey: String): GroupRecord {
        val old = owned(groupId)
        return publish(old.name, old.members + pubkey, old, old.settings)
    }

    suspend fun removeMember(groupId: String, pubkey: String): GroupRecord {
        val old = owned(groupId)
        // Tell the removed member too, so they know they're out. They don't get the new key.
        return publish(old.name, old.members.filter { it != pubkey }, old, old.settings, notify = old.members)
    }

    /** Starts a new key epoch; messages already sent keep the setting they were sent under. */
    suspend fun setHistory(groupId: String, history: String): GroupRecord {
        require(history in HISTORY_MODES) { "history must be one of ${HISTORY_MODES.joinToString(", ")}" }
        val old = owned(groupId)
        return publish(old.name, old.members, old, JsonObject(old.settings + ("history" to str(history))))
    }

    // --- messages --------------------------------------------------------------------------------

    /** Deliver to every member we can reach right now. Returns (delivered, other members); anyone missed catches up via sync. */
    suspend fun sendGroup(groupId: String, text: String, extra: Map<String, JsonElement> = emptyMap()): Pair<Int, Int> {
        val g = store.get(groupId)
        if (g == null || ident.pubkey !in g.members) throw IllegalArgumentException("not a member of that group")
        if (!store.joined(groupId)) throw IllegalArgumentException("accept the invite first")
        val envelope = store.sealMessage(groupId, text, extra)
        store.addMessage(envelope)
        val msg = obj { put("type", str("group_msg")); put("envelope", envelope); put("group", g.toJson()) }
        val others = g.members.filter { it != ident.pubkey }
        val replies = coroutineScope { others.map { pk -> async { host.requestPeer(pk, msg) } }.awaitAll() }
        return replies.count { it.truthy("ok") } to others.size
    }

    /** Ask every other member for messages we don't have. Returns how many new ones we got. [quiet]: don't raise events (history on joining). */
    suspend fun syncGroup(groupId: String, quiet: Boolean = false): Int {
        val g = store.get(groupId)
        if (g == null || !store.joined(groupId)) return 0
        val req = obj {
            put("group", str(groupId)); put("from", str(ident.pubkey)); put("ts", Canonical.now())
            put("have", stringArray(store.logIds(groupId)))
        }
        val msg = obj { put("type", str("group_sync")); put("req", req); put("sig", str(ident.sign(Canonical.encode(req)))) }
        val others = g.members.filter { it != ident.pubkey }
        var new = 0
        val replies = coroutineScope { others.map { pk -> async { host.requestPeer(pk, msg) } }.awaitAll() }
        for (reply in replies) {
            if (reply == null || !reply.truthy("ok")) continue
            store.merge(GroupRecord.fromJson(reply["group"]))
            (reply["messages"] as? JsonArray)?.forEach { envelope ->
                if (envelope is JsonObject) store.addMessage(envelope)?.let { inner ->
                    new++
                    if (!quiet) emitMessage(envelope, inner, live = false)
                }
            }
        }
        return new
    }

    suspend fun syncAll(): Int = coroutineScope { store.all().map { g -> async { syncGroup(g.groupId) } }.awaitAll().sum() }

    private fun emitMessage(envelope: JsonObject, inner: JsonObject, live: Boolean) {
        val gid = inner["group"].asString() ?: return
        val g = store.get(gid) ?: return
        val withId = JsonObject(inner + ("id" to (envelope["id"] ?: JsonPrimitive(""))))
        emit(GroupEvent.Message(g, phonebook.get(inner["from"].asString() ?: ""), withId, live))
    }

    // --- incoming --------------------------------------------------------------------------------

    suspend fun handle(kind: String, msg: JsonObject): JsonObject {
        when (kind) {
            "group_handoff" -> {
                val inner = host.openSealed(msg) ?: return failed("bad message")
                val from = inner["from"].asString()!!
                if (trust.blocked(from)) return OK  // dropped; don't tell them they're blocked
                host.seen(from)
                host.spawn { takeOver(inner) }
                return OK
            }

            "group_update" -> {
                val group = GroupRecord.fromJson(msg["group"]) ?: return failed("bad group")
                val known = store.get(group.groupId)
                if (known == null && (ident.pubkey !in group.members || trust.dropped(group.owner))) return failed("not a member")
                val wasMember = known != null && ident.pubkey in known.members
                if (store.merge(group)) {
                    val inviter = name(group.owner)
                    val gid = group.groupId
                    if (group.deleted) {
                        if (store.status(gid) in listOf("accepted", "pending")) {
                            host.log("$inviter deleted group ${group.name}")
                            emit(GroupEvent.Deleted(group))
                        }
                        store.purge(gid)
                    } else if (known != null && known.owner != group.owner && ident.pubkey in group.members) {
                        host.log("#${group.name} has a new owner: ${name(group.owner)}")
                        emit(GroupEvent.NewOwner(group, known.owner))
                    } else if (ident.pubkey !in group.members) {
                        if (store.status(gid) == "accepted") {
                            store.setStatus(gid, "removed")
                            host.log("you were removed from group ${group.name}")
                        }
                    } else if (!wasMember) {
                        // An invite, not a membership: nothing shows until it's accepted.
                        store.setStatus(gid, "pending")
                        host.log("$inviter invited you to group ${group.name}")
                        emit(GroupEvent.Invite(group, group.owner))
                    } else if (known!!.history != group.history) {
                        host.log("group ${group.name} history is now ${group.history}")
                    } else if (known.members != group.members && store.joined(gid)) {
                        host.log("group ${group.name} now has ${group.members.size} members")
                    }
                }
                return OK
            }

            "group_leave" -> {
                val req = msg["req"] as? JsonObject ?: JsonObject(emptyMap())
                val sig = msg["sig"].asString() ?: "None"
                val group = store.get(req["group"].asString() ?: "None")
                val member = req["from"].asString() ?: "None"
                val ts = req["ts"].asDouble()
                val fresh = ts != null && abs(Canonical.nowSeconds() - ts) <= LEAVE_MAX_AGE
                if (group == null || group.owner != ident.pubkey || member !in group.members || !fresh ||
                    !verify(member, Canonical.encode(req), sig)) return failed("not allowed")
                host.log("${name(member)} left group ${group.name}")
                host.spawn { removeMember(group.groupId, member) }
                return OK
            }

            "group_msg" -> {
                GroupRecord.fromJson(msg["group"])?.let { if (ident.pubkey in it.members) store.merge(it) }
                val envelope = msg["envelope"] as? JsonObject ?: JsonObject(emptyMap())
                val gid = envelope["group"].asString() ?: "None"
                if (store.status(gid) in listOf("declined", "left")) return failed("not a member")
                val inner = store.addMessage(envelope)
                if (inner != null) {
                    if (store.joined(gid)) emitMessage(envelope, inner, live = true)  // pending invites keep messages for later, quietly
                    return OK
                }
                return if (store.has(gid, envelope["id"].asString() ?: "None")) OK else failed("rejected")
            }

            "group_sync" -> {
                val req = msg["req"] as? JsonObject ?: JsonObject(emptyMap())
                val sig = msg["sig"].asString() ?: "None"
                val group = store.get(req["group"].asString() ?: "None")
                val ts = req["ts"].asDouble()
                val fresh = ts != null && abs(Canonical.nowSeconds() - ts) <= SYNC_MAX_AGE
                val asker = req["from"].asString()
                if (group == null || !fresh || asker == null || asker !in group.members || !store.joined(group.groupId) ||
                    !verify(asker, Canonical.encode(req), sig)) return failed("not allowed")
                val have = (req["have"] as? JsonArray)?.mapNotNull { it.asString() }?.toSet() ?: emptySet()
                val messages = store.missingFor(group.groupId, have, asker)
                return obj { put("ok", JsonPrimitive(true)); put("group", group.toJson()); put("messages", JsonArray(messages)) }
            }
        }
        return failed("unknown type '$kind'")
    }
}
