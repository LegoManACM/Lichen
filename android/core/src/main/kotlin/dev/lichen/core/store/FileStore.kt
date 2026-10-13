package dev.lichen.core.store

import dev.lichen.core.CODE_POINT_ORDER
import dev.lichen.core.Canonical
import dev.lichen.core.asDouble
import dev.lichen.core.asLong
import dev.lichen.core.asString
import dev.lichen.core.b64
import dev.lichen.core.decrypt
import dev.lichen.core.dumps
import dev.lichen.core.encrypt
import dev.lichen.core.obj
import dev.lichen.core.parseObject
import dev.lichen.core.secureRandom
import dev.lichen.core.stringArray
import dev.lichen.core.unb64
import dev.lichen.core.writeFileAtomic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

// File offers and the chunked, encrypted, resumable transfer behind them (files.py). Same files.json as
// Python: {"outgoing": {file_id: {path, name, size, sha256, key, to, group, ts}},
//          "incoming": {file_id: {from, name, size, sha256, key, group, ts, status, status_ts, saved, error}}}.
// status: pending | downloading | done | declined | failed | refused.
//
// Differences from Python: [share] takes a path (like Python) but [shareStream] copies a stream into
// `<dataDir>/sent/<file_id>` first, so the file stays readable for `file_get` after the picker's access
// ends; `path` then points at that copy. Downloads go to `<dataDir>/downloads` unless told otherwise.

const val FILE_CHUNK = 48 * 1024  // bytes per file_get
const val DEFAULT_MAX_MB = 100.0

fun fileAad(fileId: String, offset: Long): ByteArray = "lichen-file:$fileId:$offset".toByteArray()

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

fun sha256File(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return hex(md.digest())
}

private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }

/** A filename that can't escape the downloads folder or upset Windows (files.safe_name). */
fun safeName(name: String): String {
    var n = name.replace("\\", "/").trimEnd('/').substringAfterLast('/')
    if (n == "..") n = ""
    n = n.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trim(' ', '.')
    if (n.uppercase().split(".")[0] in RESERVED) n = "_$n"
    return n.take(180).ifEmpty { "file" }
}

fun humanSize(n: Long): String {
    var size = n.toDouble()
    for (unit in listOf("B", "KB", "MB", "GB")) {
        if (size < 1024 || unit == "GB") return if (unit == "B") "%.0f B".format(size) else "%.1f %s".format(size, unit)
        size /= 1024
    }
    return "$n B"
}

class FileStore(dataDir: File?, downloads: File? = null) {
    data class Outgoing(
        val path: String, val name: String, val size: Long, val sha256: String, val key: String,
        val to: List<String>, val group: String?, val ts: Double,
    ) {
        fun toJson(): JsonObject = obj {
            put("path", JsonPrimitive(path)); put("name", JsonPrimitive(name)); put("size", JsonPrimitive(size))
            put("sha256", JsonPrimitive(sha256)); put("key", JsonPrimitive(key)); put("to", stringArray(to))
            put("group", group?.let { JsonPrimitive(it) } ?: JsonNull); put("ts", Canonical.pyFloat(ts))
        }

        companion object {
            fun fromJson(e: JsonElement?): Outgoing? {
                val o = e as? JsonObject ?: return null
                return Outgoing(
                    o["path"].asString() ?: return null, o["name"].asString() ?: return null, o["size"].asLong() ?: return null,
                    o["sha256"].asString() ?: return null, o["key"].asString() ?: return null,
                    (o["to"] as? JsonArray)?.mapNotNull { it.asString() } ?: emptyList(), o["group"].asString(), o["ts"].asDouble() ?: 0.0,
                )
            }
        }
    }

    data class Incoming(
        val from: String, val name: String, val size: Long, val sha256: String, val key: String,
        val group: String?, val ts: Double, val status: String = "pending", val statusTs: Double = 0.0,
        val saved: String? = null, val error: String? = null,
    ) {
        fun toJson(): JsonObject = obj {
            put("from", JsonPrimitive(from)); put("name", JsonPrimitive(name)); put("size", JsonPrimitive(size))
            put("sha256", JsonPrimitive(sha256)); put("key", JsonPrimitive(key))
            put("group", group?.let { JsonPrimitive(it) } ?: JsonNull); put("ts", Canonical.pyFloat(ts))
            put("status", JsonPrimitive(status)); put("status_ts", Canonical.pyFloat(statusTs))
            saved?.let { put("saved", JsonPrimitive(it)) }
            error?.let { put("error", JsonPrimitive(it)) }
        }

        companion object {
            fun fromJson(e: JsonElement?): Incoming? {
                val o = e as? JsonObject ?: return null
                return Incoming(
                    o["from"].asString() ?: return null, o["name"].asString() ?: return null, o["size"].asLong() ?: return null,
                    o["sha256"].asString() ?: return null, o["key"].asString() ?: return null, o["group"].asString(),
                    o["ts"].asDouble() ?: 0.0, o["status"].asString() ?: "pending", o["status_ts"].asDouble() ?: 0.0,
                    o["saved"].asString(), o["error"].asString(),
                )
            }
        }
    }

    private val file: File? = dataDir?.let { File(it, "files.json") }
    private val partialDir: File = File(dataDir ?: File("."), "partial")
    private val sentDir: File = File(dataDir ?: File("."), "sent")
    val downloads: File = downloads ?: File(dataDir ?: File("."), "downloads")
    private val lock = Any()
    private val outgoing = LinkedHashMap<String, Outgoing>()
    private val incoming = LinkedHashMap<String, Incoming>()
    /** Called after offers change (device sync hooks in here). Not called for download progress or a device merge. */
    var onChange: (() -> Unit)? = null
    /** Called after any change at all (for the UI). */
    var onUpdate: (() -> Unit)? = null

    init {
        if (file != null && file.exists()) {
            val data = parseObject(file.readText(Charsets.UTF_8)) ?: JsonObject(emptyMap())
            (data["outgoing"] as? JsonObject)?.forEach { (id, v) -> Outgoing.fromJson(v)?.let { outgoing[id] = it } }
            (data["incoming"] as? JsonObject)?.forEach { (id, v) ->
                // interrupted by a restart: resumable
                Incoming.fromJson(v)?.let { incoming[id] = if (it.status == "downloading") it.copy(status = "pending") else it }
            }
        }
    }

    fun outgoing(): Map<String, Outgoing> = synchronized(lock) { LinkedHashMap(outgoing) }
    fun incoming(): Map<String, Incoming> = synchronized(lock) { LinkedHashMap(incoming) }
    fun getIncoming(fileId: String): Incoming? = synchronized(lock) { incoming[fileId] }
    fun getOutgoing(fileId: String): Outgoing? = synchronized(lock) { outgoing[fileId] }

    // --- sending ---------------------------------------------------------------------------------

    /** Offer a file that already sits at [path]; it must stay readable as long as the offer stands. */
    fun share(path: File, to: List<String>, group: String? = null): Pair<String, Outgoing> {
        require(path.isFile) { "no such file: $path" }
        val id = newFileId()
        return id to register(id, Outgoing(path.absolutePath, path.name, path.length(), sha256File(path), newKeyB64(), to, group, Canonical.nowSeconds()))
    }

    /** Copy [open]'s bytes into app storage (hashing as we go) and offer that copy under [name]. */
    fun shareStream(name: String, open: () -> InputStream, to: List<String>, group: String? = null): Pair<String, Outgoing> {
        val id = newFileId()
        sentDir.mkdirs()
        val copy = File(sentDir, safeName(id))
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            open().use { input ->
                copy.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n); out.write(buf, 0, n); size += n
                    }
                }
            }
        } catch (e: Exception) {
            copy.delete()
            throw e
        }
        val entry = Outgoing(copy.absolutePath, safeName(name), size, hex(md.digest()), newKeyB64(), to, group, Canonical.nowSeconds())
        return id to register(id, entry)
    }

    private fun register(id: String, entry: Outgoing): Outgoing {
        synchronized(lock) { outgoing[id] = entry; saveLocked() }
        onUpdate?.invoke()
        return entry
    }

    private fun newFileId() = b64(ByteArray(12).also { secureRandom.nextBytes(it) })
    private fun newKeyB64() = b64(ByteArray(32).also { secureRandom.nextBytes(it) })

    /** The encrypted chunk of an offered file at [offset], or {"ok": false, "error": ...}. */
    fun readChunk(fileId: String, offset: Long): JsonObject {
        val entry = getOutgoing(fileId) ?: return err("unknown file")
        if (offset < 0 || offset > entry.size) return err("bad offset")
        val data = try {
            RandomAccessFile(entry.path, "r").use { f ->
                f.seek(offset)
                val buf = ByteArray(minOf(FILE_CHUNK.toLong(), maxOf(0L, f.length() - offset)).toInt())
                f.readFully(buf)
                buf
            }
        } catch (e: java.io.IOException) {
            return err("file no longer available")
        }
        val box = encrypt(unb64(entry.key), data, fileAad(fileId, offset))
        return JsonObject(mapOf("ok" to JsonPrimitive(true)) + box)
    }

    private fun err(e: String) = obj { put("ok", JsonPrimitive(false)); put("error", JsonPrimitive(e)) }

    // --- receiving -------------------------------------------------------------------------------

    fun addOffer(fileId: String, offer: Incoming): Boolean {
        synchronized(lock) {
            if (fileId in incoming) return false
            incoming[fileId] = if (offer.statusTs == 0.0) offer.copy(statusTs = Canonical.nowSeconds()) else offer
            saveLocked()
        }
        onChange?.invoke(); onUpdate?.invoke()
        return true
    }

    fun partFile(fileId: String): File = File(partialDir, "${safeName(fileId)}.part")

    /** Bytes already downloaded (whole chunks only: a half-written tail is dropped). */
    fun resumeOffset(fileId: String): Long {
        val part = partFile(fileId)
        if (!part.exists()) return 0
        val size = part.length() - part.length() % FILE_CHUNK
        RandomAccessFile(part, "rw").use { it.setLength(size) }
        return size
    }

    /** Decrypt and append a chunk. Returns bytes written, or null if it doesn't decrypt. */
    fun writeChunk(fileId: String, offset: Long, box: JsonElement): Int? {
        val offer = getIncoming(fileId) ?: return null
        val data = decrypt(unb64(offer.key), box, fileAad(fileId, offset)) ?: return null
        val part = partFile(fileId)
        part.parentFile.mkdirs()
        part.appendBytes(data)
        return data.size
    }

    /** Verify the hash and move the file into downloads/. Throws IllegalStateException on a mismatch. */
    fun finish(fileId: String): File {
        val offer = getIncoming(fileId) ?: error("unknown offer")
        val part = partFile(fileId)
        if (!part.exists() || part.length() != offer.size || sha256File(part) != offer.sha256) {
            part.delete()
            error("downloaded file doesn't match its hash (corrupted or tampered); discarded")
        }
        downloads.mkdirs()
        val name = safeName(offer.name)
        var dest = File(downloads, name)
        var n = 1
        while (dest.exists()) {
            val dot = name.lastIndexOf('.')
            val (stem, suffix) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
            dest = File(downloads, "$stem ($n)$suffix")
            n++
        }
        if (!part.renameTo(dest)) { part.copyTo(dest); part.delete() }
        return dest
    }

    fun setStatus(fileId: String, status: String, saved: String? = null, error: String? = null) {
        synchronized(lock) {
            val o = incoming[fileId] ?: return
            incoming[fileId] = o.copy(status = status, statusTs = Canonical.nowSeconds(), saved = saved ?: o.saved.takeIf { status == "done" }, error = error)
            saveLocked()
        }
        if (status != "downloading") onChange?.invoke()  // progress isn't worth telling our other devices about
        onUpdate?.invoke()
    }

    /** Tell the UI something about a transfer moved (download progress). */
    fun touched() { onUpdate?.invoke() }

    // --- syncing between your own devices --------------------------------------------------------

    /** Offers made to us. What was downloaded where stays on each device, so a download elsewhere counts as
     *  still waiting here; a decline travels. */
    fun deviceState(): JsonObject = synchronized(lock) {
        obj {
            put("incoming", JsonObject(incoming.toSortedMap(CODE_POINT_ORDER).mapValues { (_, o) ->
                o.copy(status = if (o.status == "declined") "declined" else "pending", saved = null, error = null).toJson()
            }))
        }
    }

    /** Learn about offers our other devices received, and about ones they declined. Returns how many changed. */
    fun mergeDeviceState(state: JsonElement?): Int {
        var n = 0
        synchronized(lock) {
            ((state as? JsonObject)?.get("incoming") as? JsonObject)?.forEach { (fid, v) ->
                val o = v as? JsonObject ?: return@forEach
                val offer = Incoming.fromJson(o)?.takeIf { o["ts"].asDouble() != null } ?: return@forEach
                val mine = incoming[fid]
                if (mine == null) {
                    incoming[fid] = offer; n++
                } else if (offer.status == "declined" && mine.status in listOf("pending", "failed") && offer.statusTs > mine.statusTs) {
                    incoming[fid] = mine.copy(status = "declined", statusTs = offer.statusTs)
                    partFile(fid).delete(); n++
                }
            }
            if (n > 0) saveLocked()
        }
        if (n > 0) onUpdate?.invoke()
        return n
    }

    /** Offers matching [query] (id prefix, or name), in one of [statuses]. */
    fun findIncoming(query: String, statuses: Set<String> = setOf("pending", "failed")): List<Pair<String, Incoming>> {
        val q = query.lowercase()
        return incoming().filter { (fid, o) ->
            o.status in statuses && (fid.lowercase().startsWith(q) || o.name.lowercase() == q || o.name.lowercase().startsWith(q))
        }.toList()
    }

    fun save() = synchronized(lock) { saveLocked() }

    private fun saveLocked() {
        file?.let {
            writeFileAtomic(it, dumps(obj {
                put("outgoing", JsonObject(outgoing.mapValues { e -> e.value.toJson() }))
                put("incoming", JsonObject(incoming.mapValues { e -> e.value.toJson() }))
            }))
        }
    }
}
