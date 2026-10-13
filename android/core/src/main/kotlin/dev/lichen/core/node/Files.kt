package dev.lichen.core.node

import dev.lichen.core.api.ClientEvent
import dev.lichen.core.api.FileLimits
import dev.lichen.core.api.FileTransfer
import dev.lichen.core.api.FilesApi
import dev.lichen.core.api.SendResult
import dev.lichen.core.api.TransferStatus
import dev.lichen.core.asBool
import dev.lichen.core.asDouble
import dev.lichen.core.asLong
import dev.lichen.core.asString
import dev.lichen.core.dumps
import dev.lichen.core.obj
import dev.lichen.core.parseObject
import dev.lichen.core.short
import dev.lichen.core.store.DEFAULT_MAX_MB
import dev.lichen.core.store.FileStore
import dev.lichen.core.writeFileAtomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

// File sharing on the node (node.py: send_file, download, decline_file, _on_file_offer, file_offer/file_get).
// The Node only knows [FileHandler] (one dispatch line in Node.handle); everything else lives here.
//
// Groups hook: a group file offer is `send(open, name, size, to = <members minus us>, group = <group id>)`
// (node.send_file(group=...)); incoming group offers are stored with their `group` and appear in
// [FilesApi.transfers] with FileTransfer.group set. Nothing here checks group membership, like Python.

const val FILE_RETRIES = 4  // attempts per chunk before a download is marked failed (it can be resumed)

interface FileHandler {
    suspend fun handle(kind: String, msg: JsonObject): JsonObject
}

class FileService(
    private val dataDir: File,
    private val emit: (ClientEvent) -> Unit,
    private val downloadsDir: File? = null,
) : FilesApi, FileHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<FileStore>()
    @Volatile private var node: Node? = null
    private val _transfers = MutableStateFlow<List<FileTransfer>>(emptyList())
    private val _limits = MutableStateFlow(FileLimits(DEFAULT_MAX_MB, 0.0))
    private val progress = ConcurrentHashMap<String, Long>()
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val offerItems = ConcurrentHashMap<String, Map<String, String>>()  // file id -> recipient -> outbox item id (this run)

    override val transfers: StateFlow<List<FileTransfer>> = _transfers
    override val limits: StateFlow<FileLimits> = _limits

    private val store: FileStore? get() = if (ready.isCompleted && !ready.isCancelled) ready.getCompleted() else null
    private val limitsFile get() = File(dataDir, "filelimits.json")  // {"max_file_mb", "auto_download_mb"}: lichen.yaml's names

    /** Read files.json (blocking IO; call off the main thread). */
    fun load(): FileStore {
        store?.let { return it }
        val s = FileStore(dataDir, downloadsDir)
        s.onUpdate = { refresh() }
        parseObject(limitsFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: "{}")?.let { o ->
            _limits.value = FileLimits(o["max_file_mb"].asDouble() ?: DEFAULT_MAX_MB, o["auto_download_mb"].asDouble() ?: 0.0)
        }
        ready.complete(s)
        refresh()
        return s
    }

    /** The store, for device sync to register (`deviceState()` / `mergeDeviceState()`). */
    fun fileStore(): FileStore? = store

    fun attach(n: Node) { node = n; n.files = this; refresh() }

    fun detach() {
        node?.files = null
        node = null
        scope.coroutineContext[Job]?.cancelChildren()
        refresh()
    }

    override fun setLimits(limits: FileLimits) {
        _limits.value = limits
        writeFileAtomic(limitsFile, dumps(obj {
            put("max_file_mb", JsonPrimitive(limits.maxMb)); put("auto_download_mb", JsonPrimitive(limits.autoDownloadMb))
        }))
    }

    // --- state for the UI ------------------------------------------------------------------------

    fun refresh() {
        val s = store ?: return
        val box = node?.outbox
        val out = ArrayList<FileTransfer>()
        for ((id, o) in s.incoming()) {
            val status = when (o.status) {
                "downloading" -> TransferStatus.DOWNLOADING
                "done" -> TransferStatus.DONE
                "declined" -> TransferStatus.DECLINED
                "failed" -> TransferStatus.FAILED
                "refused" -> TransferStatus.REFUSED
                else -> TransferStatus.PENDING
            }
            val done = when {
                status == TransferStatus.DONE -> o.size
                status == TransferStatus.DOWNLOADING -> progress[id] ?: 0L
                else -> 0L
            }
            out.add(FileTransfer(id, o.name, o.size, false, o.from, emptyList(), o.group, o.ts, status, done, o.error))
        }
        for ((id, o) in s.outgoing()) {
            val waiting = box != null && offerItems[id]?.values?.any { box.has(it) } == true
            out.add(FileTransfer(id, o.name, o.size, true, node?.ident?.pubkey ?: "", o.to, o.group, o.ts,
                if (waiting) TransferStatus.WAITING else TransferStatus.OFFERED))
        }
        _transfers.value = out.sortedBy { it.ts }
    }

    override fun location(fileId: String): File? {
        val s = store ?: return null
        s.getIncoming(fileId)?.takeIf { it.status == "done" }?.saved?.let { path -> File(path).takeIf { it.isFile }?.let { return it } }
        return s.getOutgoing(fileId)?.let { File(it.path).takeIf { f -> f.isFile } }
    }

    // --- sending ---------------------------------------------------------------------------------

    override suspend fun send(open: () -> InputStream, name: String, size: Long, to: List<String>, group: String?): SendResult {
        val s = ready.await()
        val n = node
        if (n == null) {
            emit(ClientEvent.Log("Not connected yet; try again in a moment"))
            return SendResult("", 0, 0, to.size)
        }
        val (fileId, entry) = kotlinx.coroutines.withContext(Dispatchers.IO) { s.shareStream(name, open, to, group) }
        val fields = mapOf(
            "file_id" to JsonPrimitive(fileId), "name" to JsonPrimitive(entry.name), "size" to JsonPrimitive(entry.size),
            "sha256" to JsonPrimitive(entry.sha256), "key" to JsonPrimitive(entry.key),
            "group" to (group?.let { JsonPrimitive(it) } ?: JsonNull),
        )
        val results = coroutineScope {
            to.map { pk -> async { pk to try { n.sendSealed("file_offer", pk, fields) } catch (e: CancellationException) { throw e } catch (e: Exception) { null to JsonObject(emptyMap()) } } }.awaitAll()
        }
        offerItems[fileId] = results.filter { it.second.first == "waiting" }.associate { (pk, r) -> pk to "file_offer:${r.second["id"].asString()}" }
        refresh()
        return SendResult(fileId, results.count { it.second.first == "delivered" }, results.count { it.second.first == "waiting" },
            results.count { it.second.first == null })
    }

    // --- receiving -------------------------------------------------------------------------------

    override suspend fun download(fileId: String): File? {
        val s = ready.await()
        val n = node ?: run { emit(ClientEvent.Log("Not connected yet; try again in a moment")); return null }
        val offer = s.getIncoming(fileId) ?: return null
        if (offer.status != "pending" && offer.status != "failed") return null
        if (!active.add(fileId)) return null
        return try {
            scope.async { pull(s, n, fileId, offer) }.await()
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) null else throw e  // the node stopped under us; not the caller's cancellation
        } finally {
            active.remove(fileId)
        }
    }

    private suspend fun pull(s: FileStore, n: Node, fileId: String, offer: FileStore.Incoming): File? {
        s.setStatus(fileId, "downloading")
        try {
            var offset = s.resumeOffset(fileId)
            s.partFile(fileId).apply { parentFile.mkdirs(); createNewFile() }
            progress[fileId] = offset
            var lastReport = 0L
            while (offset < offer.size) {
                var got = false
                for (attempt in 0 until FILE_RETRIES) {
                    val reply = n.requestPeer(offer.from, obj {
                        put("type", JsonPrimitive("file_get")); put("file_id", JsonPrimitive(fileId)); put("offset", JsonPrimitive(offset))
                    })
                    if (reply != null && reply["ok"].asBool() == true) {
                        val w = s.writeChunk(fileId, offset, reply)
                        if (w != null && w > 0) { offset += w; got = true; break }
                    }
                    val err = reply?.get("error").asString()
                    if (err == "unknown file" || err == "file no longer available") error("the sender no longer has it ($err)")
                }
                if (!got) error("couldn't reach the sender; download again to resume")
                progress[fileId] = offset
                val now = System.currentTimeMillis()
                if (now - lastReport > 250) { lastReport = now; s.touched() }
            }
            val saved = s.finish(fileId)
            s.setStatus(fileId, "done", saved = saved.absolutePath)
            progress.remove(fileId)
            incomingTransfer(fileId)?.let { emit(ClientEvent.FileDownloaded(it)) }
            return saved
        } catch (e: CancellationException) {
            if (s.getIncoming(fileId)?.status == "downloading") s.setStatus(fileId, "pending")  // resumable
            throw e
        } catch (e: IllegalStateException) {
            return failed(s, fileId, e.message ?: "download failed")
        } catch (e: IOException) {
            return failed(s, fileId, e.message ?: "download failed")
        } finally {
            active.remove(fileId)
            progress.remove(fileId)
        }
    }

    private fun failed(s: FileStore, fileId: String, error: String): File? {
        s.setStatus(fileId, "failed", error = error)
        incomingTransfer(fileId)?.let { emit(ClientEvent.FileFailed(it, error)) }
        return null
    }

    override fun decline(fileId: String) {
        val s = store ?: return
        s.getIncoming(fileId) ?: return
        s.setStatus(fileId, "declined")
        s.partFile(fileId).delete()
    }

    private fun incomingTransfer(id: String): FileTransfer? {
        refresh()
        return _transfers.value.firstOrNull { it.id == id && !it.outgoing }
    }

    // --- requests --------------------------------------------------------------------------------

    override suspend fun handle(kind: String, msg: JsonObject): JsonObject {
        val s = store ?: return fail("not ready")
        val n = node ?: return fail("not ready")
        if (kind == "file_get") {
            val id = msg["file_id"].asString()
            val offset = msg["offset"].asLong()
            if (id == null || offset == null) return fail("bad request")
            return s.readChunk(id, offset)
        }
        // file_offer: sealed and signed like a message
        val inner = n.openSealedMsg(msg) ?: return fail("bad message")
        val sender = inner["from"].asString()!!
        if (n.trust.blocked(sender)) return OK_REPLY  // dropped; don't tell them they're blocked
        n.seen(sender)
        n.typing.remove(sender)
        onOffer(s, n, inner)
        return OK_REPLY
    }

    private fun onOffer(s: FileStore, n: Node, inner: JsonObject) {
        val sender = inner["from"].asString() ?: return
        val fileId = inner["file_id"].asString() ?: return
        val size = inner["size"].asLong() ?: return
        var offer = FileStore.Incoming(
            sender, inner["name"].asString() ?: return, size, inner["sha256"].asString() ?: return,
            inner["key"].asString() ?: return, inner["group"].asString(), inner["ts"].asDouble() ?: return,
        )
        if (size < 0) return
        val limits = _limits.value
        if (size > limits.maxMb * 1024 * 1024) {
            offer = offer.copy(status = "refused", error = "bigger than your ${"%g".format(limits.maxMb)} MB limit (max_file_mb)")
        }
        if (!s.addOffer(fileId, offer)) return  // already have it
        val trusted = n.trust.trusted(sender)
        val name = n.trust.nickname(sender) ?: n.phonebook.get(sender)?.name ?: short(sender)
        incomingTransfer(fileId)?.let { emit(ClientEvent.FileOffer(it, name, trusted)) }
        if (offer.status == "pending" && trusted && limits.autoDownloadMb > 0 && size <= limits.autoDownloadMb * 1024 * 1024) {
            scope.launch { download(fileId) }
        }
    }

    private fun fail(error: String) = obj { put("ok", JsonPrimitive(false)); put("error", JsonPrimitive(error)) }
    private val OK_REPLY = obj { put("ok", JsonPrimitive(true)) }
}
