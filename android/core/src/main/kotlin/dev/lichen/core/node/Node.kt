package dev.lichen.core.node

import dev.lichen.core.Canonical
import dev.lichen.core.EncKey
import dev.lichen.core.Identity
import dev.lichen.core.Record
import dev.lichen.core.asBool
import dev.lichen.core.asDouble
import dev.lichen.core.asString
import dev.lichen.core.b64
import dev.lichen.core.net.RelayClient
import dev.lichen.core.net.request
import dev.lichen.core.obj
import dev.lichen.core.openSealed
import dev.lichen.core.parseObject
import dev.lichen.core.seal
import dev.lichen.core.secureRandom
import dev.lichen.core.short
import dev.lichen.core.store.DmStore
import dev.lichen.core.store.Outbox
import dev.lichen.core.store.Phonebook
import dev.lichen.core.store.TrustStore
import dev.lichen.core.store.legacyId
import dev.lichen.core.stringArray
import dev.lichen.core.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

// The Phase 1 node (node.py): a relay-only client. It never listens (direct = false); it attaches to its
// hubs and receives every request down those connections. Its record's addrs are "relay:<hub>" for each hub
// plus addresses adopted from our other devices.
//
// Handled: hello, announce, lookup, msg, signal, owed, nudge, dm_sync. Made: hello, announce, lookup,
// forward, msg (with IOU ids), dm_sync, outbox + flush.
// TODO(phase 2): presence signals (_presence_round), sending typing/read signals, device_sync/vault_put/vault_get
//   (sync_devices, sync_vault, _state_changed), the keepalive's device round.
// TODO(phase 3): groups (group_msg/group_update/group_sync/group_leave), files (file_offer/file_get),
//   UDP hole punching (punch, _try_direct, direct paths in _req), group_handoff.

const val RELAY_PREFIX = "relay:"
const val ONLINE_WINDOW = 75.0       // seen this recently = online
const val SYNC_MAX_AGE = 300.0       // seconds a signed sync request stays valid
const val TYPING_SHOWN = 6.0         // how long "typing…" stays up after a signal
const val NODE_KEEPALIVE_MS = 15_000L
const val OUTBOX_EVERY = 4           // keepalive ticks between outbox retries (4 x 15 s = once a minute)
const val ATTACH_WAIT_MS = 5_000L

private fun str(s: String) = JsonPrimitive(s)

private fun failed(error: String): JsonObject = obj { put("ok", JsonPrimitive(false)); put("error", str(error)) }
private val OK: JsonObject = obj { put("ok", JsonPrimitive(true)) }

private fun JsonObject?.truthy(key: String): Boolean = this?.get(key).asBool() == true

class Node(
    val name: String,
    val ident: Identity,
    val enc: EncKey,
    val phonebook: Phonebook,
    val dms: DmStore,
    val outbox: Outbox,
    val trust: TrustStore,
    relays: List<String>,
    bootstrap: List<String> = emptyList(),
    val device: String = "default",
    private val log: (String) -> Unit = {},
    /** (author's record or null, text, the signed message): a message from someone else, live or via chat sync. */
    var onMessage: ((Record?, String, JsonObject) -> Unit)? = null,
    /** (kind, pubkey, count): "presence", "receipt", "typing", "owed". */
    var onEvent: ((String, String?, Int) -> Unit)? = null,
) {
    val relays: List<String> = relays.toList()
    /** Hubs are bootstrap addresses too (cli.py): we say hello to them at start. */
    val bootstrap: List<String> = (bootstrap + relays.filter { it !in bootstrap }).distinct()
    val deviceAddrs = CopyOnWriteArrayList<String>()  // addresses our other devices advertise, adopted into our record

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var me: Record? = null; private set
    val relayClients = CopyOnWriteArrayList<RelayClient>()
    private var keepalive: Job? = null
    /** Background catch-up after [start] (join, announce, chat sync, outbox). Completes when done. */
    @Volatile var catchUp: Job? = null; private set
    /** Called for each relay client as it is created (before it has attached). */
    var onRelayClient: ((RelayClient) -> Unit)? = null

    private val flushing = ConcurrentHashMap.newKeySet<String>()
    private val owedSeen = ConcurrentHashMap<String, MutableSet<String>>()  // IOU item ids already reported, per sender
    private val lastSeen = ConcurrentHashMap<String, Double>()
    val typing = ConcurrentHashMap<String, Double>()  // pubkey -> show "typing…" until this time (epoch seconds)

    /** addrs: relay:<hub> for each hub, then adopted device addresses. */
    val addrs: List<String>
        get() {
            val own = relays.map { RELAY_PREFIX + it }
            return own + deviceAddrs.filter { it !in own }
        }

    // --- lifecycle -------------------------------------------------------------------------------

    /** Attach to the hubs (waits up to ~5 s for them), then catch up in the background ([catchUp]). */
    suspend fun start() {
        refreshRecord()
        for (relay in relays) {
            val client = RelayClient(relay, ident, device, { handle(it) }, log)
            client.start(scope)
            relayClients.add(client)
            onRelayClient?.invoke(client)
        }
        coroutineScope {
            relayClients.map { c ->
                async {
                    if (withTimeoutOrNull(ATTACH_WAIT_MS) { c.connected.first { it } } == null)
                        log("relay ${c.relay} not reachable yet; will keep retrying")
                }
            }.awaitAll()
        }
        keepalive = scope.launch { keepaliveLoop() }
        catchUp = scope.launch {
            try {
                for (addr in bootstrap) join(addr)
                // Our address may differ from last run; tell everyone we already know about.
                announce()
                // TODO(phase 2): sync_devices(), sync_vault(); (phase 3): group sync.
                syncDms()
                flushOutbox()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("catch-up failed: $e")
            }
        }
    }

    fun stop() {
        keepalive?.cancel()
        for (c in relayClients) c.stop()
        relayClients.clear()
        scope.cancel()
    }

    private suspend fun keepaliveLoop() {
        var tick = 0
        while (true) {
            delay(NODE_KEEPALIVE_MS)
            tick++
            if (tick % OUTBOX_EVERY == 0 && outbox.size() > 0) flushOutbox()
            // TODO(phase 2): device round every OUTBOX_EVERY ticks; presence round every 2 ticks.
        }
    }

    private fun spawn(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("background task failed: $e") }
        }
    }

    private fun refreshRecord() {
        var prev = me?.seq ?: 0
        phonebook.get(ident.pubkey)?.let { prev = maxOf(prev, it.seq) }
        val rec = Record.create(ident, name, addrs, enc.pub, prev)
        me = rec
        phonebook.merge(rec)
    }

    // --- transport -------------------------------------------------------------------------------

    /** One request to one peer. Relay addresses go through the hub as a `forward`. */
    private suspend fun req(addr: String, pubkey: String?, msg: JsonObject): JsonObject? {
        if (addr.startsWith(RELAY_PREFIX)) {
            if (pubkey == null) return null
            val hub = addr.substring(RELAY_PREFIX.length)
            // "from"/"item" let the hub record an IOU if they're away (only for msg["iou"] items).
            val reply = request(hub, obj {
                put("type", str("forward")); put("to", str(pubkey)); put("msg", msg)
                put("from", str(ident.pubkey)); put("item", msg["iou"] ?: JsonNull)
            }) ?: return null
            // TODO(phase 3): a live relayed delivery is the cue to try a direct UDP path (_try_direct).
            return if (reply.truthy("unreachable")) null else reply
        }
        // TODO(phase 3): a punched UDP path to pubkey, if we have one, is tried first.
        return request(addr, msg)
    }

    private fun peerTargets(skip: Set<String>): List<Pair<String, String?>> {
        val targets = LinkedHashSet<Pair<String, String?>>()
        for (r in phonebook.all()) if (r.addrs.isNotEmpty() && r.pubkey !in skip) targets.add(r.addrs[0] to r.pubkey)
        val known = targets.map { it.first }.toSet()
        for (b in bootstrap) if (b !in known) targets.add(b to null)
        return targets.toList()
    }

    /** Send msg to whoever holds pubkey, looking up a fresher address if the known one is dead. */
    private suspend fun requestPeer(pubkey: String, msg: JsonObject): JsonObject? {
        val rec = phonebook.get(pubkey)
        val reply = if (rec != null) tryAddrs(rec, msg) else null
        if (reply != null) return reply
        // Unreachable at the last known address: maybe they moved while we weren't listening.
        val oldSeq = rec?.seq ?: -1
        val fresh = refresh(pubkey)
        if (fresh != null && fresh.seq > oldSeq) {
            log("${fresh.name} moved, now at ${fresh.addrs.joinToString(", ")}")
            return tryAddrs(fresh, msg)
        }
        return null
    }

    /**
     * Try each address in turn. An IOU reply means "they're away": keep going, so every hub they use holds
     * an IOU and whichever one they reconnect to nudges us.
     */
    private suspend fun tryAddrs(rec: Record, msg: JsonObject): JsonObject? {
        var iou: JsonObject? = null
        for (addr in rec.addrs) {
            val reply = req(addr, rec.pubkey, msg) ?: continue
            if (reply.truthy("iou")) {
                iou = iou ?: reply
                continue
            }
            return reply
        }
        return iou
    }

    // --- phonebook -------------------------------------------------------------------------------

    /** Say hello to one address and absorb its whole phonebook. */
    suspend fun join(addr: String): Boolean {
        val reply = request(addr, obj { put("type", str("hello")); put("record", me!!.toJson()) })
        if (reply == null || !reply.truthy("ok")) {
            log("could not reach $addr")
            return false
        }
        val recs = ArrayList<Record?>()
        recs.add(Record.fromJson(reply["record"]))
        (reply["records"] as? JsonArray)?.forEach { recs.add(Record.fromJson(it)) }
        val learned = phonebook.mergeAll(recs)
        log("joined via $addr, learned $learned record(s)")
        return true
    }

    suspend fun announce() = gossip(me!!, emptySet())

    /** Flood rec to every peer we know (plus bootstrap nodes). Dedup happens on receipt via seq. */
    private suspend fun gossip(rec: Record, exclude: Set<String>) {
        val msg = obj { put("type", str("announce")); put("record", rec.toJson()); put("from", str(ident.pubkey)) }
        // The record's owner is included on purpose: their *other* devices need to hear it.
        val targets = peerTargets(exclude + ident.pubkey)
        coroutineScope { targets.map { (a, pk) -> async { req(a, pk, msg) } }.awaitAll() }
    }

    /** Ask everyone we know (and the bootstrap phonebooks) for a fresher record of pubkey. */
    suspend fun refresh(pubkey: String): Record? {
        val msg = obj { put("type", str("lookup")); put("pubkey", str(pubkey)) }
        val targets = peerTargets(setOf(ident.pubkey, pubkey))
        val replies = coroutineScope { targets.map { (a, pk) -> async { req(a, pk, msg) } }.awaitAll() }
        for (reply in replies) reply?.get("record")?.let { phonebook.merge(Record.fromJson(it)) }
        return phonebook.get(pubkey)
    }

    /** Their X25519 key, looking it up if we don't have it; null if they never published one. */
    private suspend fun encKey(pubkey: String): String? {
        var rec = phonebook.get(pubkey)
        if (rec?.enc == null) rec = refresh(pubkey)
        return rec?.enc
    }

    // --- direct messages -------------------------------------------------------------------------

    /**
     * Deliver something that's worth delivering later. Returns "delivered", "waiting" (they're offline: it's
     * in our outbox, and their hubs hold an IOU), or null (they refused it).
     */
    private suspend fun deliver(pubkey: String, msg: JsonObject, itemId: String): String? {
        val withIou = JsonObject(msg + ("iou" to str(itemId)))
        val reply = requestPeer(pubkey, withIou)
        if (reply != null && reply.truthy("ok") && !reply.truthy("iou")) return "delivered"
        if (reply == null || reply.truthy("iou")) {
            outbox.add(pubkey, withIou, itemId)
            return "waiting"
        }
        return null
    }

    /** Sign fields (plus a fresh message id), seal them to pubkey and deliver as [kind]. Returns (result, inner). */
    private suspend fun sendSealed(kind: String, pubkey: String, fields: Map<String, kotlinx.serialization.json.JsonElement>, id: String? = null): Pair<String?, JsonObject> {
        val unsigned = JsonObject(mapOf(
            "from" to str(ident.pubkey), "to" to str(pubkey), "ts" to Canonical.now(),
            "id" to str(id ?: newMessageId()),
        ) + fields)
        val inner = JsonObject(unsigned + ("sig" to str(ident.sign(Canonical.encode(unsigned)))))
        val encPub = encKey(pubkey)
        if (encPub == null) {
            log("no encryption key for ${short(pubkey)} yet (they need to come online once)")
            return null to inner
        }
        val sealed = obj { put("type", str(kind)); put("to", str(pubkey)); put("sealed", seal(encPub, Canonical.encode(inner))) }
        return deliver(pubkey, sealed, "$kind:${inner["id"].asString()}") to inner
    }

    /** Decrypt and verify a sealed request addressed to us; null if it isn't valid. */
    private fun openSealedMsg(msg: JsonObject): JsonObject? {
        val plain = openSealed(enc, msg["sealed"]) ?: return null
        val inner = parseObject(plain.toString(Charsets.UTF_8)) ?: return null
        val from = inner["from"].asString() ?: return null
        val sig = inner["sig"].asString() ?: return null
        if (inner["to"].asString() != ident.pubkey) return null
        val body = JsonObject(inner.filterKeys { it != "sig" })
        return if (verify(from, Canonical.encode(body), sig)) inner else null
    }

    /**
     * Returns "delivered", "waiting" (in our outbox until they're back), or null. Messaging someone makes
     * them a trusted contact (unless you've blocked them). [id] lets the caller know the message id up front.
     */
    suspend fun send(pubkey: String, text: String, id: String? = null): String? {
        if (trust.get(pubkey) == "unknown") trust.set(pubkey, "trusted")
        val (result, inner) = sendSealed("msg", pubkey, mapOf("text" to str(text)), id)
        if (result != null) dms.add(pubkey, inner)
        if (result == "delivered") {
            dms.markDelivered(pubkey, inner["id"].asString()!!)
            seen(pubkey)
        }
        return result
    }

    /** For a DM we sent: "waiting" (in our outbox), "delivered", "read", or null. */
    fun messageStatus(pubkey: String, message: JsonObject): String? {
        if (outbox.has("msg:${message["id"].asString()}")) return "waiting"
        return dms.receipt(pubkey, message)
    }

    // --- presence --------------------------------------------------------------------------------
    // TODO(phase 2): send typing/read signals, _presence_round (only the receiving side exists here).

    private fun seen(pubkey: String) {
        val wasOnline = online(pubkey)
        lastSeen[pubkey] = Canonical.nowSeconds()
        if (!wasOnline && trust.trusted(pubkey)) onEvent?.invoke("presence", pubkey, 1)
    }

    fun online(pubkey: String): Boolean = Canonical.nowSeconds() - (lastSeen[pubkey] ?: 0.0) < ONLINE_WINDOW

    // --- catching up -----------------------------------------------------------------------------

    /** Try to deliver what we owe people. Returns how many items got through. */
    suspend fun flushOutbox(peer: String? = null): Int {
        var sent = 0
        for (pk in if (peer != null) listOf(peer) else outbox.peers().sorted()) {
            if (!flushing.add(pk)) continue
            try {
                for ((itemId, item) in outbox.forPeer(pk).sortedBy { it.second.ts }) {
                    val reply = requestPeer(pk, item.msg)
                    if (reply == null || reply.truthy("iou")) break  // still away; their hubs hold IOUs again
                    outbox.remove(itemId)  // delivered (or refused: don't retry forever)
                    if (reply.truthy("ok")) {
                        sent++
                        if (itemId.startsWith("msg:")) {
                            dms.markDelivered(pk, itemId.substring(4))
                            onEvent?.invoke("receipt", pk, 1)
                        }
                    }
                }
            } finally {
                flushing.remove(pk)
            }
        }
        if (sent > 0) log("delivered $sent item(s) that were waiting")
        return sent
    }

    /** Swap missing messages with [peer]; returns how many we gained. Messages are verified against their author's signature. */
    suspend fun syncDm(peer: String): Int {
        val req = obj {
            put("from", str(ident.pubkey)); put("to", str(peer)); put("ts", Canonical.now())
            put("have", stringArray(dms.ids(peer)))
        }
        val reply = requestPeer(peer, obj {
            put("type", str("dm_sync")); put("req", req); put("sig", str(ident.sign(Canonical.encode(req))))
        })
        val plain = if (reply != null && reply.truthy("ok")) openSealed(enc, reply["sealed"]) else null
        val messages = (parseObject(plain?.toString(Charsets.UTF_8) ?: return 0)?.get("messages") as? JsonArray) ?: return 0
        var new = 0
        for (el in messages) {
            val m = el as? JsonObject ?: continue
            val from = m["from"].asString() ?: continue
            val to = m["to"].asString() ?: continue
            val sig = m["sig"].asString() ?: continue
            if (m["id"].asString() == null) continue
            if (setOf(from, to) != setOf(ident.pubkey, peer)) continue
            if (!verify(from, Canonical.encode(JsonObject(m.filterKeys { it != "sig" })), sig)) continue
            if (dms.add(peer, m)) {
                new++
                if (from == peer) onMessage?.invoke(phonebook.get(peer), m["text"].asString() ?: "", m)
            }
        }
        return new
    }

    /** Fill gaps in every chat, and pull chats from contacts we have no history with yet. */
    suspend fun syncDms(): Int {
        val contacts = trust.keys("trusted")
        val peers = (dms.peers().toSet() + contacts).filter { !trust.blocked(it) && it != ident.pubkey }
        return coroutineScope { peers.map { async { syncDm(it) } }.awaitAll().sum() }
    }

    // --- incoming --------------------------------------------------------------------------------

    /** Another device of ours published our record: keep its addresses in ours too. Re-announce only if that added something. */
    private fun adoptDeviceAddrs(rec: Record) {
        val current = addrs
        val extra = rec.addrs.filter { it !in current }
        if (extra.isNotEmpty()) {
            deviceAddrs.addAll(extra)
            log("another device of yours is at ${extra.joinToString(", ")}")
        }
        val missing = addrs.filter { it !in rec.addrs }  // their record dropped some of ours
        if (extra.isNotEmpty() || missing.isNotEmpty()) {
            refreshRecord()
            spawn { announce() }
        }
        // TODO(phase 2): spawn { syncDevices() } -- another device of ours is around: compare notes.
    }

    /** They're reachable again: deliver what we owe them and fill gaps in our chat. */
    private suspend fun peerOnline(pubkey: String) {
        if (pubkey in outbox.peers()) flushOutbox(pubkey)
        if (pubkey in dms.peers() && !trust.blocked(pubkey)) syncDm(pubkey)
    }

    private fun clean(msg: JsonObject) = if ("_udp_src" in msg) JsonObject(msg.filterKeys { it != "_udp_src" }) else msg

    suspend fun handle(raw: JsonObject): JsonObject {
        val msg = clean(raw)
        val kind = msg["type"].asString()
        when (kind) {
            "hello" -> {
                val rec = Record.fromJson(msg["record"])
                if (rec != null && rec.pubkey == ident.pubkey) {
                    if (phonebook.merge(rec)) adoptDeviceAddrs(rec)
                } else if (rec != null && phonebook.merge(rec)) {
                    log("met ${rec.name} (${short(rec.pubkey)}) at ${rec.addrs.joinToString(", ")}")
                    spawn { gossip(rec, emptySet()) }
                    spawn { peerOnline(rec.pubkey) }
                }
                return obj {
                    put("ok", JsonPrimitive(true)); put("record", me!!.toJson())
                    put("records", JsonArray(phonebook.all().map { it.toJson() }))
                }
            }

            "announce" -> {
                val rec = Record.fromJson(msg["record"])
                if (rec != null && rec.pubkey == ident.pubkey) {
                    if (phonebook.merge(rec)) adoptDeviceAddrs(rec)
                } else if (rec != null && phonebook.merge(rec)) {
                    log("${rec.name} (${short(rec.pubkey)}) is now at ${rec.addrs.joinToString(", ")}")
                    val from = msg["from"].asString() ?: "null"
                    spawn { gossip(rec, setOf(from)) }
                    spawn { peerOnline(rec.pubkey) }  // a fresh record means they're (back) online
                    seen(rec.pubkey)
                }
                return OK
            }

            "lookup" -> {
                val rec = phonebook.get(msg["pubkey"].asString() ?: "null")
                return obj { put("ok", JsonPrimitive(true)); put("record", rec?.toJson() ?: JsonNull) }
            }

            "nudge" -> {
                val peer = msg["peer"].asString() ?: "null"
                if (peer != ident.pubkey) spawn { peerOnline(peer) }
                return OK
            }

            "owed" -> {
                val owed = msg["from"] as? JsonObject ?: JsonObject(emptyMap())
                for ((sender, items) in owed) {
                    if (trust.blocked(sender) || items !is JsonArray) continue
                    val seenSet = owedSeen.getOrPut(sender) { ConcurrentHashMap.newKeySet() }
                    val waiting = items.mapNotNull { it.asString() }.filter { id ->
                        id !in seenSet && !(id.startsWith("msg:") && dms.has(sender, id.substring(4)))
                    }.distinct()
                    // the same IOU held by several hubs is reported once
                    val fresh = waiting.filter { seenSet.add(it) }
                    if (fresh.isNotEmpty()) {
                        log("${short(sender)} has ${fresh.size} thing(s) for you; they'll arrive when they are online")
                        onEvent?.invoke("owed", sender, fresh.size)
                    }
                }
                return OK
            }

            "dm_sync" -> {
                val req = msg["req"] as? JsonObject ?: JsonObject(emptyMap())
                val sig = msg["sig"].asString() ?: "null"
                val asker = req["from"].asString() ?: "null"
                val ts = req["ts"].asDouble()
                val fresh = ts != null && abs(Canonical.nowSeconds() - ts) <= SYNC_MAX_AGE
                if (!fresh || req["to"].asString() != ident.pubkey || trust.blocked(asker) ||
                    !verify(asker, Canonical.encode(req), sig)) return failed("not allowed")
                val have = (req["have"] as? JsonArray)?.mapNotNull { it.asString() }?.toSet() ?: emptySet()
                val missing = dms.missingFor(asker, have)
                val askerEnc = encKey(asker) ?: return failed("no key for you")
                return obj {
                    put("ok", JsonPrimitive(true))
                    put("sealed", seal(askerEnc, Canonical.encode(obj { put("messages", JsonArray(missing)) })))
                }
            }

            "signal" -> {
                val inner = openSealedMsg(msg)
                val sender = inner?.get("from").asString()
                if (inner == null || sender == null || !trust.trusted(sender)) return failed("not allowed")
                seen(sender)
                when (inner["signal"].asString()) {
                    "typing" -> {
                        typing[sender] = Canonical.nowSeconds() + TYPING_SHOWN
                        onEvent?.invoke("typing", sender, 0)
                    }
                    "read" -> inner["upto"].asDouble()?.let { if (dms.markRead(sender, it)) onEvent?.invoke("receipt", sender, 0) }
                }
                return OK
            }

            "msg" -> {
                val inner = openSealedMsg(msg) ?: return failed("bad message")
                val from = inner["from"].asString()!!
                if (trust.blocked(from)) return OK  // dropped; don't tell them they're blocked
                seen(from)
                typing.remove(from)  // they finished typing
                val stored = if ("id" in inner) inner else JsonObject(inner + ("id" to str(legacyId(inner))))
                if (!dms.add(from, stored)) return OK  // already have it (a retry, or an IOU redeemed via another hub)
                onMessage?.invoke(phonebook.get(from), stored["text"].asString() ?: "", stored)
                return OK
            }
        }
        // TODO(phase 2): device_sync, vault_put, vault_get. (phase 3): group_*, file_offer, file_get, group_handoff, punch, forward.
        return failed("unknown type ${kind?.let { "'$it'" } ?: "None"}")
    }

    private fun newMessageId(): String = b64(ByteArray(12).also { secureRandom.nextBytes(it) })
}
