package dev.lichen.core.node

import dev.lichen.core.Canonical
import dev.lichen.core.EncKey
import dev.lichen.core.Identity
import dev.lichen.core.asString
import dev.lichen.core.dumps
import dev.lichen.core.isLinked
import dev.lichen.core.markLinked
import dev.lichen.core.net.request
import dev.lichen.core.obj
import dev.lichen.core.openSealed
import dev.lichen.core.parseJson
import dev.lichen.core.parseObject
import dev.lichen.core.seal
import dev.lichen.core.verify
import dev.lichen.core.writeFileAtomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.time.Duration.Companion.seconds

// Syncing with your own other devices (node.py "syncing your own devices", vault.py client side).
// Live: devices that are on at the same time compare digests (a probe) and, if they differ, swap whole
// snapshots through your hubs (`forward` with fanout / except_device) or directly. Through the vault: when
// state changes a sealed snapshot is left on the hubs, and a device that was off collects them at start.
// The phone is never a vault host. Merging only ever adds, so devices converge in any order.
//
// The state is a map of named sections. Everything but "phonebook" counts towards the digest (the same set
// Python hashes), so two devices only agree when every section matches. "groups" and "files" start as the
// empty shapes Python produces with no groups/files; the groups and files code replaces them with [register].

const val SYNC_BUDGET = 2_300_000        // bytes of compressed state per device-sync message
const val SYNC_TIMEOUT_S = 30
const val STATE_MAX = 256L * 1024 * 1024 // most a snapshot may inflate to
const val VAULT_DELAY_MS = 5_000L        // after a change, wait this long (batching) before leaving a snapshot
const val VAULT_MIN_GAP_MS = 15_000L     // at most one snapshot upload per hub this often
const val DEVSYNC_DELAY_MS = 1_000L      // after a change, wait this long before a live sync
val KEEP_STEPS = listOf<Int?>(null, 2000, 500, 200, 100, 50)  // messages kept per chat when the history is too big

/**
 * One part of the synced state. [export] gets the number of messages to keep per chat (null = all);
 * [merge] adopts another device's version and returns how many things were new (it must not call the
 * store's change hook). [inDigest] is false only for the phonebook.
 */
class StateSection(val inDigest: Boolean = true, val export: (Int?) -> JsonElement, val merge: (JsonElement?) -> Int)

class Packed(val z: ByteArray, val digest: String)

class DeviceSync(
    private val ident: Identity,
    private val enc: EncKey,
    private val device: String,
    private val dataDir: File?,
    private val log: (String) -> Unit,
    private val scope: CoroutineScope,
    /** Hubs our devices can meet at: every relay we or a sibling attaches to. */
    private val hubs: () -> List<String>,
    /** Direct addresses of our other devices (not relay: ones). */
    private val siblings: () -> List<String>,
    /** A merge brought news: (their device id, total new things). */
    private val onMerged: (String, Int) -> Unit = { _, _ -> },
) {
    private val sections = LinkedHashMap<String, StateSection>()
    @Volatile var linked: Boolean = dataDir?.let { isLinked(it) } ?: false; private set
    @Volatile var vaultDelayMs = VAULT_DELAY_MS
    @Volatile var vaultMinGapMs = VAULT_MIN_GAP_MS

    private val vaultSeen = ConcurrentHashMap<String, Long>()  // device -> newest snapshot of theirs we've merged
    private val vaultPushed = ConcurrentHashMap<String, String>()  // hub -> digest of what we last left there
    private val vaultAt = ConcurrentHashMap<String, Long>()
    @Volatile private var vaultPending = false
    @Volatile private var vaultDirty = false
    @Volatile private var devsyncPending = false
    @Volatile private var roundBusy = false
    private var rev = 0L

    init {
        // The shape Python produces when there are no groups; the groups code replaces it. "files" is registered by FileService.attach.
        register("groups", StateSection(export = { JsonObject(emptyMap()) }, merge = { 0 }))
        loadSyncState()
    }

    /** Add or replace a state section (e.g. "trust", "dms", "outbox", "phonebook", later "groups", "files"). */
    fun register(name: String, section: StateSection) {
        synchronized(sections) { sections[name] = section }
    }

    private fun sectionList(): List<Pair<String, StateSection>> = synchronized(sections) { sections.toList() }

    // --- the state ---------------------------------------------------------------------------------

    private fun state(keep: Int?): JsonObject = JsonObject(sectionList().associate { (n, s) -> n to s.export(keep) })

    /** Our state, compressed to fit one message (older messages are left out if it must be). */
    fun pack(): Packed {
        var st: JsonObject
        var z: ByteArray
        var i = 0
        while (true) {
            val keep = KEEP_STEPS[i]
            st = state(keep)
            z = deflate(Canonical.encode(st))
            if (z.size <= SYNC_BUDGET || i == KEEP_STEPS.lastIndex) break
            i++
        }
        val skip = sectionList().filter { !it.second.inDigest }.map { it.first }.toSet()
        val core = JsonObject(st.filterKeys { it !in skip })
        return Packed(z, sha256(Canonical.encode(core)))
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(6)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(z: ByteArray): ByteArray? {
        val inf = Inflater()
        try {
            inf.setInput(z)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > STATE_MAX) return null
            }
            return if (inf.remaining > 0) null else out.toByteArray()
        } catch (e: Exception) {
            return null
        } finally { inf.end() }
    }

    private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A signed envelope sealed to our own key: our device id and digest, plus (if [full]) the state. */
    fun sealSnapshot(packed: Packed, full: Boolean = true, rev: Long = 0): JsonObject {
        val z = if (full) packed.z else ByteArray(0)
        val head = obj {
            put("from", JsonPrimitive(ident.pubkey)); put("to", JsonPrimitive(ident.pubkey))
            put("device", JsonPrimitive(device)); put("rev", JsonPrimitive(rev))
            put("ts", Canonical.now()); put("digest", JsonPrimitive(packed.digest)); put("zhash", JsonPrimitive(sha256(z)))
        }
        val signed = JsonObject(head + ("sig" to JsonPrimitive(ident.sign(Canonical.encode(head)))))
        return seal(enc.pub, dumps(signed).toByteArray(Charsets.UTF_8) + byteArrayOf('\n'.code.toByte()) + z)
    }

    /** (header, state) of a snapshot sealed to us and signed by us; state is null for a probe. */
    fun openSnapshot(box: JsonElement?): Pair<JsonObject, JsonObject?>? {
        val plain = (if (box is JsonObject) openSealed(enc, box) else null) ?: return null
        try {
            val nl = plain.indexOf('\n'.code.toByte())
            if (nl < 0) return null
            val head = parseObject(String(plain, 0, nl, Charsets.UTF_8)) ?: return null
            val z = plain.copyOfRange(nl + 1, plain.size)
            val sig = head["sig"].asString() ?: return null
            val body = JsonObject(head.filterKeys { it != "sig" })
            val me = ident.pubkey
            if (head["from"].asString() != me || head["to"].asString() != me ||
                !verify(me, Canonical.encode(body), sig) || sha256(z) != head["zhash"].asString()) return null
            if (z.isEmpty()) return body to null
            val state = parseObject(String(inflate(z) ?: return null, Charsets.UTF_8)) ?: return null
            return body to state
        } catch (e: Exception) {
            return null
        }
    }

    fun link() {
        if (!linked) {
            linked = true
            dataDir?.let { markLinked(it) }
            log("another device of yours is around; your devices will now keep each other in step")
            vaultSoon()
        }
    }

    private fun mergeState(state: JsonObject, from: String) {
        val counts = LinkedHashMap<String, Int>()
        for ((name, s) in sectionList()) {
            val n = try { s.merge(state[name]) } catch (e: CancellationException) { throw e } catch (e: Exception) { log("could not merge $name from your device $from: $e"); 0 }
            if (n > 0) counts[name] = n
        }
        if (counts.isNotEmpty()) {
            val news = counts.filterKeys { it != "phonebook" }
            if (news.isNotEmpty()) log("synced from your device $from: ${news.entries.joinToString(", ") { "${it.value} ${it.key}" }}")
            else log("synced from your device $from: ${counts["phonebook"]} contact record(s)")
            onMerged(from, news.values.sum())
        }
    }

    // --- live sync ---------------------------------------------------------------------------------

    /** Answer an incoming `device_sync` (a probe, or a full snapshot to merge). */
    fun handle(msg: JsonObject): JsonObject {
        val got = openSnapshot(msg["sealed"])
        if (got == null || got.first["device"].asString() == device) return obj { put("ok", JsonPrimitive(false)); put("error", JsonPrimitive("not allowed")) }
        val (head, state) = got
        link()
        if (state == null) return obj { put("ok", JsonPrimitive(true)); put("sealed", sealSnapshot(pack(), full = false)) }
        mergeState(state, head["device"].asString() ?: "?")
        return obj { put("ok", JsonPrimitive(true)); put("sealed", sealSnapshot(pack())) }
    }

    private suspend fun askDevices(target: String, msg: JsonObject, only: String? = null): List<JsonObject> {
        if (target in siblings()) return listOfNotNull(request(target, msg, SYNC_TIMEOUT_S.seconds))
        val fwd = obj {
            put("type", JsonPrimitive("forward")); put("to", JsonPrimitive(ident.pubkey)); put("msg", msg)
            put("except_device", JsonPrimitive(device)); put("fanout", JsonPrimitive(true))
            if (only != null) put("only_device", JsonPrimitive(only))
        }
        val reply = request(target, fwd, SYNC_TIMEOUT_S.seconds) ?: return emptyList()
        val replies = reply["replies"]
        return if (replies is JsonArray) replies.mapNotNull { it as? JsonObject } else listOf(reply)
    }

    private fun ok(r: JsonObject) = (r["ok"] as? JsonPrimitive)?.content == "true"

    /** Compare notes with our other devices that are on right now. Returns how many devices answered. */
    suspend fun syncDevices(): Int {
        val packed = pack()
        val probe = obj { put("type", JsonPrimitive("device_sync")); put("to", JsonPrimitive(ident.pubkey)); put("sealed", sealSnapshot(packed, full = false)) }
        val targets = hubs() + siblings()
        val answers = coroutineScope { targets.map { t -> async { askDevices(t, probe) } }.awaitAll() }
        val seen = HashSet<String>()
        val differ = ArrayList<Pair<String, String>>()
        for ((target, replies) in targets.zip(answers)) for (reply in replies) {
            val got = if (ok(reply)) openSnapshot(reply["sealed"]) else null
            val dev = got?.first?.get("device").asString()
            if (got == null || dev == null || dev == device || dev in seen) continue
            seen.add(dev)
            link()
            if (got.first["digest"].asString() != packed.digest) differ.add(target to dev)
        }
        if (differ.isNotEmpty()) {
            val full = obj { put("type", JsonPrimitive("device_sync")); put("to", JsonPrimitive(ident.pubkey)); put("sealed", sealSnapshot(packed)) }
            val all = coroutineScope { differ.map { (t, d) -> async { askDevices(t, full, d) } }.awaitAll() }
            for (replies in all) for (reply in replies) {
                val got = if (ok(reply)) openSnapshot(reply["sealed"]) else null
                val dev = got?.first?.get("device").asString()
                if (got?.second != null && dev != null && dev != device) mergeState(got.second!!, dev)
            }
        }
        return seen.size
    }

    /** Something worth telling our other devices changed: sync shortly (batched), and leave a snapshot on our hubs. */
    fun stateChanged() {
        vaultSoon()
        if (devsyncPending || (hubs().isEmpty() && siblings().isEmpty())) return
        devsyncPending = true
        scope.launch {
            try {
                delay(DEVSYNC_DELAY_MS)
                devsyncPending = false
                syncDevices()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { devsyncPending = false; log("device sync failed: $e") }
        }
    }

    /** The keepalive's device round: live sync, then the vault. Skipped if the last one is still running. */
    suspend fun deviceRound() {
        if (roundBusy || (hubs().isEmpty() && siblings().isEmpty())) return
        roundBusy = true
        try { syncDevices(); syncVault() } finally { roundBusy = false }
    }

    // --- the vault ---------------------------------------------------------------------------------

    private fun syncStateFile(): File? = dataDir?.let { File(it, "devsync.json") }

    private fun loadSyncState() {
        val seen = syncStateFile()?.takeIf { it.exists() }?.let { try { parseObject(it.readText(Charsets.UTF_8)) } catch (e: Exception) { null } }
            ?.get("seen") as? JsonObject ?: return
        seen.forEach { (d, r) -> (r as? JsonPrimitive)?.content?.toLongOrNull()?.let { vaultSeen[d] = it } }
    }

    private fun saveSyncState() {
        syncStateFile()?.let { writeFileAtomic(it, dumps(obj { put("seen", JsonObject(vaultSeen.mapValues { e -> JsonPrimitive(e.value) })) })) }
    }

    private fun signed(req: JsonObject): JsonObject = obj { put("req", req); put("sig", JsonPrimitive(ident.sign(Canonical.encode(req)))) }

    /** Collect the snapshots our other devices left on our hubs, then leave ours. Only linked identities use this. */
    suspend fun syncVault(): Int {
        val hs = hubs()
        if (!linked || hs.isEmpty()) return 0
        val got = vaultPull(hs)
        vaultPush(hs)
        return got
    }

    private suspend fun vaultPull(hs: List<String>): Int {
        val req = obj {
            put("pubkey", JsonPrimitive(ident.pubkey)); put("device", JsonPrimitive(device)); put("ts", Canonical.now())
            put("have", JsonObject(vaultSeen.mapValues { JsonPrimitive(it.value) }))
        }
        val msg = JsonObject(mapOf("type" to JsonPrimitive("vault_get")) + signed(req))
        var merged = 0
        val replies = coroutineScope { hs.map { h -> async { request(h, msg, SYNC_TIMEOUT_S.seconds) } }.awaitAll() }
        for (reply in replies) {
            if (reply == null || !ok(reply)) continue
            for (el in (reply["snaps"] as? JsonArray) ?: continue) {
                val snap = el as? JsonObject ?: continue
                val dev = snap["device"].asString() ?: continue
                val r = (snap["rev"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
                if (dev == device || r <= (vaultSeen[dev] ?: 0L)) continue
                val box = try { parseJson(snap["blob"].asString() ?: continue) } catch (e: Exception) { continue }
                val got = openSnapshot(box)
                if (got == null || got.second == null || got.first["device"].asString() != dev) continue  // not from us (or damaged)
                link()
                mergeState(got.second!!, dev)
                vaultSeen[dev] = r
                merged++
            }
        }
        if (merged > 0) saveSyncState()
        return merged
    }

    suspend fun vaultPush(hs: List<String>, force: Boolean = false) {
        val packed = pack()
        vaultDirty = false  // (a change made while we upload sets it again)
        val now = System.currentTimeMillis()
        val todo = hs.filter { vaultPushed[it] != packed.digest && (force || now - (vaultAt[it] ?: 0L) >= vaultMinGapMs) }
        if (todo.isEmpty()) return
        rev = maxOf(System.currentTimeMillis() * 1000, rev + 1)
        val blob = dumps(sealSnapshot(packed, rev = rev))
        val req = obj {
            put("pubkey", JsonPrimitive(ident.pubkey)); put("device", JsonPrimitive(device)); put("rev", JsonPrimitive(rev))
            put("ts", Canonical.now()); put("hash", JsonPrimitive(sha256(blob.toByteArray(Charsets.UTF_8))))
        }
        val msg = JsonObject(mapOf("type" to JsonPrimitive("vault_put")) + signed(req) + ("blob" to JsonPrimitive(blob)))
        for (h in todo) vaultAt[h] = System.currentTimeMillis()
        val replies = coroutineScope { todo.map { h -> async { request(h, msg, SYNC_TIMEOUT_S.seconds) } }.awaitAll() }
        for ((h, reply) in todo.zip(replies)) {
            if (reply != null && ok(reply)) vaultPushed[h] = packed.digest
            else if (reply != null) log("hub $h won't keep a snapshot for your other devices: ${reply["error"].asString()}")
        }
    }

    /** Leave a fresh snapshot on our hubs shortly (batched), if we have other devices to leave it for. */
    fun vaultSoon() {
        if (!linked || hubs().isEmpty()) return
        vaultDirty = true
        if (vaultPending) return
        vaultPending = true
        scope.launch {
            try {
                delay(vaultDelayMs)
                vaultPending = false
                vaultPush(hubs())
            } catch (e: CancellationException) { throw e } catch (e: Exception) { vaultPending = false; log("vault push failed: $e") }
        }
    }

    /** Don't take unsent news to the grave: leave it for our other devices (called before stopping). */
    suspend fun finalPush() {
        if (vaultDirty && linked) vaultPush(hubs(), force = true)
    }
}
