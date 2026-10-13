package dev.lichen.core.store

import dev.lichen.core.Record
import dev.lichen.core.dumps
import dev.lichen.core.parseJson
import dev.lichen.core.writeFileAtomic
import kotlinx.serialization.json.JsonArray
import java.io.File

// Every node keeps a phonebook of verified records, newest seq wins. Same phonebook.json as Python
// (a JSON list of record objects). Thread-safe; [onChange] runs after a change, outside the lock.

class Phonebook(private val file: File? = null) {
    private val lock = Any()
    private val records = LinkedHashMap<String, Record>()
    var onChange: (() -> Unit)? = null

    init {
        if (file != null && file.exists()) {
            (parseJson(file.readText(Charsets.UTF_8)) as? JsonArray)?.forEach { d ->
                Record.fromJson(d)?.let { records[it.pubkey] = it }  // unverifiable entries are dropped
            }
        }
    }

    /** Store [rec] if it is valid and newer than what we have. True if it changed anything. */
    fun merge(rec: Record?): Boolean {
        if (rec == null || !rec.valid()) return false
        synchronized(lock) {
            val cur = records[rec.pubkey]
            if (cur != null && cur.seq >= rec.seq) return false
            records[rec.pubkey] = rec
            save()
        }
        onChange?.invoke()
        return true
    }

    /** [merge] for many records with one save at the end. Returns how many changed anything. */
    fun mergeAll(recs: Iterable<Record?>): Int {
        var n = 0
        synchronized(lock) {
            for (rec in recs) {
                if (rec == null || !rec.valid()) continue
                val cur = records[rec.pubkey]
                if (cur == null || cur.seq < rec.seq) { records[rec.pubkey] = rec; n++ }
            }
            if (n > 0) save()
        }
        if (n > 0) onChange?.invoke()
        return n
    }

    fun get(pubkey: String): Record? = synchronized(lock) { records[pubkey] }

    /** Match by exact name, or by public key prefix. */
    fun find(query: String): List<Record> = synchronized(lock) { records.values.filter { it.name == query || it.pubkey.startsWith(query) } }

    fun all(): List<Record> = synchronized(lock) { records.values.toList() }

    fun save() = synchronized(lock) { saveLocked() }

    private fun saveLocked() {
        file?.let { writeFileAtomic(it, dumps(JsonArray(records.values.map { r -> r.toJson() }))) }
    }
}
