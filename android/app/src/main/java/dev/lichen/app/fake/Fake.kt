package dev.lichen.app.fake

import dev.lichen.app.Backend
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
        file(dataDir).writeText((listOf(settings.name) + settings.hubs).joinToString("\n"))
    }

    override fun create(dataDir: File, settings: Settings) = saveSettings(dataDir, settings)

    override fun import(dataDir: File, exportText: String, passphrase: String): SetupResult {
        if (exportText.isBlank()) return SetupResult.Failed("That file is empty.")
        if (passphrase == "wrong") return SetupResult.Failed("Wrong passphrase.")
        val s = Settings("Afrika", listOf("wss://hub.example.org", "wss://backup.example.net"))
        saveSettings(dataDir, s)
        return SetupResult.Ok(s)
    }

    override fun open(dataDir: File): ChatClient = FakeChatClient(
        settings(dataDir) ?: Settings("Me", listOf("wss://hub.example.org")),
    )
}

class FakeChatClient(private val settings: Settings) : ChatClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val me = "meKEY0000000000000000000000000000000000000"

    private class Person(val pubkey: String, val name: String, var trust: Trust, var online: Boolean, var nickname: String? = null)

    private val alice = "aLiCe1234567890abcdefghijklmnopqrstuvwxyz0"
    private val bob = "bOb987654321zyxwvutsrqponmlkjihgfedcba0000"
    private val carol = "cArOl5555aaaa6666bbbb7777cccc8888dddd99990"
    private val mal = "mAlLoRy99998888777766665555444433332222110"

    private val everyone = listOf(
        Person(alice, "Alice", Trust.TRUSTED, true),
        Person(bob, "Bob", Trust.TRUSTED, false),
        Person(carol, "Carol", Trust.TRUSTED, false, "Carol (work)"),
        Person("dAvE1111eeee2222ffff3333gggg4444hhhh55550", "Dave", Trust.TRUSTED, true),
        Person(mal, "Mallory", Trust.UNKNOWN, false),
        Person("eVe00001111222233334444555566667777888899", "Eve", Trust.BLOCKED, false),
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
    )

    @Synchronized
    private fun refresh() {
        profile.value = Profile(me, settings.name, settings.hubs, "meKE Y000 0000 0000", "d3v1c3-fake")
        contacts.value = everyone.filter { it.trust != Trust.BLOCKED }
            .map { contactOf(it) }
            .filter { it.lastMessage != null }
            .sortedByDescending { it.lastMessage?.ts ?: 0.0 }
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
            if (p.name == "Bob") { setStatus(pubkey, m.id, MessageStatus.WAITING); return@launch }
            setStatus(pubkey, m.id, MessageStatus.DELIVERED)
            delay(1000)
            // the echo bot reads it and replies
            flow(pubkey).value.filter { it.outgoing && it.status == MessageStatus.DELIVERED }
                .forEach { setStatus(pubkey, it.id, MessageStatus.READ) }
            val reply = add(pubkey, me, "Echo: $text")
            if (Backend.currentChat.value != pubkey) unread[pubkey] = (unread[pubkey] ?: 0) + 1
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
