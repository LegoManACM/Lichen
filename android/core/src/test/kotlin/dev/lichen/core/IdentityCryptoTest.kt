package dev.lichen.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityTest {
    @Test fun `fixed-seed identities match Python and sign identically`() {
        for (v in Vectors.arr("identities")) {
            val id = Identity.fromSeed(unb64(v.o()["seed"].str()))
            assertEquals(v.o()["pubkey"].str(), id.pubkey)
            assertEquals(v.o()["fingerprint"].str(), fingerprint(id.pubkey))
            assertEquals(v.o()["short"].str(), short(id.pubkey))
            assertContentEquals(unb64(v.o()["seed"].str()), id.seed())
            for (s in v.o()["signatures"].a()) {
                val data = unb64(s.o()["data"].str())
                assertEquals(s.o()["sig"].str(), id.sign(data))  // Ed25519 is deterministic
                assertTrue(verify(id.pubkey, data, s.o()["sig"].str()))
            }
        }
    }

    @Test fun `verify never throws and agrees with Python`() {
        for (v in Vectors.arr("verify")) {
            val o = v.o()
            assertEquals(o["valid"].toString().toBoolean(), verify(o["pubkey"].str(), unb64(o["data"].str()), o["sig"].str()), o.toString())
        }
    }

    @Test fun `safety codes match Python`() {
        for (v in Vectors.arr("safety")) assertEquals(v.o()["code"].str(), safetyCode(v.o()["a"].str(), v.o()["b"].str()))
    }

    @Test fun `base64 is url-safe, unpadded on output, tolerant on input`() {
        val data = byteArrayOf(-5, -1, -2, 63, 62, 0, 1)
        val s = b64(data)
        assertFalse('=' in s || '+' in s || '/' in s)
        assertContentEquals(data, unb64(s)); assertContentEquals(data, unb64(s + "=".repeat((4 - s.length % 4) % 4)))
    }

    @Test fun `Python verifies Kotlin signatures`() {
        val a = Identity.generate(); val b = Identity.generate()
        assertTrue(a.pubkey != b.pubkey)
        Py.require()
        val data = "héllo".toByteArray()
        val out = Py.run("""
from lichen.identity import verify
c = json.load(sys.stdin)
print(verify(c["pk"], c["data"].encode(), c["sig"]))
""", dumps(JsonObject(mapOf("pk" to JsonPrimitive(a.pubkey), "data" to JsonPrimitive("héllo"), "sig" to JsonPrimitive(a.sign(data))))))
        assertEquals("True", out.trim())
    }

    @Test fun `loadOrCreate persists the key`() {
        val f = createTempDirectory("lichen").toFile().resolve("key")
        val a = Identity.loadOrCreate(f)
        assertEquals(a.pubkey, Identity.loadOrCreate(f).pubkey)
        assertEquals(a.pubkey, Identity.fromSeed(readSecret(f.readText())).pubkey)
    }
}

class CryptoTest {
    @Test fun `opens Python sealed boxes`() {
        for (v in Vectors.obj("sealed")["valid"].a()) {
            val key = EncKey.fromSeed(unb64(v.o()["enc_seed"].str()))
            assertEquals(v.o()["enc_pub"].str(), key.pub)
            assertContentEquals(unb64(v.o()["plaintext"].str()), openSealed(key, v.o()["box"]))
        }
    }

    @Test fun `rejects bad boxes without throwing`() {
        val key = EncKey.fromSeed(unb64(Vectors.obj("sealed")["valid"].a()[0].o()["enc_seed"].str()))
        for (b in Vectors.obj("sealed")["invalid"].a()) assertNull(openSealed(key, b), b.toString())
        assertNull(openSealed(key, null))
    }

    @Test fun `seal and open round trip, and only the recipient can open`() {
        val me = EncKey.generate(); val other = EncKey.generate()
        val box = seal(me.pub, "secret 😀".toByteArray())
        assertEquals("secret 😀", openSealed(me, box)!!.toString(Charsets.UTF_8))
        assertNull(openSealed(other, box))
        assertEquals(setOf("epk", "n", "ct"), box.keys)
    }

    @Test fun `decrypts Python symmetric boxes with aad`() {
        for (v in Vectors.arr("aead")) {
            val key = unb64(v.o()["key"].str()); val aad = unb64(v.o()["aad"].str())
            assertContentEquals(unb64(v.o()["plaintext"].str()), decrypt(key, v.o()["box"], aad))
            assertNull(decrypt(key, v.o()["box"], aad + 1))
            assertNull(decrypt(ByteArray(32), v.o()["box"], aad))
        }
    }

    @Test fun `encrypt and decrypt round trip`() {
        val k = newKey(); assertEquals(32, k.size)
        val box = encrypt(k, "x".toByteArray(), "aad".toByteArray())
        assertEquals("x", decrypt(k, box, "aad".toByteArray())!!.toString(Charsets.UTF_8))
        assertNull(decrypt(k, box, "other".toByteArray()))
        assertNull(decrypt(k, JsonPrimitive("junk"), ByteArray(0)))
    }

    @Test fun `Python opens what Kotlin sealed and encrypted`() {
        Py.require()
        val seed = unb64(Vectors.obj("sealed")["valid"].a()[0].o()["enc_seed"].str())
        val pub = EncKey.fromSeed(seed).pub
        val key = ByteArray(32) { it.toByte() }
        val pt = ByteArray(3000) { (it * 7).toByte() }
        val input = JsonObject(mapOf(
            "seed" to JsonPrimitive(b64(seed)), "pt" to JsonPrimitive(b64(pt)), "key" to JsonPrimitive(b64(key)),
            "sealed" to seal(pub, pt), "sym" to encrypt(key, pt, "gid".toByteArray()),
            "symbad" to encrypt(key, pt, "other".toByteArray())))
        val out = Py.run("""
from lichen import crypto
from lichen.identity import unb64
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
c = json.load(sys.stdin)
k = crypto.EncKey(X25519PrivateKey.from_private_bytes(unb64(c["seed"])))
pt = unb64(c["pt"])
print(crypto.open_sealed(k, c["sealed"]) == pt, crypto.decrypt(unb64(c["key"]), c["sym"], b"gid") == pt,
      crypto.decrypt(unb64(c["key"]), c["symbad"], b"gid"))
""", dumps(input))
        assertEquals("True True None", out.trim())
    }
}

class RecordTest {
    @Test fun `accepts valid Python records and rejects tampered ones`() {
        for (v in Vectors.arr("records")) {
            val rec = Record.fromJson(v.o()["record"])
            val expect = v.o()["valid"].toString().toBoolean()
            assertEquals(expect, rec != null, v.o()["name"].str())
            if (rec != null) {
                assertTrue(rec.valid())
                // body() re-encodes to exactly the bytes Python signed, and toJson round-trips
                assertContentEquals(Canonical.encode(JsonObject(v.o()["record"].o() - "sig")), Canonical.encode(rec.body()))
                assertContentEquals(Canonical.encode(v.o()["record"]!!), Canonical.encode(rec.toJson()))
            }
        }
    }

    @Test fun `create makes valid increasing records`() {
        val id = Identity.generate(); val enc = EncKey.generate()
        val a = Record.create(id, "me", listOf("relay:h:1"), enc.pub)
        val b = Record.create(id, "me", listOf("relay:h:1"), enc.pub, prevSeq = a.seq + 10_000_000_000)
        assertTrue(a.valid()); assertEquals(a.seq + 10_000_000_001, b.seq)
        assertNotNull(Record.fromJson(a.toJson()))
        assertFalse(a.copy(name = "evil").valid())
        assertNull(Record.fromJson(JsonObject(a.toJson() + ("addrs" to JsonArray(listOf(JsonPrimitive("x")))))))
    }

    @Test fun `Python accepts Kotlin-signed records`() {
        Py.require()
        val id = Identity.generate(); val enc = EncKey.generate()
        val recs = listOf(Record.create(id, "aliçe 😀", listOf("relay:hub:9000", "wss://x"), enc.pub),
            Record(id.pubkey, "n", emptyList(), 1728750000123456, null).also { it.sig = id.sign(Canonical.encode(it.body())) })
        val out = Py.run("""
from lichen.records import Record
print([Record.from_dict(d) is not None for d in json.load(sys.stdin)])
""", dumps(JsonArray(recs.map { it.toJson() })))
        assertEquals("[True, True]", out.trim())
    }
}
