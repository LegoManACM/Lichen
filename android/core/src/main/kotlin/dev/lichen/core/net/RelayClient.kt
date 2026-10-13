package dev.lichen.core.net

import dev.lichen.core.Canonical
import dev.lichen.core.Identity
import dev.lichen.core.asBool
import dev.lichen.core.obj
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Node side of relay.py: keep an attached connection to a hub open, reconnecting (1 s, doubling to 30 s)
 * when it drops, and answer every `{"rid","msg"}` the hub pushes down it with [handler].
 *
 * Attach: `{"type":"attach","req":{"pubkey","ts","device"},"sig"}` -> `{"ok":true}`; then keepalive
 * `{"keepalive": ts}` every [KEEPALIVE_MS].
 */
class RelayClient(
    val relay: String,
    private val ident: Identity,
    private val device: String,
    private val handler: suspend (JsonObject) -> JsonObject,
    private val log: (String) -> Unit = {},
    private val dial: suspend (String) -> Conn = ::connect,
    private val keepaliveMs: Long = KEEPALIVE_MS,
) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private var job: Job? = null
    @Volatile private var conn: Conn? = null
    @Volatile private var attached = false  // the current/last session got as far as a successful attach

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        conn?.close()
        _connected.value = false
    }

    private suspend fun run() {
        var delayMs = 1_000L
        while (true) {
            try {
                attached = false
                session()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {  // network errors, refused attach: retry
                log("relay $relay: ${e.message ?: e.javaClass.simpleName}")
            }
            _connected.value = false
            if (attached) delayMs = 1_000L
            delay(delayMs)
            delayMs = minOf(delayMs * 2, 30_000L)
        }
    }

    /** One connection, from dial to drop. */
    private suspend fun session() {
        val c = dial(relay)
        conn = c
        val sess = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            val req = obj {
                put("pubkey", JsonPrimitive(ident.pubkey))
                put("ts", Canonical.now())
                put("device", JsonPrimitive(device))
            }
            c.send(obj {
                put("type", JsonPrimitive("attach"))
                put("req", req)
                put("sig", JsonPrimitive(ident.sign(Canonical.encode(req))))
            })
            val ack = withTimeoutOrNull(10_000) { c.recv() } ?: throw IOException("no answer to attach")
            if (ack["ok"].asBool() != true) throw IOException("relay refused attach: ${(ack["error"] as? JsonPrimitive)?.content}")
            _connected.value = true
            attached = true
            log("attached to relay $relay")
            sess.launch {
                while (true) {
                    delay(keepaliveMs)
                    try { c.send(obj { put("keepalive", Canonical.now()) }) } catch (e: IOException) { return@launch }
                }
            }
            while (true) {
                val data = c.recv() ?: break
                val rid = data["rid"]
                val msg = data["msg"]
                sess.launch {
                    val reply = if (msg is JsonObject) {
                        try { handler(msg) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            obj { put("ok", JsonPrimitive(false)); put("error", JsonPrimitive("internal error")) }
                        }
                    } else obj { put("ok", JsonPrimitive(false)); put("error", JsonPrimitive("bad request")) }
                    try {
                        c.send(obj { put("rid", rid ?: JsonPrimitive(0)); put("reply", reply) })
                    } catch (e: IOException) { /* connection went away */ }
                }
            }
            log("relay $relay closed the connection")
        } finally {
            sess.cancel()
            c.close()
            if (conn === c) conn = null
        }
    }

    companion object {
        const val KEEPALIVE_MS = 25_000L
    }
}
