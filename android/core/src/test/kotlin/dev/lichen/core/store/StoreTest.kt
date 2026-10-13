package dev.lichen.core.store

import dev.lichen.core.Canonical
import dev.lichen.core.Identity
import dev.lichen.core.Py
import dev.lichen.core.Record
import dev.lichen.core.EncKey
import dev.lichen.core.Vectors
import dev.lichen.core.a
import dev.lichen.core.asString
import dev.lichen.core.dumps
import dev.lichen.core.o
import dev.lichen.core.parseJson
import dev.lichen.core.seal
import dev.lichen.core.str
import dev.lichen.core.tmpDir
import dev.lichen.core.verify
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val V get() = Vectors.obj("stores")

/** A fresh copy of the Python-written sample data folder. */
private fun sampleDir(): File = tmpDir().also { d ->
    for ((name, text) in V["files"].o()) File(d, name).writeText(text.str())
}

private fun canon(e: JsonElement) = Canonical.encode(e).toString(Charsets.US_ASCII)
private fun fileJson(f: File) = canon(parseJson(f.readText())!!)
private fun obj(vararg p: Pair<String, JsonElement>) = JsonObject(mapOf(*p))

private fun signedBy(id: Identity, to: String, ts: Double, text: String, mid: String): JsonObject {
    val inner = obj("from" to JsonPrimitive(id.pubkey), "to" to JsonPrimitive(to), "ts" to Canonical.pyFloat(ts),
        "id" to JsonPrimitive(mid), "text" to JsonPrimitive(text))
    return JsonObject(inner + ("sig" to JsonPrimitive(id.sign(Canonical.encode(inner)))))
}

class StoreRoundTripTest {
    @Test fun `loading and saving Python's files changes nothing`() {
        val d = sampleDir()
        Phonebook(File(d, "phonebook.json")).save(); DmStore(File(d, "dms.json")).save()
        TrustStore(File(d, "trust.json")).save(); Outbox(File(d, "outbox.json")).save()
        for ((name, text) in V["files"].o()) assertEquals(canon(parseJson(text.str())!!), fileJson(File(d, name)), name)
    }

    @Test fun `stored messages still verify`() {
        val dms = DmStore(File(sampleDir(), "dms.json"))
        var n = 0
        for (peer in dms.peers()) for (m in dms.messages(peer)) {
            val sig = m["sig"].asString()!!
            assertTrue(verify(m["from"].asString()!!, Canonical.encode(JsonObject(m - "sig")), sig), m.toString())
            n++
        }
        assertEquals(5, n)
    }

    @Test fun `device state is byte-identical to Python's`() {
        val d = sampleDir()
        val dms = DmStore(File(d, "dms.json"))
        assertEquals(canon(V["dms_state"]!!), canon(dms.deviceState()))
        assertEquals(canon(V["dms_state_keep2"]!!), canon(dms.deviceState(2)))
        assertEquals(canon(V["trust_state"]!!), canon(TrustStore(File(d, "trust.json")).deviceState()))
        assertEquals(canon(V["outbox_state"]!!), canon(Outbox(File(d, "outbox.json")).deviceState()))
    }

    @Test fun `merging another device's state gives Python's result`() {
        val d = sampleDir()
        val dms = DmStore(File(d, "dms.json")); val trust = TrustStore(File(d, "trust.json")); val ob = Outbox(File(d, "outbox.json"))
        val merged = V["merged"].o()
        var fired = 0
        dms.onChange = { fired++ }
        assertEquals(merged["dms_new"].toString().toInt(), dms.mergeDeviceState(V["remote_dms"], V["me"].str()))
        assertEquals(merged["trust_changed"].toString().toInt(), trust.mergeDeviceState(V["remote_trust"]))
        assertEquals(merged["outbox_changed"].toString().toInt(), ob.mergeDeviceState(V["remote_outbox"]))
        assertEquals(0, fired)  // a merge must not echo back to the device it came from
        assertEquals(canon(merged["dms"]!!), fileJson(File(d, "dms.json")))
        assertEquals(canon(merged["trust"]!!), fileJson(File(d, "trust.json")))
        assertEquals(canon(merged["outbox"]!!), fileJson(File(d, "outbox.json")))
        // merging the same state again is a no-op
        assertEquals(0, dms.mergeDeviceState(V["remote_dms"], V["me"].str()))
        assertEquals(0, trust.mergeDeviceState(V["remote_trust"]))
    }

    @Test fun `Python loads what Kotlin saved and agrees on the state`() {
        Py.require()
        val d = sampleDir()
        val dms = DmStore(File(d, "dms.json")); val trust = TrustStore(File(d, "trust.json")); val ob = Outbox(File(d, "outbox.json"))
        val pb = Phonebook(File(d, "phonebook.json"))
        // change things from the Kotlin side
        val me = V["me"].str()
        val bob = Identity.generate(); val enc = EncKey.generate()
        pb.merge(Record.create(bob, "bobé 😀", listOf("relay:h:1"), enc.pub))
        dms.add(bob.pubkey, signedBy(bob, me, 1728750900.25, "kotlin 😀 \"q\"\n", "kid-1"))
        dms.markDelivered(bob.pubkey, "kid-1"); dms.markRead(bob.pubkey, 1728750900.25)
        trust.set(bob.pubkey, "trusted"); trust.setNickname(bob.pubkey, "kbob"); trust.setVerified(bob.pubkey, 1728750901.5)
        ob.add(bob.pubkey, obj("type" to JsonPrimitive("msg"), "sealed" to seal(enc.pub, "queued".toByteArray())), "kitem")
        ob.add(bob.pubkey, obj("type" to JsonPrimitive("msg")), "kitem2"); ob.remove("kitem2")
        val expect = obj("dms" to dms.deviceState(), "trust" to trust.deviceState(), "outbox" to ob.deviceState())
        val out = Py.run("""
from pathlib import Path
from lichen.dms import DmStore
from lichen.trust import TrustStore
from lichen.outbox import Outbox
from lichen.phonebook import Phonebook
from lichen.identity import canonical
c = json.load(sys.stdin)
d = Path(c["dir"])
got = {"dms": DmStore(d / "dms.json").device_state(), "trust": TrustStore(d / "trust.json").device_state(),
       "outbox": Outbox(d / "outbox.json").device_state()}
print(canonical(got) == c["expect"].encode(), len(Phonebook(d / "phonebook.json").all()),
      DmStore(d / "dms.json").receipt(c["bob"], {"id": "kid-1", "ts": 2e9}))
""", dumps(obj("dir" to JsonPrimitive(d.absolutePath), "expect" to JsonPrimitive(canon(expect)), "bob" to JsonPrimitive(bob.pubkey))))
        assertEquals("True 4 delivered", out.trim())
    }
}

class DmStoreTest {
    private val me = Identity.generate(); private val bob = Identity.generate()

    @Test fun `adds once, orders by timestamp, keeps every signed field`() {
        val s = DmStore()
        val late = signedBy(bob, me.pubkey, 20.0, "late", "b")
        val early = JsonObject(signedBy(bob, me.pubkey, 10.0, "early", "a") + ("reply" to JsonPrimitive("b")))
        assertTrue(s.add(bob.pubkey, late)); assertTrue(s.add(bob.pubkey, early))
        assertFalse(s.add(bob.pubkey, late))
        assertEquals(listOf("a", "b"), s.ids(bob.pubkey))
        assertEquals("b", s.messages(bob.pubkey)[0]["reply"].asString())
        assertTrue(s.has(bob.pubkey, "a")); assertFalse(s.has(bob.pubkey, "zz"))
        assertEquals(listOf("b"), s.missingFor(bob.pubkey, setOf("a")).map { it["id"].asString() })
        assertEquals(listOf("b"), s.missingFor(bob.pubkey, emptySet(), limit = 1).map { it["id"].asString() })  // newest wins the limit
    }

    @Test fun `history limit drops the oldest first`() {
        val s = DmStore()
        for (i in 0 until HISTORY_LIMIT + 5) s.add("p", obj("id" to JsonPrimitive("m$i"), "ts" to Canonical.pyFloat(i.toDouble()), "from" to JsonPrimitive("p")))
        assertEquals(HISTORY_LIMIT, s.messages("p").size)
        assertFalse(s.has("p", "m0")); assertTrue(s.has("p", "m5")); assertTrue(s.add("p", obj("id" to JsonPrimitive("m0"), "ts" to Canonical.pyFloat(0.0))))
    }

    @Test fun `receipts`() {
        val s = DmStore(); var fired = 0; s.onChange = { fired++ }
        val m1 = signedBy(me, bob.pubkey, 5.0, "x", "i1"); val m2 = signedBy(me, bob.pubkey, 9.0, "y", "i2")
        assertNull(s.receipt(bob.pubkey, m1))
        s.markDelivered(bob.pubkey, "i1"); s.markDelivered(bob.pubkey, "i1")
        assertEquals("delivered", s.receipt(bob.pubkey, m1)); assertNull(s.receipt(bob.pubkey, m2))
        assertTrue(s.markRead(bob.pubkey, 6.0)); assertFalse(s.markRead(bob.pubkey, 6.0))
        assertEquals("read", s.receipt(bob.pubkey, m1)); assertNull(s.receipt(bob.pubkey, m2))
        assertEquals(2, fired)
    }

    @Test fun `old files without ids get Python's legacy ids`() {
        val f = File(tmpDir(), "dms.json")
        val legacy = Vectors.obj("legacy")
        f.writeText(legacy["file"].str())
        val s = DmStore(f)
        assertEquals(legacy["ids"].a().map { it.str() }.toSet(), s.ids("peerkey").toSet())
        assertEquals(4, s.messages("peerkey").size)
    }

    @Test fun `rejects a message without id`() {
        assertFailsWith<IllegalArgumentException> { DmStore().add("p", obj("ts" to JsonPrimitive(1))) }
    }

    @Test fun `merge only accepts messages between us and the peer`() {
        val s = DmStore()
        val ok = signedBy(bob, me.pubkey, 1.0, "ok", "ok1")
        val foreign = signedBy(bob, "someone-else", 1.0, "no", "no1")
        val st = obj("convos" to obj(bob.pubkey to kotlinx.serialization.json.JsonArray(listOf(ok, foreign, JsonPrimitive(3)))))
        assertEquals(1, s.mergeDeviceState(st, me.pubkey))
        assertEquals(listOf("ok1"), s.ids(bob.pubkey))
        assertEquals(0, s.mergeDeviceState(JsonPrimitive("junk"), me.pubkey))
    }

    @Test fun `is safe to use from many threads`() {
        val f = File(tmpDir(), "dms.json")
        val s = DmStore(f); val n = AtomicInteger()
        s.onChange = { n.incrementAndGet() }
        val start = CountDownLatch(1)
        val threads = (0 until 8).map { t -> thread { start.await(); for (i in 0 until 40) s.add("p$t", obj("id" to JsonPrimitive("t$t-$i"), "ts" to Canonical.pyFloat(i.toDouble()))); s.messages("p0"); s.deviceState() } }
        start.countDown(); threads.forEach { it.join() }
        assertEquals(320, n.get())
        assertEquals(320, DmStore(f).let { r -> r.peers().sumOf { r.messages(it).size } })
    }
}

class TrustStoreTest {
    @Test fun `levels, nicknames, verification`() {
        val t = TrustStore(); var fired = 0; t.onChange = { fired++ }
        assertEquals("unknown", t.get("a"))
        t.set("a", "trusted"); t.set("b", "blocked")
        assertTrue(t.trusted("a")); assertTrue(t.blocked("b"))
        t.set("a", "unknown"); assertEquals("unknown", t.get("a"))
        assertFailsWith<IllegalArgumentException> { t.set("a", "bogus") }
        t.setNickname("a", "Ann"); assertEquals("Ann", t.nickname("a"))
        assertFailsWith<IllegalArgumentException> { t.setNickname("b", "ann") }  // case-insensitive duplicate
        assertFailsWith<IllegalArgumentException> { t.setNickname("b", "has space") }
        assertFailsWith<IllegalArgumentException> { t.setNickname("b", "a#b") }
        t.setNickname("a", null); assertNull(t.nickname("a"))
        t.setVerified("a", 5.5); assertTrue(t.isVerified("a")); t.setVerified("a", null); assertFalse(t.isVerified("a"))
        assertEquals(7, fired)
    }

    @Test fun `reads the old levels-only format`() {
        val f = File(tmpDir(), "trust.json"); f.writeText("{\"k1\": \"trusted\", \"k2\": \"junk\"}")
        val t = TrustStore(f)
        assertTrue(t.trusted("k1")); assertEquals("unknown", t.get("k2"))
    }

    @Test fun `newer remote state wins, older loses`() {
        val t = TrustStore(); t.set("a", "trusted")
        val newer = Canonical.nowSeconds() + 100
        assertEquals(1, t.mergeDeviceState(obj("a" to obj("level" to JsonPrimitive("blocked"), "nickname" to JsonPrimitive("zed"), "changed" to Canonical.pyFloat(newer)))))
        assertTrue(t.blocked("a")); assertEquals("zed", t.nickname("a"))
        assertEquals(0, t.mergeDeviceState(obj("a" to obj("level" to JsonPrimitive("trusted"), "changed" to Canonical.pyFloat(1.0)))))
    }
}

class OutboxAndPhonebookTest {
    @Test fun `outbox queues, dedups, remembers what got through`() {
        val f = File(tmpDir(), "outbox.json")
        val o = Outbox(f); var fired = 0; o.onChange = { fired++ }
        val id = o.add("bob", obj("type" to JsonPrimitive("msg")))
        assertEquals(12, id.length)
        assertEquals(id, o.add("bob", obj("type" to JsonPrimitive("other")), id))  // same id: ignored
        assertEquals(1, o.size()); assertEquals(setOf("bob"), o.peers()); assertEquals(1, o.forPeer("bob").size); assertEquals(0, o.forPeer("eve").size)
        o.remove(id)
        assertEquals(0, o.size()); o.add("bob", obj(), id); assertEquals(0, o.size())  // done: not re-queued
        assertEquals(2, fired)
        assertEquals(0, Outbox(f).size())
    }

    @Test fun `outbox expires after a month`() {
        val f = File(tmpDir(), "outbox.json")
        f.writeText("{\"items\":{\"old\":{\"to\":\"x\",\"msg\":{},\"ts\":1.0},\"new\":{\"to\":\"x\",\"msg\":{},\"ts\":${Canonical.nowSeconds()}}},\"done\":{\"d1\":2.0}}")
        val o = Outbox(f)
        assertEquals(listOf("new"), o.forPeer().map { it.first })
        assertFalse("d1" in f.readText())
        // legacy {id: item} format
        f.writeText("{\"i\":{\"to\":\"x\",\"msg\":{},\"ts\":${Canonical.nowSeconds()}}}")
        assertEquals(listOf("i"), Outbox(f).forPeer().map { it.first })
    }

    @Test fun `phonebook keeps the newest valid record`() {
        val f = File(tmpDir(), "phonebook.json")
        val pb = Phonebook(f); var fired = 0; pb.onChange = { fired++ }
        val id = Identity.generate(); val enc = EncKey.generate()
        val a = Record.create(id, "ann", listOf("relay:h:1"), enc.pub)
        val b = Record.create(id, "ann2", listOf("relay:h:2"), enc.pub, prevSeq = a.seq)
        assertTrue(pb.merge(a)); assertFalse(pb.merge(a)); assertTrue(pb.merge(b)); assertFalse(pb.merge(a))
        assertFalse(pb.merge(a.copy(name = "forged", seq = b.seq + 5))); assertFalse(pb.merge(null))
        assertEquals("ann2", pb.get(id.pubkey)!!.name)
        assertEquals(2, fired)
        assertEquals(listOf(id.pubkey), pb.find("ann2").map { it.pubkey }); assertEquals(1, pb.find(id.pubkey.take(6)).size); assertEquals(0, pb.find("zzz").size)
        assertEquals(b, Phonebook(f).get(id.pubkey))
        val c = Record.create(Identity.generate(), "c", emptyList(), enc.pub)
        assertEquals(1, pb.mergeAll(listOf(c, a, null)))
        assertEquals(2, Phonebook(f).all().size)
    }

    @Test fun `phonebook drops entries that do not verify when loading`() {
        val f = File(tmpDir(), "phonebook.json")
        val id = Identity.generate(); val enc = EncKey.generate()
        val good = Record.create(id, "good", listOf("h:1"), enc.pub)
        val bad = good.toJson().let { JsonObject(it + ("name" to JsonPrimitive("bad"))) }
        f.writeText("[" + dumps(good.toJson()) + "," + dumps(bad) + "]")
        assertEquals(listOf("good"), Phonebook(f).all().map { it.name })
    }
}
