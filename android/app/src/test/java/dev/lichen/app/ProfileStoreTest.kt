package dev.lichen.app

import dev.lichen.core.api.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** An identity is a folder with a `key` file. */
    private val setup = object : LichenSetup {
        override fun hasIdentity(dataDir: File) = File(dataDir, "key").exists()
        override fun settings(dataDir: File): Settings? = null
        override fun saveSettings(dataDir: File, settings: Settings) {}
        override fun create(dataDir: File, settings: Settings) {}
        override fun import(dataDir: File, exportText: String, passphrase: String): SetupResult = TODO()
        override fun open(dataDir: File): ChatClient = TODO()
    }

    private var n = 0
    private fun store(root: File) = ProfileStore(root, setup) { "p${n++}" }

    private fun ProfileStore.addProfile(linked: Boolean = false): String {
        val id = begin()
        File(dir(id), "key").writeText("k$id")
        if (linked) File(dir(id), "linked").writeText("")
        assertTrue(commit(id))
        return id
    }

    @Test fun emptyRootHasNoProfiles() {
        val s = store(tmp.newFolder())
        assertEquals(ProfileStore.Index(null, emptyList()), s.load())
    }

    @Test fun addingProfilesKeepsOrderAndActivatesTheNewest() {
        val root = tmp.newFolder()
        val s = store(root)
        val a = s.addProfile()
        val b = s.addProfile()
        assertEquals(ProfileStore.Index(b, listOf(a, b)), s.load())
        s.setActive(a)
        // a fresh store sees the same thing: the index file is the truth
        assertEquals(ProfileStore.Index(a, listOf(a, b)), store(root).load())
        assertTrue(File(root, "profiles.json").readText().contains("\"order\""))
    }

    @Test fun commitWithoutIdentityFails() {
        val s = store(tmp.newFolder())
        val id = s.begin()
        assertFalse(s.commit(id))
        assertEquals(emptyList<String>(), s.load().order)
    }

    @Test fun cancelledAddRemovesTheFolder() {
        val s = store(tmp.newFolder())
        val id = s.begin()
        assertTrue(s.dir(id).exists())
        s.discard(id)
        assertFalse(s.dir(id).exists())
    }

    @Test fun leftoverEmptyFoldersAreCleanedUpOnLoad() {
        val s = store(tmp.newFolder())
        val keep = s.addProfile()
        val stale = s.begin()
        assertEquals(listOf(keep), s.load().order)
        assertFalse(s.dir(stale).exists())
    }

    @Test fun oldSingleProfileLayoutIsMigrated() {
        val root = tmp.newFolder()
        File(root, "key").writeText("secret")
        File(root, "linked").writeText("")
        File(root, "dms").mkdirs()
        File(root, "dms/x.json").writeText("{}")
        val s = store(root)
        val idx = s.load()
        assertEquals(1, idx.order.size)
        val id = idx.order[0]
        assertEquals(id, idx.active)
        assertEquals("secret", File(s.dir(id), "key").readText())
        assertEquals("{}", File(s.dir(id), "dms/x.json").readText())
        assertTrue(s.isLinked(id))
        assertFalse(File(root, "key").exists())
        assertFalse(File(root, "dms").exists())
        // loading again changes nothing
        assertEquals(idx, store(root).load())
    }

    @Test fun deleteRemovesFolderAndMovesActiveOn() {
        val root = tmp.newFolder()
        val s = store(root)
        val a = s.addProfile(linked = true)
        val b = s.addProfile()
        val c = s.addProfile()
        assertTrue(s.isLinked(a))
        assertFalse(s.isLinked(b))

        // deleting a profile that is not active keeps the active one
        s.setActive(a)
        assertEquals(ProfileStore.Index(a, listOf(a, c)), s.delete(b))
        assertFalse(s.dir(b).exists())

        // deleting the active one switches to another
        val idx = s.delete(a)
        assertFalse(s.dir(a).exists())
        assertEquals(ProfileStore.Index(c, listOf(c)), idx)
        assertEquals(idx, store(root).load())

        // deleting the last one leaves nothing
        assertEquals(ProfileStore.Index(null, emptyList()), s.delete(c))
        assertEquals(ProfileStore.Index(null, emptyList()), store(root).load())
    }

    @Test fun deleteRemovesNestedFiles() {
        val s = store(tmp.newFolder())
        val a = s.addProfile()
        File(s.dir(a), "deep/er").mkdirs()
        File(s.dir(a), "deep/er/f").writeText("x")
        s.delete(a)
        assertFalse(s.dir(a).exists())
    }
}
