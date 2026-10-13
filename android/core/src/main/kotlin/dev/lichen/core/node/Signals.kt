package dev.lichen.core.node

import dev.lichen.core.Canonical
import dev.lichen.core.asBool
import dev.lichen.core.asDouble
import dev.lichen.core.obj
import dev.lichen.core.seal
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

// Presence, typing and read receipts (node.py "presence, typing, read receipts"). Ephemeral signals, sealed
// like messages but never stored, synced or queued, and only exchanged with trusted contacts: strangers
// learn nothing about when you're around or what you've read. Receiving them is in Node.handle("signal").

const val PRESENCE_EVERY = 2   // keepalive ticks between "still here" signals to contacts (2 x 15 s)
const val TYPING_EVERY = 3.0   // at most one typing signal per contact per this many seconds

class Signals(private val node: Node) {
    private val typingSent = ConcurrentHashMap<String, Double>()
    private val readSent = ConcurrentHashMap<String, Double>()

    /** One cheap attempt: their first two addresses. No lookups, no outbox. */
    private suspend fun quick(pubkey: String, msg: JsonObject): JsonObject? {
        val rec = node.phonebook.get(pubkey)
        for (addr in rec?.addrs?.take(2) ?: emptyList()) {
            val reply = node.reqTo(addr, pubkey, msg) ?: continue
            if (reply["unreachable"].asBool() != true && reply["iou"].asBool() != true) return reply
        }
        return null
    }

    suspend fun signal(pubkey: String, fields: Map<String, kotlinx.serialization.json.JsonElement>): Boolean {
        if (!node.trust.trusted(pubkey)) return false
        val enc = node.phonebook.get(pubkey)?.enc ?: return false
        val unsigned = JsonObject(mapOf(
            "from" to JsonPrimitive(node.ident.pubkey), "to" to JsonPrimitive(pubkey), "ts" to Canonical.now(),
        ) + fields)
        val inner = JsonObject(unsigned + ("sig" to JsonPrimitive(node.ident.sign(Canonical.encode(unsigned)))))
        val reply = quick(pubkey, obj {
            put("type", JsonPrimitive("signal")); put("to", JsonPrimitive(pubkey)); put("sealed", seal(enc, Canonical.encode(inner)))
        })
        if (reply != null && reply["ok"].asBool() == true) {
            node.markSeen(pubkey)
            return true
        }
        return false
    }

    /** "Still here" to every contact; tells the app when someone we thought was online no longer is. */
    suspend fun presenceRound() {
        val contacts = node.trust.keys("trusted").filter { node.phonebook.get(it) != null }
        val was = contacts.associateWith { node.online(it) }
        coroutineScope { contacts.map { pk -> async { signal(pk, mapOf("signal" to JsonPrimitive("here"))) } }.awaitAll() }
        for (pk in contacts) if (was[pk] == true && !node.online(pk)) node.onEvent?.invoke("presence", pk, 0)
    }

    /** The user is typing to [pubkey]: tell them (at most every [TYPING_EVERY] seconds). */
    fun sendTyping(pubkey: String) {
        val now = Canonical.nowSeconds()
        if (now - (typingSent[pubkey] ?: 0.0) >= TYPING_EVERY) {
            typingSent[pubkey] = now
            node.launchTask { signal(pubkey, mapOf("signal" to JsonPrimitive("typing"))) }
        }
    }

    /** Tell [pubkey] we've read everything they sent so far (call when their chat is on screen). */
    fun sendRead(pubkey: String) {
        val newest = node.dms.messages(pubkey).filter { it["from"] == JsonPrimitive(pubkey) }.mapNotNull { it["ts"].asDouble() }.maxOrNull() ?: return
        if (newest > (readSent[pubkey] ?: 0.0)) {
            readSent[pubkey] = newest
            node.launchTask { signal(pubkey, mapOf("signal" to JsonPrimitive("read"), "upto" to Canonical.pyFloat(newest))) }
        }
    }
}
