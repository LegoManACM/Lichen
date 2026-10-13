package dev.lichen.core.node

import dev.lichen.core.Py
import dev.lichen.core.api.ChatMessage
import dev.lichen.core.api.ClientEvent
import dev.lichen.core.api.Settings
import dev.lichen.core.asString
import dev.lichen.core.dumps
import dev.lichen.core.parseObject
import dev.lichen.core.FileSetup
import dev.lichen.core.tmpDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Poll [cond] until it holds; fails with [what] after [timeoutMs]. */
fun eventually(what: String = "condition", timeoutMs: Long = 20_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (try { cond() } catch (e: Exception) { false }) return
        Thread.sleep(50)
    }
    throw AssertionError("timed out waiting for $what")
}

/** android/tools/py_peer.py running as a subprocess: a real Python hub, optionally with an attached Python user. */
class PyPeer(withUser: Boolean, val port: Int = freePort()) : AutoCloseable {
    private val dir = tmpDir()
    private val process: Process
    private val events = CopyOnWriteArrayList<JsonObject>()
    private val stderr = StringBuffer()
    val hubAddr = "127.0.0.1:$port"
    val wsAddr = "ws://127.0.0.1:$port"
    lateinit var hubPk: String
    var userPk: String? = null

    init {
        Py.require()
        val script = File(Py.repo, "android/tools/py_peer.py")
        val cmd = mutableListOf(Py.exe!!, "-X", "utf8", script.absolutePath, "--dir", dir.absolutePath, "--port", port.toString())
        if (withUser) cmd += listOf("--user", "alice")
        process = ProcessBuilder(cmd).start()
        Thread { process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { l -> parseObject(l)?.let { events.add(it) } } }.also { it.isDaemon = true }.start()
        Thread { process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { stderr.append(it).append('\n') } }.also { it.isDaemon = true }.start()
        val ready = try { await("ready", 0) { it["event"].asString() == "ready" } } catch (e: AssertionError) { close(); throw AssertionError("${e.message}\n$stderr") }
        hubPk = (ready["hub"] as JsonObject)["pubkey"].asString()!!
        userPk = (ready["user"] as? JsonObject)?.get("pubkey").asString()
    }

    /** First event after index [from] that matches. */
    fun await(what: String, from: Int, timeoutMs: Long = 20_000, pred: (JsonObject) -> Boolean): JsonObject {
        var found: JsonObject? = null
        try {
            eventually(what, timeoutMs) { found = events.drop(from).firstOrNull(pred); found != null }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}\nevents: ${events.joinToString("\n") { dumps(it) }}\npython stderr:\n$stderr")
        }
        return found!!
    }

    /** Send a command and return the reply event named [reply]. */
    fun call(reply: String, vararg fields: Pair<String, Any?>, timeoutMs: Long = 20_000): JsonObject {
        val from = events.size
        val o = JsonObject(fields.associate { (k, v) -> k to toJson(v) })
        process.outputStream.write((dumps(o) + "\n").toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
        return await("$reply after ${o["cmd"]}", from, timeoutMs) { it["event"].asString() == reply || it["event"].asString() == "error" }
            .also { if (it["event"].asString() == "error") throw AssertionError("python: ${it["error"].asString()}") }
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Collection<*> -> kotlinx.serialization.json.JsonArray(v.map { toJson(it) })
        else -> error("unsupported $v")
    }

    fun send(from: String, to: String, text: String): String? =
        call("sent", "cmd" to "send", "from" to from, "to" to to, "text" to text)["result"].asString()

    fun state(): JsonObject = call("state", "cmd" to "state")

    fun hubAttached(): Set<String> = ((state()["hub_attached"]) as JsonObject).keys

    /** Python received a message with this text (a `message` event). */
    fun received(text: String): JsonObject? = events.firstOrNull { it["event"].asString() == "message" && it["text"].asString() == text }

    fun awaitReceived(text: String, timeoutMs: Long = 20_000): JsonObject {
        await("python to receive '$text'", 0, timeoutMs) { it["event"].asString() == "message" && it["text"].asString() == text }
        return received(text)!!
    }

    override fun close() {
        try { process.outputStream.write("{\"cmd\":\"quit\"}\n".toByteArray()); process.outputStream.flush() } catch (_: Exception) {}
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

/** A Kotlin NodeClient in a fresh folder (or [dir] again), plus a recorder of its events. */
class Phone(val hubs: List<String>, val dir: File = tmpDir(), name: String = "kate") {
    private val setup = FileSetup()
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val events = CopyOnWriteArrayList<ClientEvent>()
    var client: NodeClient
    val pubkey: String

    init {
        if (!setup.hasIdentity(dir)) setup.create(dir, Settings(name, hubs))
        client = open()
        pubkey = dev.lichen.core.Identity.loadOrCreate(File(dir, "key")).pubkey
    }

    fun open(): NodeClient = (setup.open(dir) as NodeClient).also { c ->
        val flow: SharedFlow<ClientEvent> = c.events
        scope.launch { flow.collect { events.add(it) } }
    }

    fun reopen() { client = open() }

    fun incoming(): List<ClientEvent.Incoming> = events.filterIsInstance<ClientEvent.Incoming>()

    fun chat(pk: String): List<ChatMessage> = client.messages(pk).value

    fun close() {
        kotlinx.coroutines.runBlocking { client.stop() }
        scope.cancel()
    }
}
