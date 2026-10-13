package dev.lichen.core.store

import dev.lichen.core.EncKey
import dev.lichen.core.Identity
import dev.lichen.core.Py
import dev.lichen.core.asString
import dev.lichen.core.dumps
import dev.lichen.core.obj
import dev.lichen.core.parseObject
import dev.lichen.core.tmpDir
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** groups.json and device state are shared with Python byte for byte in format: each side must read what the other wrote. */
class GroupStoreTest {
    private fun py(script: String, dir: File): JsonObject {
        val out = Py.run(script, dumps(obj { put("dir", JsonPrimitive(dir.absolutePath.replace('\\', '/'))) }))
        return parseObject(out.trim().lines().last()) ?: error("python said: $out")
    }

    private val prelude = """
import time
from pathlib import Path
from lichen.identity import Identity
from lichen.crypto import EncKey
from lichen.groups import GroupStore
d = Path(json.loads(sys.stdin.read())["dir"])
def who(n):
    return Identity.load_or_create(d / (n + "_key")), EncKey.load_or_create(d / (n + "_enckey"))
"""

    private fun who(dir: File, n: String) = Identity.loadOrCreate(File(dir, "${n}_key")) to EncKey.loadOrCreate(File(dir, "${n}_enckey"))

    @Test
    fun pythonWrittenFileLoadsInKotlinAndBobCanReadIt() {
        Py.require()
        val dir = tmpDir()
        val out = py(prelude + """
a, aenc = who("a"); b, benc = who("b")
store = GroupStore(a, aenc, d / "groups.json")
rec = store.build_record("py group é", [b.pubkey], {b.pubkey: benc.pub}, None, {"history": "shared"})
store.set_status(rec.group_id, "accepted"); store.merge(rec)
store.add_message(store.seal_message(rec.group_id, "hello é\U0001F600"))
rec2 = store.build_record("py group é", [b.pubkey], {b.pubkey: benc.pub}, rec, {"history": "private"}); store.merge(rec2)
store.add_message(store.seal_message(rec.group_id, "second epoch"))
print(json.dumps({"gid": rec.group_id, "a": a.pubkey, "b": b.pubkey}))
""", dir)
        val gid = out["gid"].asString()!!
        val (alice, aenc) = who(dir, "a")
        assertEquals(out["a"].asString(), alice.pubkey)
        val store = GroupStore(alice, aenc, File(dir, "groups.json"))
        val rec = store.get(gid)!!
        assertTrue(rec.valid())
        assertEquals("py group é", rec.name)
        assertEquals("private", rec.history)
        assertEquals(2, rec.epoch)
        assertEquals("accepted", store.status(gid))
        assertTrue(store.joined(gid))
        assertEquals(listOf("hello é😀", "second epoch"), store.messages(gid).map { it["text"].asString() })
        assertEquals(setOf(1, 2), store.keysOf(gid).keys)
        assertEquals(setOf(1, 2), store.epochsOf(gid).keys)

        // Bob (Kotlin) takes the record and the log from Alice's device state and reads everything.
        val (bob, benc) = who(dir, "b")
        val bobStore = GroupStore(bob, benc)
        assertTrue(bobStore.merge(rec))
        assertEquals(setOf(1, 2), bobStore.keysOf(gid).keys)
        val log = (store.deviceState()[gid] as JsonObject)["log"] as JsonArray
        assertEquals(2, log.count { bobStore.addMessage(it as JsonObject) != null })
        assertEquals(listOf("hello é😀", "second epoch"), bobStore.messages(gid).map { it["text"].asString() })
        // a tampered ciphertext id is rejected, a duplicate is ignored
        assertEquals(null, bobStore.addMessage(log[0] as JsonObject))
        assertFalse(bobStore.merge(rec))
    }

    @Test
    fun kotlinWrittenFileLoadsInPythonAndBobCanReadIt() {
        Py.require()
        val dir = tmpDir()
        val bobKeys = py(prelude + """
b, benc = who("b")
print(json.dumps({"b": b.pubkey, "benc": benc.pub}))
""", dir)
        val (kate, kenc) = who(dir, "k")
        val store = GroupStore(kate, kenc, File(dir, "groups.json"))
        val rec = store.buildRecord("café ☕", listOf(bobKeys["b"].asString()!!), mapOf(bobKeys["b"].asString()!! to bobKeys["benc"].asString()!!),
            null, obj { put("history", JsonPrimitive("private")) })
        store.setStatus(rec.groupId, "accepted")
        assertTrue(store.merge(rec))
        store.addMessage(store.sealMessage(rec.groupId, "from kotlin 😀\n\"quoted\" \\ done"))
        Thread.sleep(5)
        store.addMessage(store.sealMessage(rec.groupId, "second", mapOf("reply_to" to JsonPrimitive("abc"))))
        store.save()

        val out = py(prelude + """
k, kenc = who("k"); b, benc = who("b")
store = GroupStore(k, kenc, d / "groups.json")
gid = next(iter(store.groups))
rec = store.get(gid)
bob = GroupStore(b, benc)
merged = bob.merge(rec)
read = [bob.add_message(m["outer"]) is not None for m in store.logs[gid].values()]
print(json.dumps({"valid": rec.valid(), "name": rec.name, "status": store.status[gid], "joined": store.joined(gid),
                  "texts": [m["text"] for m in store.messages(gid)], "merged": merged, "bob_read": read,
                  "bob_texts": [m["text"] for m in bob.messages(gid)], "extra": store.messages(gid)[1].get("reply_to"),
                  "private": store.messages(gid)[0]["private"]}))
""", dir)
        assertEquals("true", out["valid"].toString())
        assertEquals("café ☕", out["name"].asString())
        assertEquals("accepted", out["status"].asString())
        assertEquals("true", out["joined"].toString())
        val texts = listOf("from kotlin 😀\n\"quoted\" \\ done", "second")
        assertEquals(texts, (out["texts"] as JsonArray).map { it.asString() })
        assertEquals("true", out["merged"].toString())
        assertEquals("[true,true]", out["bob_read"].toString())
        assertEquals(texts, (out["bob_texts"] as JsonArray).map { it.asString() })
        assertEquals("abc", out["extra"].asString())
        assertEquals("true", out["private"].toString())
    }

    @Test
    fun deviceStateRoundTripsBothWays() {
        Py.require()
        val dir = tmpDir()
        py(prelude + """
who("b"); print("{}")
""", dir)
        val bobKeys = py(prelude + """
b, benc = who("b"); print(json.dumps({"b": b.pubkey, "benc": benc.pub}))
""", dir)
        val (kate, kenc) = who(dir, "k")
        val store = GroupStore(kate, kenc)
        val rec = store.buildRecord("devices", listOf(bobKeys["b"].asString()!!), mapOf(bobKeys["b"].asString()!! to bobKeys["benc"].asString()!!),
            null, obj { put("history", JsonPrimitive("shared")) })
        store.setStatus(rec.groupId, "accepted")
        store.merge(rec)
        store.addMessage(store.sealMessage(rec.groupId, "one"))
        store.addMessage(store.sealMessage(rec.groupId, "two"))
        File(dir, "kotlin_state.json").writeText(dumps(store.deviceState()), Charsets.UTF_8)

        // Another Python device of Kate's absorbs it, adds a message, and hands its own state back.
        val out = py(prelude + """
k, kenc = who("k")
other = GroupStore(k, kenc)
changed = other.merge_device_state(json.loads((d / "kotlin_state.json").read_text(encoding="utf-8")))
gid = next(iter(other.groups))
other.add_message(other.seal_message(gid, "three from the other device"))
(d / "py_state.json").write_text(json.dumps(other.device_state()), encoding="utf-8")
print(json.dumps({"changed": changed, "texts": [m["text"] for m in other.messages(gid)], "status": other.status[gid],
                  "owner_keys": sorted(other.keys[gid]), "epochs": sorted(other.epochs[gid])}))
""", dir)
        assertEquals(listOf(rec.groupId), (out["changed"] as JsonArray).map { it.asString() })
        assertEquals(setOf("one", "two", "three from the other device"), (out["texts"] as JsonArray).map { it.asString() }.toSet())
        assertEquals("accepted", out["status"].asString())
        assertEquals("[1]", out["owner_keys"].toString())
        assertEquals("[1]", out["epochs"].toString())

        val back = parseObject(File(dir, "py_state.json").readText(Charsets.UTF_8))!!
        val changed = store.mergeDeviceState(back)
        assertEquals(emptyList(), changed.filter { it != rec.groupId })
        assertEquals(setOf("one", "two", "three from the other device"), store.messages(rec.groupId).map { it["text"].asString() }.toSet())

        // A fresh Kotlin device of Kate's gets all of it, status and keys included.
        val fresh = GroupStore(kate, kenc)
        assertEquals(listOf(rec.groupId), fresh.mergeDeviceState(store.deviceState()))
        assertEquals("accepted", fresh.status(rec.groupId))
        assertEquals(3, fresh.messages(rec.groupId).size)
        assertEquals(setOf(1), fresh.keysOf(rec.groupId).keys)
        assertEquals(emptyList(), fresh.mergeDeviceState(store.deviceState()))  // nothing new the second time
    }

    @Test
    fun deletedStatusWinsAndWipesKeys() {
        val a = Identity.generate(); val aenc = EncKey.generate()
        val b = Identity.generate(); val benc = EncKey.generate()
        val s1 = GroupStore(a, aenc)
        val rec = s1.buildRecord("x", listOf(b.pubkey), mapOf(b.pubkey to benc.pub), null, obj { put("history", JsonPrimitive("private")) })
        s1.setStatus(rec.groupId, "accepted"); s1.merge(rec)
        s1.addMessage(s1.sealMessage(rec.groupId, "hi"))
        val s2 = GroupStore(a, aenc)
        s2.mergeDeviceState(s1.deviceState())
        assertEquals(1, s2.messages(rec.groupId).size)
        s1.purge(rec.groupId)
        s2.mergeDeviceState(s1.deviceState())
        assertEquals("deleted", s2.status(rec.groupId))
        assertEquals(0, s2.messages(rec.groupId).size)
        assertTrue(s2.keysOf(rec.groupId).isEmpty())
    }
}
