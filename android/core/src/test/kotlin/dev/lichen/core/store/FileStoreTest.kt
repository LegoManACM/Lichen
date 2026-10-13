package dev.lichen.core.store

import dev.lichen.core.Py
import dev.lichen.core.asString
import dev.lichen.core.dumps
import dev.lichen.core.obj
import dev.lichen.core.parseObject
import dev.lichen.core.tmpDir
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FileStore against the Python files.py: same files.json, same device state, same names, same crypto. */
class FileStoreTest {
    private fun offer(from: String, name: String, status: String = "pending", ts: Double = 1728750000.123456, group: String? = null) =
        FileStore.Incoming(from, name, 1234, "ab".repeat(32), "k".repeat(43), group, ts, status, ts + 1)

    @Test
    fun pythonWrittenFilesJsonLoadsAndDeviceStateMatches() {
        Py.require()
        val dir = tmpDir()
        val out = Py.run("""
import time
from pathlib import Path
from lichen.files import FileStore
d = Path(json.loads(sys.stdin.read())["dir"])
src = d / "orig.txt"; src.write_bytes(b"hello" * 5000)
st = FileStore(d)
fid, entry = st.share(src, ["pk1", "pk2"], None)
st.add_offer("in1", {"from": "pkA", "name": "a.txt", "size": 10, "sha256": "ff" * 32, "key": "kk", "group": None, "ts": 1728750000.5, "status": "pending"})
st.add_offer("in2", {"from": "pkB", "name": "b é.txt", "size": 20, "sha256": "ee" * 32, "key": "kk2", "group": "g1", "ts": 1728750001.25, "status": "pending"})
st.set_status("in2", "done", saved="/x/b.txt")
st.add_offer("in3", {"from": "pkB", "name": "c.txt", "size": 30, "sha256": "dd" * 32, "key": "kk3", "group": None, "ts": 1728750002.0, "status": "pending"})
st.set_status("in3", "declined")
st.add_offer("in4", {"from": "pkB", "name": "d.txt", "size": 40, "sha256": "cc" * 32, "key": "kk4", "group": None, "ts": 1728750003.0, "status": "refused", "error": "too big"})
st.set_status("in1", "downloading")
again = FileStore(d)   # what a restart sees
print(json.dumps({"id": fid, "sha": entry["sha256"], "state": again.device_state()}))
""", dumps(obj { put("dir", JsonPrimitive(dir.absolutePath.replace('\\', '/'))) }))
        val py = parseObject(out.trim().lines().last())!!
        val store = FileStore(dir)
        val o = store.getOutgoing(py["id"].asString()!!)!!
        assertEquals(py["sha"].asString(), o.sha256)
        assertEquals(25000L, o.size)
        assertEquals(listOf("pk1", "pk2"), o.to)
        assertEquals("orig.txt", o.name)
        val inc = store.incoming()
        assertEquals("pending", inc["in1"]!!.status)  // was downloading: interrupted by the "restart"
        assertEquals("done", inc["in2"]!!.status); assertEquals("/x/b.txt", inc["in2"]!!.saved); assertEquals("g1", inc["in2"]!!.group)
        assertEquals("b é.txt", inc["in2"]!!.name)
        assertEquals("refused", inc["in4"]!!.status); assertEquals("too big", inc["in4"]!!.error)
        assertEquals(1728750001.25, inc["in2"]!!.ts)
        // The device state we produce is exactly what Python produces from the same file.
        assertEquals(py["state"], store.deviceState())
        // The chunk we serve is decryptable by Python, and Python's by us (full-cycle: see FilesIntegrationTest).
        val chunk = store.readChunk(py["id"].asString()!!, 0)
        assertTrue(chunk["ok"].toString() == "true" && chunk["ct"] != null)
    }

    @Test
    fun kotlinWrittenFilesJsonLoadsInPython() {
        Py.require()
        val dir = tmpDir()
        val store = FileStore(dir)
        val (id, o) = store.shareStream("Report: Q1?.txt", { ByteArrayInputStream("data".repeat(100_000).toByteArray()) }, listOf("pk1"), "grp")
        assertEquals("Report_ Q1_.txt", o.name)
        store.addOffer("in1", offer("pkA", "a.bin"))
        store.addOffer("in2", offer("pkB", "b é.bin", group = "g"))
        store.setStatus("in2", "done", saved = "/some/where/b.bin")
        store.addOffer("in3", offer("pkB", "c.bin"))
        store.setStatus("in3", "declined")
        store.setStatus("in1", "failed", error = "couldn't reach the sender")
        val out = Py.run("""
from pathlib import Path
from lichen.files import FileStore, sha256_file
d = Path(json.loads(sys.stdin.read())["dir"])
st = FileStore(d)
fid = next(iter(st.outgoing))
chunk = st.read_chunk(fid, 0)
from lichen.crypto import decrypt
from lichen.identity import unb64
from lichen.files import file_aad
plain = decrypt(unb64(st.outgoing[fid]["key"]), chunk, file_aad(fid, 0))
print(json.dumps({"id": fid, "entry": st.outgoing[fid], "sha": sha256_file(Path(st.outgoing[fid]["path"])),
                  "plain_ok": plain == (b"data" * 100_000)[:len(plain)] and len(plain) == 48 * 1024,
                  "incoming": st.incoming, "state": st.device_state()}))
""", dumps(obj { put("dir", JsonPrimitive(dir.absolutePath.replace('\\', '/'))) }))
        val py = parseObject(out.trim().lines().last())!!
        assertEquals(id, py["id"].asString())
        assertEquals(o.sha256, py["sha"].asString())
        assertEquals("true", py["plain_ok"].toString())
        val entry = py["entry"] as JsonObject
        assertEquals("grp", entry["group"].asString()); assertEquals(o.sha256, entry["sha256"].asString())
        val incoming = py["incoming"] as JsonObject
        assertEquals("failed", ((incoming["in1"]) as JsonObject)["status"].asString())
        assertEquals("/some/where/b.bin", ((incoming["in2"]) as JsonObject)["saved"].asString())
        assertEquals(store.deviceState(), py["state"])
    }

    @Test
    fun deviceStateMergesBothWays() {
        Py.require()
        val a = FileStore(tmpDir()); val b = FileStore(tmpDir())
        a.addOffer("x", offer("pkA", "x.bin", ts = 100.0)); a.addOffer("y", offer("pkA", "y.bin", ts = 101.0))
        assertEquals(2, b.mergeDeviceState(a.deviceState()))
        assertEquals(0, b.mergeDeviceState(a.deviceState()))
        assertEquals("pending", b.getIncoming("x")!!.status)
        b.setStatus("x", "declined")
        assertEquals(1, a.mergeDeviceState(b.deviceState()))
        assertEquals("declined", a.getIncoming("x")!!.status)
        assertEquals("pending", a.getIncoming("y")!!.status)
        // A finished download elsewhere still counts as waiting here.
        b.setStatus("y", "done", saved = "/p")
        assertEquals(0, a.mergeDeviceState(b.deviceState()))
        assertEquals("pending", a.getIncoming("y")!!.status)
        // Python takes our state too.
        val out = Py.run("""
from pathlib import Path
from lichen.files import FileStore
d = Path(json.loads(sys.stdin.read())["dir"])
st = FileStore(d)
print(st.merge_device_state(json.load(open(d / "kotlin_state.json"))), sorted(st.incoming))
""", dumps(obj { put("dir", JsonPrimitive(File(tmpDir().also { File(it, "kotlin_state.json").writeText(dumps(a.deviceState())) }.absolutePath).path.replace('\\', '/'))) }))
        assertTrue(out.trim().lines().last().startsWith("2 "), out)
    }

    @Test
    fun safeNameMatchesPython() {
        Py.require()
        val names = listOf("a.txt", "../../etc/passwd", "C:\\Windows\\evil.exe", "con.txt", "NUL", "weird<>:\"|?*name.txt", "  . dots . ",
            "", "..", "dir/", "x".repeat(300) + ".bin", "é😀.png", "lpt1", "COM9.tar.gz", "file\u0001name")
        val out = Py.run("""
from lichen.files import safe_name
names = json.loads(sys.stdin.read())
print(json.dumps([safe_name(n) for n in names]))
""", dumps(kotlinx.serialization.json.JsonArray(names.map { JsonPrimitive(it) })))
        val expected = (dev.lichen.core.parseJson(out.trim().lines().last()) as kotlinx.serialization.json.JsonArray).map { it.asString()!! }
        assertEquals(expected, names.map { safeName(it) })
    }

    @Test
    fun finishChecksTheHashAndAvoidsOverwriting() {
        val dir = tmpDir()
        val store = FileStore(dir)
        val data = "hello".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        store.addOffer("f1", FileStore.Incoming("pk", "hi.txt", 5, sha, "k", null, 1.0))
        store.addOffer("f2", FileStore.Incoming("pk", "hi.txt", 5, sha, "k", null, 2.0))
        for (id in listOf("f1", "f2")) { store.partFile(id).apply { parentFile.mkdirs(); writeBytes(data) } }
        assertEquals("hi.txt", store.finish("f1").name)
        assertEquals("hi (1).txt", store.finish("f2").name)
        store.addOffer("f3", FileStore.Incoming("pk", "bad.txt", 5, sha, "k", null, 3.0))
        store.partFile("f3").apply { parentFile.mkdirs(); writeBytes("HELLO".toByteArray()) }
        val e = runCatching { store.finish("f3") }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("hash"))
        assertFalse(store.partFile("f3").exists())
    }
}
