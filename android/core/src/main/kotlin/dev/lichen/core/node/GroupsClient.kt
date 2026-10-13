package dev.lichen.core.node

import dev.lichen.core.Canonical
import dev.lichen.core.Identity
import dev.lichen.core.api.ClientEvent
import dev.lichen.core.api.GroupHistory
import dev.lichen.core.api.GroupInfo
import dev.lichen.core.api.GroupMember
import dev.lichen.core.api.GroupMessage
import dev.lichen.core.api.GroupResult
import dev.lichen.core.api.GroupsApi
import dev.lichen.core.asDouble
import dev.lichen.core.asLong
import dev.lichen.core.asString
import dev.lichen.core.dumps
import dev.lichen.core.parseObject
import dev.lichen.core.short
import dev.lichen.core.store.GroupRecord
import dev.lichen.core.store.GroupStore
import dev.lichen.core.store.Phonebook
import dev.lichen.core.store.TrustStore
import dev.lichen.core.writeFileAtomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val GROUP_STALE_AFTER = 7 * 24 * 3600.0  // catch-up older than this is history, not news
private val GROUP_ACTIONS = setOf("react", "edit", "delete")  // replies/reactions/edits aren't shown (yet)

/**
 * [GroupsApi] on top of the node's [Groups] and the [GroupStore]: flows for the UI, unread counts, events.
 * Created with the NodeClient; [bind] hands it the stores once they are loaded, [attach] the node once it runs.
 */
internal class GroupsClient(
    private val dataDir: File,
    private val ioScope: CoroutineScope,
    private val events: MutableSharedFlow<ClientEvent>,
    private val node: () -> Node?,
) : GroupsApi {
    private class Bound(val store: GroupStore, val ident: Identity, val phonebook: Phonebook, val trust: TrustStore)

    @Volatile private var bound: Bound? = null
    private val _groups = MutableStateFlow<List<GroupInfo>>(emptyList())
    override val groups: StateFlow<List<GroupInfo>> = _groups
    private val flows = ConcurrentHashMap<String, MutableStateFlow<List<GroupMessage>>>()
    private val unread = ConcurrentHashMap<String, Int>()
    private val unreadLock = Any()

    fun bind(store: GroupStore, ident: Identity, phonebook: Phonebook, trust: TrustStore) {
        parseObject(File(dataDir, "group-unread.json").takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: "{}")?.forEach { (k, v) ->
            v.asLong()?.takeIf { it > 0 }?.let { unread[k] = it.toInt() }
        }
        bound = Bound(store, ident, phonebook, trust)
    }

    fun attach(n: Node) {
        n.groups.onEvent = { onEvent(it) }
    }

    // --- events from the node ----------------------------------------------------------------------

    private fun onEvent(e: GroupEvent) {
        val b = bound ?: return
        when (e) {
            is GroupEvent.Message -> {
                val inner = e.inner
                if (inner["kind"].asString() in GROUP_ACTIONS) return
                val from = inner["from"].asString() ?: return
                if (from == b.ident.pubkey) return
                val ts = inner["ts"].asDouble() ?: 0.0
                if (Canonical.nowSeconds() - ts > GROUP_STALE_AFTER) return
                val gid = e.group.groupId
                unread.merge(gid, 1) { a, c -> a + c }
                saveUnread()
                val msg = toMessage(b, inner) ?: return
                events.tryEmit(ClientEvent.GroupIncoming(info(b, e.group, msg), msg))
            }
            is GroupEvent.Invite -> events.tryEmit(ClientEvent.GroupInvite(info(b, e.group), name(b, e.inviter)))
            is GroupEvent.Deleted -> events.tryEmit(ClientEvent.Log("${name(b, e.group.owner)} deleted the group ${e.group.name}"))
            is GroupEvent.NewOwner -> events.tryEmit(ClientEvent.Log("${name(b, e.group.owner)} now owns the group ${e.group.name}"))
        }
    }

    private fun saveUnread() {
        ioScope.launch {
            synchronized(unreadLock) {
                writeFileAtomic(File(dataDir, "group-unread.json"), dumps(JsonObject(unread.filterValues { it > 0 }.mapValues { JsonPrimitive(it.value) })))
            }
        }
    }

    override fun markRead(groupId: String) {
        if (unread.remove(groupId) != null) saveUnread()
    }

    // --- state for the UI --------------------------------------------------------------------------

    private fun name(b: Bound, pk: String): String = b.trust.nickname(pk) ?: b.phonebook.get(pk)?.name ?: short(pk)

    private fun toMessage(b: Bound, m: JsonObject): GroupMessage? {
        if (m["kind"].asString() in GROUP_ACTIONS) return null
        val from = m["from"].asString() ?: return null
        return GroupMessage(m["id"].asString() ?: return null, m["group"].asString() ?: return null, from, name(b, from),
            m["ts"].asDouble() ?: 0.0, m["text"].asString() ?: "", from == b.ident.pubkey)
    }

    private fun messagesOf(b: Bound, gid: String): List<GroupMessage> = b.store.messages(gid).mapNotNull { toMessage(b, it) }

    private fun info(b: Bound, g: GroupRecord, last: GroupMessage? = messagesOf(b, g.groupId).lastOrNull()): GroupInfo {
        val me = b.ident.pubkey
        val members = g.members.map { GroupMember(it, name(b, it), it == g.owner, it == me) }
            .sortedWith(compareByDescending<GroupMember> { it.isOwner }.thenBy { it.name.lowercase() })
        return GroupInfo(
            id = g.groupId, name = g.name, owner = g.owner, ownerName = name(b, g.owner), members = members,
            history = if (g.history == "shared") GroupHistory.SHARED else GroupHistory.PRIVATE,
            youOwn = g.owner == me, pending = b.store.status(g.groupId) == "pending",
            unread = unread[g.groupId] ?: 0, lastMessage = last,
        )
    }

    override fun messages(groupId: String): StateFlow<List<GroupMessage>> =
        flows.getOrPut(groupId) { MutableStateFlow(bound?.let { messagesOf(it, groupId) } ?: emptyList()) }

    /** Rebuild the flows from the store (the NodeClient calls this whenever anything changed). */
    fun recompute() {
        val b = bound ?: return
        for ((gid, flow) in flows) flow.value = messagesOf(b, gid)
        _groups.value = b.store.all()
            .filter { b.ident.pubkey in it.members && b.store.status(it.groupId) in listOf("accepted", "pending") }
            .map { g -> info(b, g, flows[g.groupId]?.value?.lastOrNull() ?: messagesOf(b, g.groupId).lastOrNull()) }
            .sortedWith(compareByDescending<GroupInfo> { it.pending }
                .thenByDescending { it.lastMessage?.ts ?: 0.0 }.thenBy { it.name.lowercase() })
    }

    // --- actions -----------------------------------------------------------------------------------

    private suspend fun act(block: suspend (Node, Bound) -> GroupResult): GroupResult = withContext(Dispatchers.IO) {
        val b = bound
        val n = node()
        if (b == null || n == null) return@withContext GroupResult.Failed("Not connected yet; try again in a moment")
        try {
            block(n, b)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            GroupResult.Failed(e.message ?: "that didn't work")
        } catch (e: Exception) {
            GroupResult.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun mode(h: GroupHistory) = if (h == GroupHistory.SHARED) "shared" else "private"

    private fun handOver(b: Bound, groupId: String, newOwner: String, result: String?): GroupResult = when (result) {
        "delivered" -> GroupResult.Ok(groupId)
        "waiting" -> GroupResult.Ok(groupId, "${name(b, newOwner)} is offline; they'll take over when they're back")
        else -> GroupResult.Failed("${name(b, newOwner)} didn't accept the hand-over")
    }

    override suspend fun create(name: String, members: List<String>, history: GroupHistory) = act { n, _ ->
        GroupResult.Ok(n.groups.createGroup(name, members, mode(history)).groupId)
    }

    override suspend fun accept(groupId: String) = act { n, _ ->
        val got = n.groups.acceptGroup(groupId)
        unread.remove(groupId)
        GroupResult.Ok(groupId, if (got > 0) "$got earlier message(s)" else null)
    }

    override suspend fun decline(groupId: String) = act { n, _ ->
        n.groups.leaveGroup(groupId, "declined")
        GroupResult.Ok(groupId)
    }

    override suspend fun leave(groupId: String, newOwner: String?) = act { n, b ->
        val g = b.store.get(groupId)
        if (g != null && g.owner == b.ident.pubkey && newOwner != null) {
            handOver(b, groupId, newOwner, n.groups.transferGroup(groupId, newOwner, leave = true))
        } else {
            n.groups.leaveGroup(groupId)
            GroupResult.Ok(groupId)
        }
    }

    override suspend fun addMember(groupId: String, pubkey: String) = act { n, _ -> n.groups.addMember(groupId, pubkey); GroupResult.Ok(groupId) }

    override suspend fun removeMember(groupId: String, pubkey: String) = act { n, _ -> n.groups.removeMember(groupId, pubkey); GroupResult.Ok(groupId) }

    override suspend fun transfer(groupId: String, newOwner: String) = act { n, b ->
        handOver(b, groupId, newOwner, n.groups.transferGroup(groupId, newOwner, leave = false))
    }

    override suspend fun delete(groupId: String) = act { n, _ -> n.groups.deleteGroup(groupId); GroupResult.Ok(groupId) }

    override suspend fun setHistory(groupId: String, history: GroupHistory) = act { n, _ ->
        n.groups.setHistory(groupId, mode(history)); GroupResult.Ok(groupId)
    }

    override suspend fun send(groupId: String, text: String) = act { n, _ ->
        val (delivered, others) = n.groups.sendGroup(groupId, text)
        GroupResult.Ok(groupId, if (others == 0) null else "delivered to $delivered of $others")
    }

    override suspend fun sync(groupId: String?): Int = withContext(Dispatchers.IO) {
        val n = node() ?: return@withContext 0
        try { if (groupId != null) n.groups.syncGroup(groupId) else n.groups.syncAll() } catch (e: CancellationException) { throw e } catch (e: Exception) { 0 }
    }
}
