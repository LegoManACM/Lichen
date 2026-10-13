package dev.lichen.core.net

import dev.lichen.core.Canonical
import dev.lichen.core.Identity
import dev.lichen.core.asString
import dev.lichen.core.dumps
import dev.lichen.core.node.PyPeer
import dev.lichen.core.node.eventually
import dev.lichen.core.obj
import dev.lichen.core.parseObject
import dev.lichen.core.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class WireTest {
    @Test
    fun addressHelpers() {
        assertTrue(isUrl("ws://h:1")); assertTrue(isUrl("wss://h")); assertFalse(isUrl("1.2.3.4:9000"))
        assertEquals("1.2.3.4" to 9000, splitAddr("1.2.3.4:9000"))
        assertEquals("::1" to 9000, splitAddr("[::1]:9000"))
        assertEquals("example.com" to 443, splitAddr("wss://example.com/path"))
        assertEquals("example.com" to 80, splitAddr("ws://example.com"))
        assertEquals("127.0.0.1" to 9300, splitAddr("ws://127.0.0.1:9300"))
    }

    @Test
    fun lineConnSkipsJunkAndEnforcesTheLineLimit() {
        ServerSocket(0).use { server ->
            thread {
                server.accept().use { s ->
                    s.getOutputStream().write("not json\n\n[1,2]\n{\"a\":1}\n".toByteArray())
                    s.getOutputStream().flush()
                    s.getOutputStream().write(ByteArray(LINE_LIMIT + 10) { 'x'.code.toByte() })  // no newline: too long
                    s.getOutputStream().flush()
                    Thread.sleep(500)
                }
            }
            runBlocking {
                val c = connect("127.0.0.1:${server.localPort}")
                try {
                    assertEquals("""{"a":1}""", dumps(c.recv()!!))
                    assertFailsWith<IOException> { c.recv() }
                } finally { c.close() }
            }
        }
    }

    @Test
    fun requestNeverThrows() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertNull(request("127.0.0.1:$closedPort", obj { put("type", JsonPrimitive("x")) }))
        assertNull(request("ws://127.0.0.1:$closedPort", obj { put("type", JsonPrimitive("x")) }))
        assertNull(request("garbage", obj { put("type", JsonPrimitive("x")) }))
        assertNull(request("ws://", obj { put("type", JsonPrimitive("x")) }))
        // A peer that accepts and never answers times out.
        ServerSocket(0).use { silent ->
            thread { silent.accept().use { Thread.sleep(2000) } }
            val t0 = System.currentTimeMillis()
            assertNull(request("127.0.0.1:${silent.localPort}", obj { put("type", JsonPrimitive("x")) }, timeout = 300.milliseconds))
            assertTrue(System.currentTimeMillis() - t0 < 1500)
        }
    }

    @Test
    fun talksToAPythonHubOverTcpAndWebSocket() {
        PyPeer(withUser = false).use { py ->
            for (addr in listOf(py.hubAddr, py.wsAddr)) {
                val reply = runBlocking { request(addr, obj { put("type", JsonPrimitive("lookup")); put("pubkey", JsonPrimitive(py.hubPk)) }) }
                assertNotNull(reply, addr)
                assertEquals(py.hubPk, ((reply["record"]) as JsonObject)["pubkey"].asString(), addr)
                val unknown = runBlocking { request(addr, obj { put("type", JsonPrimitive("nonsense")) }) }
                assertEquals("unknown type 'nonsense'", unknown!!["error"].asString(), addr)
            }
            // A big reply (many bytes) survives both transports too: a hello returns the whole phonebook.
            for (addr in listOf(py.hubAddr, py.wsAddr)) {
                val me = Identity.generate()
                val rec = dev.lichen.core.Record.create(me, "big ".repeat(1000), listOf("relay:${py.hubAddr}"), dev.lichen.core.EncKey.generate().pub)
                val reply = runBlocking { request(addr, obj { put("type", JsonPrimitive("hello")); put("record", rec.toJson()) }) }
                assertEquals("true", reply!!["ok"].toString(), addr)
            }
        }
    }
}

class RelayClientTest {
    /** A hub that drops the first connection right after attaching, keeps the second, pushes one request and watches keepalives. */
    @Test
    fun reconnectsAnswersRequestsAndKeepsAlive() {
        val ident = Identity.generate()
        val attaches = AtomicInteger()
        val seenFromClient = CopyOnWriteArrayList<JsonObject>()
        val server = ServerSocket(0)
        val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: IOException) { return@thread }
                val n = attaches.incrementAndGet()
                thread(isDaemon = true) {
                    val input = s.getInputStream().bufferedReader()
                    val out = s.getOutputStream()
                    val attach = parseObject(input.readLine())!!
                    val req = attach["req"] as JsonObject
                    val valid = verify(ident.pubkey, Canonical.encode(req), attach["sig"].asString()!!)
                    out.write((if (valid) "{\"ok\":true}\n" else "{\"ok\":false,\"error\":\"bad attach\"}\n").toByteArray()); out.flush()
                    seenFromClient.add(req)
                    if (n == 1) { Thread.sleep(100); s.close(); return@thread }
                    out.write("{\"rid\":7,\"msg\":{\"type\":\"ping\"}}\n".toByteArray()); out.flush()
                    input.lineSequence().forEach { line -> parseObject(line)?.let { seenFromClient.add(it) } }
                }
            }
        }
        val scope = CoroutineScope(Dispatchers.IO)
        val client = RelayClient("127.0.0.1:${server.localPort}", ident, "dev1",
            { m -> obj { put("ok", JsonPrimitive(true)); put("echo", m["type"] ?: JsonPrimitive("")) } }, keepaliveMs = 100)
        try {
            client.start(scope)
            eventually("second attach") { attaches.get() >= 2 && client.connected.value }
            assertEquals("dev1", (seenFromClient.first()["device"]).asString())
            eventually("reply to the pushed request") { seenFromClient.any { it["rid"]?.toString() == "7" } }
            val reply = seenFromClient.first { it["rid"]?.toString() == "7" }["reply"] as JsonObject
            assertEquals("ping", reply["echo"].asString())
            eventually("keepalives") { seenFromClient.count { "keepalive" in it } >= 2 }
            client.stop()
            assertFalse(client.connected.value)
        } finally {
            client.stop(); scope.cancel(); server.close()
            acceptor.join(1000)
        }
    }

    @Test
    fun staysDisconnectedWhenTheHubRefusesAndKeepsTrying() {
        val ident = Identity.generate()
        val attaches = AtomicInteger()
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: IOException) { return@thread }
                attaches.incrementAndGet()
                s.getOutputStream().write("{\"ok\":false,\"error\":\"bad attach\"}\n".toByteArray()); s.getOutputStream().flush()
                s.close()
            }
        }
        val scope = CoroutineScope(Dispatchers.IO)
        val client = RelayClient("127.0.0.1:${server.localPort}", ident, "d", { obj { } })
        try {
            client.start(scope)
            eventually("a retry after the backoff") { attaches.get() >= 2 }
            assertFalse(client.connected.value)
        } finally { client.stop(); scope.cancel(); server.close() }
    }

    @Test
    fun attachesToARealPythonHub() {
        PyPeer(withUser = false).use { py ->
            val ident = Identity.generate()
            val scope = CoroutineScope(Dispatchers.IO)
            val client = RelayClient(py.wsAddr, ident, "dev", { obj { put("ok", JsonPrimitive(true)) } })
            try {
                client.start(scope)
                eventually("attached over ws") { client.connected.value }
                eventually("hub lists us") { ident.pubkey in py.hubAttached() }
                // The hub forwards a request down the attachment and gets our reply back.
                val reply = runBlocking {
                    withTimeout(5000) { request(py.hubAddr, obj {
                        put("type", JsonPrimitive("forward")); put("to", JsonPrimitive(ident.pubkey))
                        put("msg", obj { put("type", JsonPrimitive("anything")) })
                    }) }
                }
                assertEquals("true", reply!!["ok"].toString())
                client.stop()
                eventually("hub sees us leave") { ident.pubkey !in py.hubAttached() }
            } finally { client.stop(); scope.cancel() }
        }
    }
}
