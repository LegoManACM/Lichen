package dev.lichen.app

import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.LichenSetup
import dev.lichen.core.api.Settings
import dev.lichen.core.markLinked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

data class ProfileInfo(val id: String, val name: String, val linked: Boolean)

/**
 * All the identities on this phone. Every profile has an opened ChatClient in [clients]; LichenService
 * starts them all so every profile keeps receiving. [active] is the one the UI shows.
 */
class Profiles(root: File, private val setup: LichenSetup) {
    private val store = ProfileStore(root, setup)

    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active

    private val _infos = MutableStateFlow<List<ProfileInfo>>(emptyList())
    val infos: StateFlow<List<ProfileInfo>> = _infos

    private val _clients = MutableStateFlow<Map<String, ChatClient>>(emptyMap())
    /** Opened (not necessarily started) client of every profile, in profile order. */
    val clients: StateFlow<Map<String, ChatClient>> = _clients

    init { reload() }

    fun name(id: String): String? = _infos.value.firstOrNull { it.id == id }?.name

    /** Re-read the index, open clients for new profiles, keep the ones already open. */
    @Synchronized
    private fun reload() {
        val idx = store.load()
        val old = _clients.value
        _clients.value = LinkedHashMap<String, ChatClient>().also { m ->
            for (id in idx.order) (old[id] ?: runCatching { setup.open(store.dir(id)) }.getOrNull())?.let { m[id] = it }
        }
        _infos.value = idx.order.map {
            ProfileInfo(it, setup.settings(store.dir(it))?.name?.ifBlank { null } ?: "Profile", store.isLinked(it))
        }
        _active.value = idx.active
    }

    fun switchTo(id: String) {
        store.setActive(id)
        _active.value = id
    }

    /** A new empty data folder for onboarding: returns its profile id. Finish with [finishAdd] or [cancelAdd]. */
    fun beginAdd(): String = store.begin()

    fun dir(id: String): File = store.dir(id)

    /** Onboarding put an identity into the folder: make it a profile and switch to it. */
    fun finishAdd(id: String): Boolean {
        if (!store.commit(id)) return false
        reload()
        return true
    }

    fun cancelAdd(id: String) = store.discard(id)

    /** Stop the profile's client, delete its folder, and move on to another profile (if any). */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        _clients.value[id]?.let { runCatching { it.stop() } }
        store.delete(id)
        forget(id)
    }

    /**
     * Write profile [id]'s identity export (see [LichenSetup.export]) to [out]. Its folder is then marked linked:
     * there is (or soon will be) another device with this identity.
     */
    suspend fun export(id: String, passphrase: String, out: OutputStream) = withContext(Dispatchers.IO) {
        val text = setup.export(store.dir(id), passphrase)
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        markLinked(store.dir(id))
        reload()
    }

    /** New name/hubs for a profile: stop its client, save, open a fresh one (the service starts it). */
    suspend fun saveSettings(id: String, settings: Settings) = withContext(Dispatchers.IO) {
        _clients.value[id]?.let { runCatching { it.stop() } }
        setup.saveSettings(store.dir(id), settings)
        forget(id)
    }

    @Synchronized
    private fun forget(id: String) {
        _clients.value = _clients.value - id
        reload()
    }
}
