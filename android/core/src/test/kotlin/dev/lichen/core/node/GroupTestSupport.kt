package dev.lichen.core.node

import dev.lichen.core.api.GroupInfo
import dev.lichen.core.api.GroupResult
import dev.lichen.core.asString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertTrue

// Group helpers for the integration tests: the Python side (py_peer.py's g* commands) and the Kotlin side.

fun PyPeer.gcreate(who: String, name: String, members: List<String>, history: String = "private"): String =
    call("group", "cmd" to "gcreate", "who" to who, "name" to name, "members" to members, "history" to history)["group_id"].asString()!!

fun PyPeer.gsend(who: String, group: String, text: String): JsonObject =
    call("gsent", "cmd" to "gsend", "who" to who, "group" to group, "text" to text)

fun PyPeer.gaccept(who: String, group: String): Int = call("gaccepted", "cmd" to "gaccept", "who" to who, "group" to group)["n"].toString().toInt()

fun PyPeer.gdo(cmd: String, who: String, group: String, vararg more: Pair<String, Any?>): JsonObject =
    call("ok", "cmd" to cmd, "who" to who, "group" to group, *more)

fun PyPeer.glist(who: String): List<JsonObject> = (call("glist", "cmd" to "glist", "who" to who)["groups"] as JsonArray).map { it as JsonObject }

fun PyPeer.gentry(who: String, group: String): JsonObject? = glist(who).firstOrNull { it["group_id"].asString() == group }

fun PyPeer.gstatus(who: String, group: String): String? = gentry(who, group)?.get("status").asString()

fun PyPeer.gtexts(who: String, group: String): List<String> =
    (call("gmessages", "cmd" to "gmessages", "who" to who, "group" to group)["messages"] as JsonArray).map { (it as JsonObject)["text"].asString()!! }

fun PyPeer.gmembers(who: String, group: String): List<String> = (gentry(who, group)!!["members"] as JsonArray).map { it.asString()!! }

fun PyPeer.awaitGroupMessage(text: String, node: String): JsonObject =
    await("group message '$text' at $node", 0) { it["event"].asString() == "group_message" && it["text"].asString() == text && it["node"].asString() == node }

fun GroupResult.ok(): GroupResult.Ok {
    assertTrue(this is GroupResult.Ok, "expected Ok but got $this")
    return this as GroupResult.Ok
}

val Phone.groups get() = client.groups

fun Phone.group(id: String): GroupInfo? = groups.groups.value.firstOrNull { it.id == id }

fun Phone.groupTexts(id: String): List<String> = groups.messages(id).value.map { it.text }

fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }
