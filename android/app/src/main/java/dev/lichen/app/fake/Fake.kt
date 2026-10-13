package dev.lichen.app.fake

import dev.lichen.app.Backend
import dev.lichen.app.OpenChat
import dev.lichen.core.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/** In-memory stand-in for the real node: for previews and until the core lands. */
class FakeSetup : LichenSetup {
    private fun file(dir: File) = File(dir, "fake-settings.txt")

    override fun hasIdentity(dataDir: File) = file(dataDir).exists()

    override fun settings(dataDir: File): Settings? {
        val lines = file(dataDir).takeIf { it.exists() }?.readLines() ?: return null
        return Settings(lines.firstOrNull().orEmpty(), lines.drop(1).filter { it.isNotBlank() })
    }

    override fun saveSettings(dataDir: File, settings: Settings) {
        dataDir.mkdirs()
        // Each profile gets its own cast of fake contacts: the 1st, 2nd, 3rd... profile folder get cast 0, 1, 2...
        val variant = File(dataDir, "fake-variant")
        if (!variant.exists()) {
            val siblings = dataDir.parentFile?.listFiles { f -> File(f, "fake-variant").exists() }?.size ?: 0
            variant.writeText((siblings % 3).toString())
        }
        file(dataDir).writeText((listOf(settings.name) + settings.hubs).joinToString("\n"))
    }

    override fun create(dataDir: File, settings: Settings) = saveSettings(dataDir, settings)

    override fun import(dataDir: File, exportText: String, passphrase: String): SetupResult {
        if (exportText.isBlank()) return SetupResult.Failed("That file is empty.")
        if (passphrase == "wrong") return SetupResult.Failed("Wrong passphrase.")
        val s = Settings("Afrika", listOf("wss://hub.example.org", "wss://backup.example.net"))
        saveSettings(dataDir, s)
        File(dataDir, "linked").writeText("")  // like the real one: an imported identity also lives elsewhere
        return SetupResult.Ok(s)
    }

    override fun open(dataDir: File): ChatClient = FakeChatClient(
        settings(dataDir) ?: Settings("Me", listOf("wss://hub.example.org")),
        dataDir.name,
        File(dataDir, "fake-variant").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0,
    )
}

class FakeChatClient(private val settings: Settings, private val profileId: String = "", private val variant: Int = 0) : ChatClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val me = "meKEY${variant}000000000000000000000000000000000000"

    private class Person(val pubkey: String, val name: String, var trust: Trust, var online: Boolean, var nickname: String? = null)

    private fun key(name: String) = (name + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP").take(43)

    // A different cast per profile, so switching profiles is visible.
    private val names = listOf(
        listOf("Alice", "Bob", "Carol", "Dave", "Mallory", "Eve"),
        listOf("Zoe", "Yuri", "Xena", "Walt", "Trent", "Victor"),
        listOf("Priya", "Quinn", "Ravi", "Sam", "Oscar", "Peggy"),
    )[variant.mod(3)]
    private val alice = key(names[0])
    private val bob = key(names[1])
    private val carol = key(names[2])
    private val mal = key(names[4])

    private val everyone = listOf(
        Person(alice, names[0], Trust.TRUSTED, true),
        Person(bob, names[1], Trust.TRUSTED, false),
        Person(carol, names[2], Trust.TRUSTED, false, "${names[2]} (work)"),
        Person(key(names[3]), names[3], Trust.TRUSTED, true),
        Person(mal, names[4], Trust.UNKNOWN, false),
        Person(key(names[5]), names[5], Trust.BLOCKED, false),
    )
    private fun person(pk: String) = everyone.first { it.pubkey == pk }

    private val msgs = HashMap<String, MutableStateFlow<List<ChatMessage>>>()
    private val unread = HashMap<String, Int>()
    private var counter = 0

    private fun flow(pk: String) = synchronized(msgs) { msgs.getOrPut(pk) { MutableStateFlow(emptyList()) } }

    override val profile = MutableStateFlow<Profile?>(null)
    override val hubs = MutableStateFlow(settings.hubs.map { HubStatus(it, false) })
    override val contacts = MutableStateFlow<List<Contact>>(emptyList())
    override val people = MutableStateFlow<List<Contact>>(emptyList())
    override val events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 16)
    override val groups: GroupsApi = FakeGroups(me)
    override val files: dev.lichen.core.api.FilesApi = FakeFiles()

    override val typing = MutableStateFlow<Set<String>>(emptySet())
    override val requests = MutableStateFlow<List<Contact>>(emptyList())
    override val log = MutableStateFlow(listOf("fake node: nothing to log"))
    private val verifiedKeys = HashSet<String>()

    override fun typing(pubkey: String) {}
    override fun safetyCode(pubkey: String): String = "12345 67890 12345 67890"
    override suspend fun setVerified(pubkey: String, verified: Boolean) {
        if (verified) verifiedKeys.add(pubkey) else verifiedKeys.remove(pubkey)
        refresh()
    }

    override fun messages(pubkey: String): StateFlow<List<ChatMessage>> = flow(pubkey)

    private fun now() = System.currentTimeMillis() / 1000.0

    @Synchronized
    private fun add(from: String, to: String, text: String, ago: Double = 0.0, status: MessageStatus? = null): ChatMessage {
        val outgoing = from == me
        val m = ChatMessage("m${counter++}", from, to, now() - ago, text, outgoing, if (outgoing) status else null)
        flow(if (outgoing) to else from).update { it + m }
        return m
    }

    private fun setStatus(pk: String, id: String, st: MessageStatus) {
        flow(pk).update { l -> l.map { if (it.id == id) it.copy(status = st) else it } }
        refresh()
    }

    private fun contactOf(p: Person) = Contact(
        p.pubkey, p.name, p.nickname, p.trust, p.online, unread[p.pubkey] ?: 0, flow(p.pubkey).value.lastOrNull(),
        p.pubkey in verifiedKeys,
    )

    @Synchronized
    private fun refresh() {
        profile.value = Profile(me, settings.name, settings.hubs, "meKE Y000 0000 0000", "d3v1c3-fake")
        contacts.value = everyone.filter { it.trust != Trust.BLOCKED }
            .map { contactOf(it) }
            .filter { it.lastMessage != null }
            .sortedByDescending { it.lastMessage?.ts ?: 0.0 }
        requests.value = contacts.value.filter { it.trust == Trust.UNKNOWN }
        people.value = everyone.map { contactOf(it) }.sortedBy { it.displayName.lowercase() }
    }

    init {
        add(alice, me, "Hey! Did the export import work?", 7200.0)
        add(me, alice, "Yes, it did. Lichen on my phone now.", 7100.0, MessageStatus.READ)
        add(alice, me, "Nice. Say hi whenever you like.", 600.0)
        unread[alice] = 1
        add(me, bob, "Are we still on for tomorrow?", 90000.0, MessageStatus.WAITING)
        add(carol, me, "Notes from today are in the shared folder.", 200000.0)
        add(mal, me, "Hi, a friend gave me your key. Can we talk?", 300.0)
        unread[mal] = 1
        refresh()
        scope.launch { // hubs coming and going
            delay(800); setHub(0, true)
            delay(1500); setHub(1, true)
            while (true) { delay(40000); setHub(1, false); delay(5000); setHub(1, true) }
        }
        scope.launch { // someone's online status flips
            while (true) { delay(25000); person(alice).online = !person(alice).online; refresh() }
        }
    }

    private fun setHub(i: Int, up: Boolean) {
        hubs.update { l -> l.mapIndexed { j, h -> if (j == i) h.copy(connected = up) else h } }
    }

    override suspend fun start() { refresh() }

    override suspend fun stop() { scope.cancel() }

    override suspend fun send(pubkey: String, text: String): MessageStatus {
        val p = person(pubkey)
        if (p.trust == Trust.UNKNOWN) p.trust = Trust.TRUSTED
        val m = add(me, pubkey, text, 0.0, MessageStatus.SENDING)
        refresh()
        scope.launch {
            delay(600)
            if (p.pubkey == bob) { setStatus(pubkey, m.id, MessageStatus.WAITING); return@launch }
            setStatus(pubkey, m.id, MessageStatus.DELIVERED)
            delay(1000)
            // the echo bot reads it and replies
            flow(pubkey).value.filter { it.outgoing && it.status == MessageStatus.DELIVERED }
                .forEach { setStatus(pubkey, it.id, MessageStatus.READ) }
            val reply = add(pubkey, me, "Echo: $text")
            if (Backend.currentChat.value != OpenChat(profileId, pubkey)) unread[pubkey] = (unread[pubkey] ?: 0) + 1
            refresh()
            events.tryEmit(ClientEvent.Incoming(contactOf(p), reply))
        }
        return MessageStatus.SENDING
    }

    override fun markRead(pubkey: String) {
        if ((unread[pubkey] ?: 0) != 0) { unread[pubkey] = 0; refresh() }
    }

    override suspend fun setTrust(pubkey: String, trust: Trust) { person(pubkey).trust = trust; refresh() }

    override suspend fun setNickname(pubkey: String, nickname: String?) {
        person(pubkey).nickname = nickname?.takeIf { it.isNotBlank() }; refresh()
    }

    override suspend fun syncNow() { delay(700); events.tryEmit(ClientEvent.Log("Synced")) }
}

/** A trivial in-memory GroupsApi: one group, one pending invite; actions just succeed. */
class FakeGroups(private val me: String) : GroupsApi {
    private fun member(name: String, owner: Boolean = false, you: Boolean = false) =
        GroupMember((name + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP").take(43), name, owner, you)
    private val msgs = HashMap<String, MutableStateFlow<List<GroupMessage>>>()
    private val now get() = System.currentTimeMillis() / 1000.0

    override val groups = MutableStateFlow(listOf(
        GroupInfo("g-invite", "Hiking", "x", "Bob", listOf(member("Bob", true), member("You", you = true)), GroupHistory.PRIVATE, false, true),
        GroupInfo("g-devs", "Lichen devs", me, "You", listOf(member("You", true, true), member("Alice")), GroupHistory.SHARED, true, false),
    ))

    override fun messages(groupId: String): StateFlow<List<GroupMessage>> =
        synchronized(msgs) { msgs.getOrPut(groupId) { MutableStateFlow(emptyList()) } }

    private fun ok(id: String = "") = GroupResult.Ok(id)
    override suspend fun create(name: String, members: List<String>, history: GroupHistory): GroupResult {
        val g = GroupInfo("g-$name", name, me, "You", listOf(member("You", true, true)), history, true, false)
        groups.update { it + g }
        return ok(g.id)
    }
    override suspend fun accept(groupId: String): GroupResult { groups.update { l -> l.map { if (it.id == groupId) it.copy(pending = false) else it } }; return ok(groupId) }
    override suspend fun decline(groupId: String): GroupResult { groups.update { l -> l.filter { it.id != groupId } }; return ok(groupId) }
    override suspend fun leave(groupId: String, newOwner: String?) = decline(groupId)
    override suspend fun addMember(groupId: String, pubkey: String) = ok(groupId)
    override suspend fun removeMember(groupId: String, pubkey: String) = ok(groupId)
    override suspend fun transfer(groupId: String, newOwner: String) = ok(groupId)
    override suspend fun delete(groupId: String) = decline(groupId)
    override suspend fun setHistory(groupId: String, history: GroupHistory): GroupResult {
        groups.update { l -> l.map { if (it.id == groupId) it.copy(history = history) else it } }; return ok(groupId)
    }
    override suspend fun send(groupId: String, text: String): GroupResult {
        (messages(groupId) as MutableStateFlow).update { it + GroupMessage("m${it.size}", groupId, me, "You", now, text, true) }
        return ok(groupId)
    }
    override suspend fun sync(groupId: String?) = 0
    override fun markRead(groupId: String) {}
}
