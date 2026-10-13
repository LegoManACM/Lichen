package dev.lichen.core

import dev.lichen.core.api.Settings
import dev.lichen.core.api.SetupResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun tmpDir(): File = createTempDirectory("lichen").toFile().also { it.deleteOnExit() }

class KeyFileTest {
    private val kf = Vectors.obj("keyfile")

    @Test fun `reads Python key files`() {
        val secret = unb64(kf["secret"].str())
        assertContentEquals(secret, readSecret(kf["plain"].str()))
        assertContentEquals(secret, readSecret(kf["plain"].str(), "ignored"))
        assertContentEquals(secret, readSecret(kf["encrypted"].str(), kf["passphrase"].str()))
        assertContentEquals(secret, readSecret(kf["encrypted"].str() + "\n", kf["passphrase"].str()))
    }

    @Test fun `wrong or missing passphrase`() {
        assertFailsWith<WrongPassphrase> { readSecret(kf["encrypted"].str(), "nope") }
        assertFailsWith<PassphraseNeeded> { readSecret(kf["encrypted"].str()) }
    }

    @Test fun `write is atomic and round trips`() {
        val d = tmpDir(); val f = File(d, "sub/key")
        val secret = ByteArray(32) { it.toByte() }
        writeSecret(f, secret)
        assertContentEquals(secret, readSecret(f.readText()))
        writeSecret(f, secret, "pw")
        assertTrue(f.readText().startsWith(KEY_PREFIX))
        assertContentEquals(secret, readSecret(f.readText(), "pw"))
        assertEquals(listOf("key"), f.parentFile.list()!!.toList())  // no .tmp left behind
    }

    @Test fun `Python reads Kotlin key files`() {
        Py.require()
        val d = tmpDir(); val secret = ByteArray(32) { (it * 3).toByte() }
        writeSecret(File(d, "plain"), secret); writeSecret(File(d, "enc"), secret, "päss")
        val out = Py.run("""
from pathlib import Path
from lichen.keyfile import read_secret
from lichen.identity import b64
d = Path(sys.stdin.read().strip())
print(b64(read_secret(d / "plain")), b64(read_secret(d / "enc", "päss")))
""", d.absolutePath)
        assertEquals("${b64(secret)} ${b64(secret)}", out.trim())
    }
}

class DevicesTest {
    private val ex = Vectors.obj("export")

    @Test fun `opens a real Python export`() {
        val bundle = openExport(ex["text"].str(), ex["passphrase"].str())
        assertEquals(ex["config"].str(), bundle["config"].str())
        assertEquals(ex["keys"].o()["key"].str(), bundle["keys"].o()["key"].str())
        assertEquals(ex["files"].o().keys, bundle["files"].o().keys)
    }

    @Test fun `wrong passphrase or not an export throws`() {
        assertFailsWith<IllegalArgumentException> { openExport(ex["text"].str(), "wrong") }
        assertFailsWith<IllegalArgumentException> { openExport("hello", "x") }
        assertFailsWith<IllegalArgumentException> { openExport("lichen-identity-v1:{garbage", "x") }
    }

    @Test fun `import writes the folder like Python would`() {
        val d = File(tmpDir(), "data")
        val yaml = importIdentity(ex["text"].str(), d, ex["passphrase"].str())
        assertEquals(ex["config"].str(), yaml)
        assertEquals(ex["pubkey"].str(), Identity.fromSeed(readSecret(File(d, "key").readText())).pubkey)
        assertContentEquals(unb64(ex["keys"].o()["enckey"].str()), readSecret(File(d, "enckey").readText()))
        for ((name, text) in ex["files"].o()) assertEquals(text.str(), File(d, name).readText(), name)
        assertTrue(isLinked(d))
        assertFailsWith<IllegalStateException> { importIdentity(ex["text"].str(), d, ex["passphrase"].str()) }
    }

    @Test fun `import without a bundled config returns null`() {
        val nc = ex["no_config"].o()
        assertNull(importIdentity(nc["text"].str(), File(tmpDir(), "d"), nc["passphrase"].str()))
    }

    @Test fun `device id is made once, random and not exported`() {
        val a = tmpDir(); val b = tmpDir()
        val id = deviceId(a)
        assertEquals(8, id.length)
        assertEquals(id, deviceId(a)); assertNotEquals(id, deviceId(b))
        assertFalse(isLinked(a)); markLinked(a); assertTrue(isLinked(a))
    }
}

class SetupTest {
    private val setup = FileSetup()
    private val ex = Vectors.obj("export")

    @Test fun `creates an identity folder Python could use`() {
        val d = File(tmpDir(), "data")
        assertFalse(setup.hasIdentity(d))
        setup.create(d, Settings("Phone", listOf("wss://h.example.com", "hub:9000")))
        assertTrue(setup.hasIdentity(d))
        assertEquals(Settings("Phone", listOf("wss://h.example.com", "hub:9000")), setup.settings(d))
        assertEquals("{\"name\":\"Phone\",\"hubs\":[\"wss://h.example.com\",\"hub:9000\"]}", File(d, "settings.json").readText())
        assertFalse(isEncrypted(File(d, "key").readText()))
        assertEquals(8, File(d, "device").readText().length)
        Py.require()
        val pk = Identity.loadOrCreate(File(d, "key")).pubkey
        val out = Py.run("""
from pathlib import Path
from lichen.identity import Identity
from lichen.crypto import EncKey
d = Path(sys.stdin.read().strip())
print(Identity.load_or_create(d / "key").pubkey, EncKey.load_or_create(d / "enckey").pub)
""", d.absolutePath).trim().split(" ")
        assertEquals(pk, out[0]); assertEquals(EncKey.loadOrCreate(File(d, "enckey")).pub, out[1])
    }

    @Test fun `imports an export with name and hubs from its yaml`() {
        val d = File(tmpDir(), "data")
        val r = assertIs<SetupResult.Ok>(setup.import(d, ex["text"].str(), ex["passphrase"].str()))
        assertEquals(Settings("alice", listOf("hub.example.org:9000", "wss://b.example.com")), r.settings)
        assertEquals(r.settings, setup.settings(d)); assertTrue(setup.hasIdentity(d)); assertTrue(isLinked(d))
        assertEquals(8, File(d, "device").readText().length)
    }

    @Test fun `import falls back on our phonebook record when the export has no yaml`() {
        val nc = ex["no_config"].o()
        val r = assertIs<SetupResult.Ok>(setup.import(File(tmpDir(), "d"), nc["text"].str(), nc["passphrase"].str()))
        assertEquals("alice", r.settings.name)
        assertEquals(listOf("hub.example.org:9000", "wss://b.example.com"), r.settings.hubs)
    }

    @Test fun `import failures are reported not thrown`() {
        val d = File(tmpDir(), "d")
        assertIs<SetupResult.Failed>(setup.import(d, ex["text"].str(), "wrong"))
        assertIs<SetupResult.Failed>(setup.import(d, "garbage", "x"))
        assertFalse(setup.hasIdentity(d))
        setup.import(d, ex["text"].str(), ex["passphrase"].str())
        assertIs<SetupResult.Failed>(setup.import(d, ex["text"].str(), ex["passphrase"].str()))
    }

    @Test fun `yaml reader handles scalars, flow and block lists, quotes and comments`() {
        val y = parseLichenYaml(
            "# top comment\nname: \"Bob # not a comment\"   # trailing\nrelay:\n  - wss://a.example.com  # one\n  - 'b.example.org:9000'\nport: 9000\n")
        assertEquals(LichenYaml("Bob # not a comment", listOf("wss://a.example.com", "b.example.org:9000")), y)
        assertEquals(LichenYaml("x", listOf("h:1")), parseLichenYaml("name: x\nrelay: h:1\n"))
        assertEquals(LichenYaml(null, listOf("a", "b:2")), parseLichenYaml("relay: [a, \"b:2\"]\n"))
        assertEquals(LichenYaml("n", listOf("a")), parseLichenYaml("relay:\n- a\nname: n\nbootstrap:\n  - ignored\n"))
        assertEquals(LichenYaml(null, emptyList()), parseLichenYaml("relay:\nname:\n# nothing\n"))
    }
}
