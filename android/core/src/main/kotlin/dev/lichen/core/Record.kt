package dev.lichen.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Contact record: a signed "this key currently lives at these addresses" statement. Self-certifying,
 * so any node can store and forward anyone's record; a forged one fails [valid], a stale one loses to
 * a higher [seq]. An address is "HOST:PORT" or "relay:HOST:PORT". [enc] is the X25519 public key.
 */
data class Record(
    val pubkey: String,
    val name: String,
    val addrs: List<String>,
    val seq: Long,
    val enc: String? = null,
    var sig: String = "",
) {
    /** The signed part. */
    fun body(): JsonObject = obj {
        put("pubkey", JsonPrimitive(pubkey))
        put("name", JsonPrimitive(name))
        put("addrs", stringArray(addrs))
        put("seq", JsonPrimitive(seq))
        if (enc != null) put("enc", JsonPrimitive(enc))
    }

    fun valid(): Boolean = verify(pubkey, Canonical.encode(body()), sig)

    fun toJson(): JsonObject = JsonObject(body() + ("sig" to JsonPrimitive(sig)))

    companion object {
        /** seq is the microsecond clock, so it survives restarts without a stored counter. */
        fun create(ident: Identity, name: String, addrs: List<String>, enc: String, prevSeq: Long = 0): Record {
            val seq = maxOf(System.currentTimeMillis() * 1000, prevSeq + 1)
            val rec = Record(ident.pubkey, name, addrs.toList(), seq, enc)
            rec.sig = ident.sign(Canonical.encode(rec.body()))
            return rec
        }

        /** Parse and verify; null if malformed or the signature doesn't check out. */
        fun fromJson(e: JsonElement?): Record? {
            val d = e as? JsonObject ?: return null
            val pubkey = d["pubkey"].asString() ?: return null
            val name = d["name"].asString() ?: return null
            val addrs = (d["addrs"] as? JsonArray)?.map { it.asString() ?: return null } ?: return null
            val seq = d["seq"].asLong() ?: return null
            val encEl = d["enc"]
            val enc = if (encEl == null || encEl is JsonNull) null else encEl.asString() ?: return null
            val sig = d["sig"].asString() ?: return null
            return Record(pubkey, name, addrs, seq, enc, sig).takeIf { it.valid() }
        }
    }
}
