package dev.lichen.app

import dev.lichen.core.api.LichenSetup
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where the profiles live on disk (no Android APIs, so it is unit-tested on the JVM):
 *
 *     <root>/profiles.json            {"active": id, "order": [ids]}
 *     <root>/profiles/<id>/           one Lichen data folder per identity
 *
 * An old single-profile app kept its identity directly in <root>; [load] moves that into a profile.
 */
class ProfileStore(
    private val root: File,
    private val setup: LichenSetup,
    private val newId: () -> String = ::randomProfileId,
) {
    data class Index(val active: String?, val order: List<String>)

    private val profilesDir = File(root, "profiles")
    private val indexFile = File(root, "profiles.json")

    fun dir(id: String): File = File(profilesDir, id)

    /** An identity that was imported from, or exported to, another device (the `linked` marker file). */
    fun isLinked(id: String): Boolean = File(dir(id), "linked").exists()

    /** Migrates an old layout, reconciles the index with the folders on disk and returns the result. */
    @Synchronized
    fun load(): Index {
        root.mkdirs()
        var idx = readIndex()
        val migrated = migrate()
        if (migrated != null) idx = Index(idx.active ?: migrated, idx.order + migrated)

        val onDisk = profilesDir.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted().orEmpty()
        val valid = onDisk.filter { setup.hasIdentity(dir(it)) }
        // Folders without an identity that are not in the index are leftovers of a cancelled add.
        onDisk.filter { it !in valid && it !in idx.order }.forEach { dir(it).deleteRecursively() }
        val order = idx.order.filter { it in valid }.distinct() + valid.filter { it !in idx.order }
        val active = idx.active?.takeIf { it in order } ?: order.firstOrNull()
        val result = Index(active, order)
        if (result != idx || !indexFile.exists()) writeIndex(result)
        return result
    }

    /** A new, empty data folder for a profile that is about to be set up. Not in the index until [commit]. */
    @Synchronized
    fun begin(): String {
        var id = newId()
        while (dir(id).exists()) id = newId()
        check(dir(id).mkdirs()) { "Could not create ${dir(id)}" }
        return id
    }

    /** The folder now holds an identity: add it to the index and make it the active profile. */
    @Synchronized
    fun commit(id: String): Boolean {
        if (!setup.hasIdentity(dir(id))) return false
        val idx = readIndex()
        writeIndex(Index(id, idx.order.filter { it != id } + id))
        return true
    }

    /** Throw away a folder from [begin] that never became a profile. */
    @Synchronized
    fun discard(id: String) {
        if (id !in readIndex().order) dir(id).deleteRecursively()
    }

    @Synchronized
    fun setActive(id: String) {
        val idx = readIndex()
        if (id in idx.order) writeIndex(idx.copy(active = id))
    }

    /** Deletes the folder (all of it), then drops the profile from the index. Returns the new index. */
    @Synchronized
    fun delete(id: String): Index {
        val idx = readIndex()
        val d = dir(id)
        if (d.exists() && !d.deleteRecursively()) throw java.io.IOException("Could not delete $d")
        val pos = idx.order.indexOf(id)
        val order = idx.order.filter { it != id }
        val active = if (idx.active != null && idx.active != id && idx.active in order) idx.active
        else order.getOrNull(pos.coerceAtLeast(0)) ?: order.lastOrNull()
        return Index(active, order).also { writeIndex(it) }
    }

    private fun migrate(): String? {
        if (!setup.hasIdentity(root)) return null
        val id = begin()
        val target = dir(id)
        root.listFiles()?.filter { it.name != "profiles" && it.name != "profiles.json" }?.forEach {
            Files.move(it.toPath(), File(target, it.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return id
    }

    private fun readIndex(): Index = try {
        val o = Json.parseToJsonElement(indexFile.readText()).jsonObject
        Index(
            o["active"]?.jsonPrimitive?.contentOrNull,
            o["order"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )
    } catch (_: Exception) {
        Index(null, emptyList())
    }

    private fun writeIndex(idx: Index) {
        val text = buildJsonObject {
            put("active", idx.active?.let { JsonPrimitive(it) } ?: JsonNull)
            put("order", JsonArray(idx.order.map { JsonPrimitive(it) }))
        }.toString()
        val tmp = File(root, "profiles.json.tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

fun randomProfileId(): String {
    val b = ByteArray(4)
    java.security.SecureRandom().nextBytes(b)
    return b.joinToString("") { "%02x".format(it) }
}
