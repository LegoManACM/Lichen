package dev.lichen.core.node

import dev.lichen.core.Canonical
import dev.lichen.core.EncKey
import dev.lichen.core.Identity
import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.ChatMessage
import dev.lichen.core.api.ClientEvent
import dev.lichen.core.api.Contact
import dev.lichen.core.api.FilesApi
import dev.lichen.core.api.HubStatus
import dev.lichen.core.api.MessageStatus
import dev.lichen.core.api.Profile
import dev.lichen.core.api.Settings
import dev.lichen.core.api.Trust
import dev.lichen.core.asDouble
import dev.lichen.core.asLong
import dev.lichen.core.asString
import dev.lichen.core.b64
import dev.lichen.core.deviceId
import dev.lichen.core.dumps
import dev.lichen.core.fingerprint
import dev.lichen.core.obj
import dev.lichen.core.parseObject
import dev.lichen.core.secureRandom
import dev.lichen.core.short
import dev.lichen.core.store.DmStore
import dev.lichen.core.store.Outbox
import dev.lichen.core.store.Phonebook
import dev.lichen.core.store.TrustStore
import dev.lichen.core.writeFileAtomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Chat messages older than this arriving via chat sync are history, not news: no unread count, no notification. */
private const val STALE_AFTER = 7 * 24 * 3600.0
private val ACTIONS = setOf("react", "edit", "delete")  // TODO(phase 2): replies, reactions, edits (thread.py)

/**
 * The ChatClient the app uses: a [Node] plus the state the UI wants as flows. Opening is cheap and does
 * no IO on the calling thread; the data folder is loaded in the background. After [stop], [start] can be
 * called again (a fresh Node on the same stores).
 */
class NodeClient(private val dataDir: File) : ChatClient {
    private class Loaded(
        val settings: Settings, val ident: Identity, val enc: EncKey, val device: String,
        val phonebook: Phonebook, val dms: DmStore, val outbox: Outbox, val trust: TrustStore,
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)  // lives as long as the client object
    @Volatile private var loaded: Loaded? = null

    private val _profile = MutableStateFlow<Profile?>(null)
    private val _hubs = MutableStateFlow<List<HubStatus>>(emptyList())
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    private val _people = MutableStateFlow<List<Contact>>(emptyList())
    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val profile: StateFlow<Profile?> = _profile
    override val hubs: StateFlow<List<HubStatus>> = _hubs
    override val contacts: StateFlow<List<Contact>> = _contacts
    override val people: StateFlow<List<Contact>> = _people
    override val events: SharedFlow<ClientEvent> = _events
    private val fileService = FileService(dataDir, { _events.tryEmit(it) })
    override val files: FilesApi = fileService

    private val chats = ConcurrentHashMap<String, MutableStateFlow<List<ChatMessage>>>()
    private val unread = ConcurrentHashMap<String, Int>()
    private val inflight = ConcurrentHashMap<String, ChatMessage>()   // message id -> being sent right now
    private val failedSends = ConcurrentHashMap<String, ChatMessage>()  // message id -> send returned null (this run only)
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    private val lifecycle = Mutex()
    private var node: Node? = null
    private var run: CoroutineScope? = null
    @Volatile private var runningNode: Node? = null

    // Declared last: load() runs on another thread straight away and uses everything above.
    private val loading = ioScope.async { load() }

    init {
        ioScope.launch {
            for (u in dirty) {
                try { recompute() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                delay(30)  // coalesce bursts of changes
            }
        }
    }

    private fun markDirty() { dirty.trySend(Unit) }

    private fun load(): Loaded {
        val settings = dev.lichen.core.FileSetup().settings(dataDir) ?: error("no settings in $dataDir")
        val ident = Identity.loadOrCreate(File(dataDir, "key"))
        val enc = EncKey.loadOrCreate(File(dataDir, "enckey"))
        val l = Loaded(settings, ident, enc, deviceId(dataDir),
            Phonebook(File(dataDir, "phonebook.json")), DmStore(File(dataDir, "dms.json")),
            Outbox(File(dataDir, "outbox.json")), TrustStore(File(dataDir, "trust.json")))
        parseObject(File(dataDir, "unread.json").takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: "{}")?.forEach { (k, v) ->
            v.asLong()?.takeIf { it > 0 }?.let { unread[k] = it.toInt() }
        }
        fileService.load()
        val hook = { markDirty() }
        l.phonebook.onChange = hook; l.dms.onChange = hook; l.outbox.onChange = hook; l.trust.onChange = hook
        loaded = l
        _profile.value = Profile(ident.pubkey, settings.name, settings.hubs, fingerprint(ident.pubkey), l.device)
        _hubs.value = settings.hubs.map { HubStatus(it, false) }
        markDirty()
        return l
    }

    // --- lifecycle -------------------------------------------------------------------------------

    override suspend fun start() = withContext(Dispatchers.IO) {
        val l = loading.await()
        lifecycle.withLock {
            if (node != null) return@withLock
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            run = scope
            val n = Node(l.settings.name, l.ident, l.enc, l.phonebook, l.dms, l.outbox, l.trust,
                relays = l.settings.hubs, device = l.device,
                onMessage = { rec, text, m -> onIncoming(l, rec, text, m) },
                onEvent = { kind, pk, count ->
                    if (kind == "owed" && pk != null) {
                        _events.tryEmit(ClientEvent.Log("${displayName(l, pk)} has $count message(s) for you; they'll arrive when they're online"))
                    }
                    markDirty()
                })
            node = n
            runningNode = n
            fileService.attach(n)
            // The hubs row follows each relay connection; online dots expire with time.
            n.onRelayClient = { c -> scope.launch { c.connected.collect { publishHubs(n) } } }
            scope.launch { while (true) { delay(15_000); markDirty() } }
            n.start()
            publishHubs(n)
            markDirty()
        }
    }

    override suspend fun stop() {
        lifecycle.withLock {
            runningNode = null
            fileService.detach()
            node?.stop()
            node = null
            run?.cancel()
            run = null
            loaded?.let { l -> _hubs.value = l.settings.hubs.map { HubStatus(it, false) } }
        }
    }

    private fun publishHubs(n: Node) {
        if (runningNode !== n) return
        _hubs.value = n.relays.mapIndexed { i, hub -> HubStatus(hub, n.relayClients.getOrNull(i)?.connected?.value == true) }
    }

    /** The node's background catch-up (join, announce, chat sync, outbox) has finished. For tests. */
    internal suspend fun awaitCatchUp() { runningNode?.catchUp?.join() }

    internal fun node(): Node? = runningNode

    override suspend fun syncNow() {
        val n = runningNode ?: return
        withContext(Dispatchers.IO) {
            for (addr in n.bootstrap) n.join(addr)
            n.announce()
            n.syncDms()
            n.flushOutbox()
        }
        _events.tryEmit(ClientEvent.Log("Synced"))
    }

    // --- sending and receiving -----------------------------------------------------------------

    override suspend fun send(pubkey: String, text: String): MessageStatus = withContext(Dispatchers.IO) {
        val l = loading.await()
        val n = runningNode
        if (n == null) {
            _events.tryEmit(ClientEvent.Log("Not connected yet; try again in a moment"))
            return@withContext MessageStatus.FAILED
        }
        val id = b64(ByteArray(12).also { secureRandom.nextBytes(it) })
        val placeholder = ChatMessage(id, l.ident.pubkey, pubkey, Canonical.nowSeconds(), text, true, MessageStatus.SENDING)
        inflight[id] = placeholder
        markDirty()
        var result: String? = null
        try {
            result = try { n.send(pubkey, text, id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        } finally {
            inflight.remove(id)
            if (result == null) failedSends[id] = placeholder.copy(status = MessageStatus.FAILED)
            markDirty()
        }
        when (result) {
            "delivered" -> MessageStatus.DELIVERED
            "waiting" -> MessageStatus.WAITING
            else -> MessageStatus.FAILED
        }
    }

    private fun onIncoming(l: Loaded, rec: dev.lichen.core.Record?, text: String, m: JsonObject) {
        if (m["kind"].asString() in ACTIONS) return
        val from = m["from"].asString() ?: return
        val ts = m["ts"].asDouble() ?: 0.0
        if (Canonical.nowSeconds() - ts > STALE_AFTER) return
        unread.merge(from, 1) { a, b -> a + b }
        saveUnread()
        markDirty()
        val message = toChatMessage(l, m, from) ?: return
        _events.tryEmit(ClientEvent.Incoming(contactFor(l, from), message))
    }

    override fun markRead(pubkey: String) {
        if (unread.remove(pubkey) != null) { saveUnread(); markDirty() }
        // TODO(phase 2): send a read receipt (node.send_read).
    }

    private val unreadLock = Any()
    private fun saveUnread() {
        ioScope.launch {
            synchronized(unreadLock) {
                writeFileAtomic(File(dataDir, "unread.json"), dumps(JsonObject(unread.filterValues { it > 0 }.mapValues { JsonPrimitive(it.value) })))
            }
        }
    }

    override suspend fun setTrust(pubkey: String, trust: Trust) = withContext(Dispatchers.IO) {
        loading.await().trust.set(pubkey, trust.name.lowercase())
    }

    override suspend fun setNickname(pubkey: String, nickname: String?) = withContext(Dispatchers.IO) {
        try {
            loading.await().trust.setNickname(pubkey, nickname)
        } catch (e: IllegalArgumentException) {
            _events.tryEmit(ClientEvent.Log(e.message ?: "can't use that nickname"))
            Unit
        }
    }

    // --- state for the UI ------------------------------------------------------------------------

    override fun messages(pubkey: String): StateFlow<List<ChatMessage>> =
        chats.getOrPut(pubkey) { MutableStateFlow(loaded?.let { messagesOf(it, pubkey) } ?: emptyList()) }

    private fun displayName(l: Loaded, pk: String): String = l.trust.nickname(pk) ?: l.phonebook.get(pk)?.name ?: short(pk)

    private fun toChatMessage(l: Loaded, m: JsonObject, peer: String): ChatMessage? {
        val id = m["id"].asString() ?: return null
        val from = m["from"].asString() ?: return null
        val outgoing = from == l.ident.pubkey
        val status = if (!outgoing) null else when {
            l.outbox.has("msg:$id") -> MessageStatus.WAITING
            l.dms.receipt(peer, m) == "read" -> MessageStatus.READ
            else -> MessageStatus.DELIVERED  // "delivered", or sent from another device long ago
        }
        return ChatMessage(id, from, m["to"].asString() ?: "", m["ts"].asDouble() ?: 0.0, m["text"].asString() ?: "", outgoing, status)
    }

    private fun messagesOf(l: Loaded, pubkey: String): List<ChatMessage> {
        val stored = l.dms.messages(pubkey).filter { it["kind"].asString() !in ACTIONS }.mapNotNull { toChatMessage(l, it, pubkey) }
        val have = stored.mapTo(HashSet()) { it.id }
        val extra = (inflight.values + failedSends.values).filter { it.to == pubkey && it.id !in have }
        return if (extra.isEmpty()) stored else (stored + extra).sortedBy { it.ts }
    }

    private fun contactFor(l: Loaded, pk: String, withLast: Boolean = true): Contact {
        val level = l.trust.get(pk)
        return Contact(
            pubkey = pk,
            name = l.phonebook.get(pk)?.name ?: short(pk),
            nickname = l.trust.nickname(pk),
            trust = when (level) { "trusted" -> Trust.TRUSTED; "blocked" -> Trust.BLOCKED; else -> Trust.UNKNOWN },
            online = runningNode?.online(pk) == true,
            unread = unread[pk] ?: 0,
            lastMessage = if (withLast) messagesOf(l, pk).lastOrNull() else null,
        )
    }

    private fun recompute() {
        val l = loaded ?: return
        val me = l.ident.pubkey
        fileService.refresh()
        for ((pk, flow) in chats) flow.value = messagesOf(l, pk)
        val keys = (l.trust.keys("trusted") + l.dms.peers()).filter { it != me && !l.trust.blocked(it) }.distinct()
        _contacts.value = keys.map { contactFor(l, it) }
            .sortedWith(compareByDescending<Contact> { it.lastMessage?.ts ?: 0.0 }.thenBy { it.displayName.lowercase() })
        _people.value = l.phonebook.all().filter { it.pubkey != me }.map { contactFor(l, it.pubkey, withLast = false) }
            .sortedBy { it.displayName.lowercase() }
    }
}
