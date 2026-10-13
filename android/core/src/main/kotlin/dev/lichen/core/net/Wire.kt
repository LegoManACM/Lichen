package dev.lichen.core.net

import dev.lichen.core.dumps
import dev.lichen.core.parseObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Transport for requests to hubs (wire.py). An address is HOST:PORT (raw TCP, one JSON object per line),
// ws://HOST[:PORT][/path] or wss://... (WebSocket, one JSON object per text message). Normal requests are
// one request + one reply per connection; relay attachments keep the connection open.

const val LINE_LIMIT = 4 * 1024 * 1024
const val CONNECT_TIMEOUT_MS = 5_000

fun isUrl(addr: String): Boolean = addr.startsWith("ws://") || addr.startsWith("wss://")

/** HOST:PORT -> (host, port). For ws(s) URLs, the host and port the URL points at. */
fun splitAddr(addr: String): Pair<String, Int> {
    if (isUrl(addr)) {
        val u = URI(addr)
        val host = u.host ?: throw IllegalArgumentException("bad address: $addr")
        return host.trim('[', ']') to (if (u.port != -1) u.port else if (addr.startsWith("wss://")) 443 else 80)
    }
    val i = addr.lastIndexOf(':')
    require(i > 0) { "bad address: $addr" }
    return addr.substring(0, i).trim('[', ']') to addr.substring(i + 1).toInt()
}

/**
 * A connection carrying JSON objects. [recv] returns null when the peer closed. Cancelling a suspended
 * [recv] closes the connection (a blocked socket read can't be interrupted any other way).
 */
interface Conn {
    suspend fun recv(): JsonObject?
    suspend fun send(msg: JsonObject)
    fun close()
}

class LineConn(private val socket: Socket) : Conn {
    private val input = socket.getInputStream().buffered(64 * 1024)
    private val output = socket.getOutputStream()
    private val writeLock = Mutex()

    override suspend fun recv(): JsonObject? = coroutineScope {
        val read = async(Dispatchers.IO) { readMessage() }
        try {
            read.await()
        } catch (e: CancellationException) {
            close()
            throw e
        }
    }

    private fun readMessage(): JsonObject? {
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isBlank()) continue
            parseObject(line)?.let { return it }  // junk lines are skipped, like wire.py
        }
    }

    override suspend fun send(msg: JsonObject) {
        val bytes = (dumps(msg) + "\n").toByteArray(Charsets.UTF_8)
        writeLock.withLock {
            withContext(Dispatchers.IO) { output.write(bytes); output.flush() }
        }
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    private companion object {
        /** One line (without the newline), null at end of stream; IOException if it exceeds [LINE_LIMIT]. */
        fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8)
                if (b == 10) return buf.toString(Charsets.UTF_8)
                buf.write(b)
                if (buf.size() > LINE_LIMIT) throw IOException("line too long")
            }
        }
    }
}

private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // we do our own keepalives and timeouts
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

class WsConn private constructor() : Conn {
    private val incoming = Channel<JsonObject>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()
    @Volatile private var ws: WebSocket? = null

    override suspend fun recv(): JsonObject? = incoming.receiveCatching().getOrNull()

    override suspend fun send(msg: JsonObject) {
        val w = ws ?: throw IOException("not connected")
        val text = dumps(msg)
        if (text.length > LINE_LIMIT) throw IOException("message too large")
        if (!w.send(text)) throw IOException("websocket closed")
    }

    override fun close() {
        incoming.close()
        ws?.close(1000, null)
        ws?.cancel()  // don't wait for the peer's close frame
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > LINE_LIMIT) { webSocket.cancel(); incoming.close(); return }
            parseObject(text)?.let { incoming.trySend(it) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            incoming.close()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.close() }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened.completeExceptionally(IOException("websocket failed: ${t.message}", t))
            incoming.close()
        }
    }

    companion object {
        internal suspend fun open(url: String): WsConn {
            val conn = WsConn()
            val request = try { Request.Builder().url(url).build() } catch (e: IllegalArgumentException) { throw IOException("bad address: $url") }
            conn.ws = httpClient.newWebSocket(request, conn.listener)
            try {
                conn.opened.await()
            } catch (e: Throwable) {
                conn.close()
                throw e
            }
            return conn
        }
    }
}

/** Open a connection to [addr] (5 s connect timeout). Throws IOException on failure. */
suspend fun connect(addr: String): Conn {
    if (isUrl(addr)) return WsConn.open(addr)
    return withContext(Dispatchers.IO) {
        val (host, port) = try { splitAddr(addr) } catch (e: IllegalArgumentException) { throw IOException("bad address: $addr") }
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: Throwable) {
            try { socket.close() } catch (_: IOException) {}
            throw e
        }
        LineConn(socket)
    }
}

/** Send [msg] to [addr] and return the reply, or null if the peer is unreachable or misbehaves. Never throws (except cancellation). */
suspend fun request(addr: String, msg: JsonObject, timeout: Duration = 3.seconds): JsonObject? {
    val conn = try {
        withTimeoutOrNull(timeout + (CONNECT_TIMEOUT_MS / 1000).seconds) { connect(addr) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } ?: return null
    return try {
        conn.send(msg)
        withTimeoutOrNull(timeout) { conn.recv() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } finally {
        conn.close()
    }
}
