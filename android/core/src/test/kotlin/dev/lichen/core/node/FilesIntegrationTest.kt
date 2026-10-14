package dev.lichen.core.node

import dev.lichen.core.api.ClientEvent
import dev.lichen.core.api.FileLimits
import dev.lichen.core.api.FileTransfer
import dev.lichen.core.api.Trust
import dev.lichen.core.api.TransferStatus
import dev.lichen.core.asString
import dev.lichen.core.store.sha256File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** File transfer between the Kotlin node and real Python nodes (android/tools/py_peer.py). */
class FilesIntegrationTest {
    private fun <T> withPeer(body: (PyPeer) -> T): T = PyPeer(true).use(body)

    private fun PyPeer.sendFile(from: String, to: String, name: String, size: Int, seed: Int = 1, group: String? = null): JsonObject =
        call("file_sent", "cmd" to "send_file", "from" to from, "to" to to, "name" to name, "size" to size, "seed" to seed, "group" to group, timeoutMs = 60_000)

    private fun Phone.transfer(id: String): FileTransfer? = client.files.transfers.value.firstOrNull { it.id == id }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun start(phone: Phone) = runBlocking { phone.client.start(); phone.client.awaitCatchUp() }

    @Test
    fun pythonOffersAFileAndKotlinDownloadsIt() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            val sent = py.sendFile("user", phone.pubkey, "report.bin", 200_000)
            val id = sent["file_id"].asString()!!
            eventually("offer to show up") { phone.transfer(id) != null }
            val t = phone.transfer(id)!!
            assertEquals(TransferStatus.PENDING, t.status)
            assertEquals("report.bin", t.name)
            assertEquals(200_000L, t.size)
            assertEquals(py.userPk, t.from)
            val ev = phone.events.filterIsInstance<ClientEvent.FileOffer>().single()
            assertEquals("alice", ev.senderName)
            assertEquals(false, ev.trusted)

            val saved = runBlocking { phone.client.files.download(id) }
            assertNotNull(saved)
            assertEquals(sent["sha256"].asString(), sha256File(saved))
            assertEquals(200_000L, saved.length())
            assertEquals(TransferStatus.DONE, phone.transfer(id)!!.status)
            assertEquals(saved, phone.client.files.location(id))
            assertTrue(phone.events.any { it is ClientEvent.FileDownloaded })
            // A second download of a finished one does nothing.
            assertNull(runBlocking { phone.client.files.download(id) })
        } finally { phone.close() }
    }

    @Test
    fun kotlinOffersAFileAndPythonDownloadsIt() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            val content = ByteArray(130_000) { (it * 31 + it / 7).toByte() }
            val r = runBlocking { phone.client.files.send({ ByteArrayInputStream(content) }, "kotlin.bin", content.size.toLong(), listOf(py.userPk!!)) }
            assertEquals(1, r.delivered)
            assertEquals(TransferStatus.OFFERED, phone.transfer(r.fileId)!!.status)
            assertTrue(phone.transfer(r.fileId)!!.outgoing)
            eventually("python to see the offer") { r.fileId in (py.call("offers", "cmd" to "offers", "who" to "user")["incoming"] as JsonObject).keys }
            val got = py.call("file_downloaded", "cmd" to "accept_file", "who" to "user", "file_id" to r.fileId, timeoutMs = 60_000)
            assertEquals(sha(content), got["sha256"].asString())
            assertEquals(File(phone.client.files.location(r.fileId)!!.path).length(), content.size.toLong())  // our copy is still served
        } finally { phone.close() }
    }

    @Test
    fun groupFileOffersBothWays() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            val alice = py.userPk!!
            val gid = py.gcreate("user", "photos", listOf(phone.pubkey))
            eventually("invite") { phone.group(gid)?.pending == true }
            blocking { phone.groups.accept(gid) }.ok()
            eventually("accepted") { phone.group(gid)?.pending == false && py.gmembers("user", gid).contains(phone.pubkey) }

            // node.send_file(group=...): an offer per member with `group` set; it lands in the group, not the DM.
            val sent = py.sendFile("user", phone.pubkey, "group.bin", 20_000, group = gid)
            val id = sent["file_id"].asString()!!
            eventually("group offer") { phone.transfer(id) != null }
            val t = phone.transfer(id)!!
            assertEquals(gid, t.group)
            assertEquals(alice, t.from)
            assertEquals(TransferStatus.PENDING, t.status)
            assertEquals(sent["sha256"].asString(), sha256File(assertNotNull(runBlocking { phone.client.files.download(id) })))

            // And ours to the group: Python files it under the group too.
            val content = ByteArray(9_000) { it.toByte() }
            val r = runBlocking { phone.client.files.send({ ByteArrayInputStream(content) }, "mine.bin", content.size.toLong(), listOf(alice), gid) }
            assertEquals(1, r.delivered)
            assertEquals(gid, phone.transfer(r.fileId)!!.group)
            eventually("python to see the group offer") {
                ((py.call("offers", "cmd" to "offers", "who" to "user")["incoming"] as JsonObject)[r.fileId] as? JsonObject)?.get("group").asString() == gid
            }
            assertEquals(sha(content), py.call("file_downloaded", "cmd" to "accept_file", "who" to "user", "file_id" to r.fileId, timeoutMs = 60_000)["sha256"].asString())
        } finally { phone.close() }
    }

    @Test
    fun interruptedDownloadResumes() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            val sent = py.sendFile("user", phone.pubkey, "big.bin", 40_000_000)
            val id = sent["file_id"].asString()!!
            eventually("offer") { phone.transfer(id) != null }
            val t = Thread { runBlocking { phone.client.files.download(id) } }.also { it.start() }
            eventually("some progress", 30_000) { (phone.transfer(id)?.done ?: 0) > 0 }
            runBlocking { phone.client.stop() }  // the node goes away mid-download
            t.join(20_000)
            val part = File(phone.dir, "partial").listFiles()!!.single { it.name.endsWith(".part") }
            assertTrue(part.length() > 0 && part.length() < 40_000_000, "partial ${part.length()}")
            assertEquals(TransferStatus.PENDING, phone.transfer(id)!!.status)  // resumable, not failed

            phone.reopen()
            runBlocking { phone.client.start(); phone.client.awaitCatchUp() }
            assertEquals(TransferStatus.PENDING, phone.transfer(id)!!.status)
            val saved = runBlocking { phone.client.files.download(id) }
            assertNotNull(saved)
            assertEquals(sent["sha256"].asString(), sha256File(saved))
            assertTrue(!part.exists())
        } finally { phone.close() }
    }

    @Test
    fun declineIsKeptAndBlocksDownload() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            val id = py.sendFile("user", phone.pubkey, "nope.txt", 5_000)["file_id"].asString()!!
            eventually("offer") { phone.transfer(id) != null }
            phone.client.files.decline(id)
            assertEquals(TransferStatus.DECLINED, phone.transfer(id)!!.status)
            assertNull(runBlocking { phone.client.files.download(id) })
            runBlocking { phone.client.stop() }
            phone.reopen()
            eventually("declined after restart") { phone.transfer(id)?.status == TransferStatus.DECLINED }
        } finally { phone.close() }
    }

    @Test
    fun offersOverTheLimitAreRefused() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            phone.client.files.setLimits(FileLimits(maxMb = 0.1, autoDownloadMb = 0.0))
            val id = py.sendFile("user", phone.pubkey, "huge.bin", 300_000)["file_id"].asString()!!
            eventually("offer") { phone.transfer(id) != null }
            val t = phone.transfer(id)!!
            assertEquals(TransferStatus.REFUSED, t.status)
            assertTrue(t.error!!.contains("limit"), t.error)
            assertNull(runBlocking { phone.client.files.download(id) })
            assertEquals(TransferStatus.REFUSED, phone.transfer(id)!!.status)
            // A small one still works.
            val small = py.sendFile("user", phone.pubkey, "small.bin", 10_000, seed = 2)["file_id"].asString()!!
            eventually("small offer") { phone.transfer(small)?.status == TransferStatus.PENDING }
        } finally { phone.close() }
    }

    @Test
    fun offerToAnOfflinePeerWaitsInTheOutboxAndArrivesLater() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            val alice = py.userPk!!
            py.call("ok", "cmd" to "stop_user")
            eventually("alice detached") { alice !in py.hubAttached() }
            val content = ByteArray(70_000) { (it % 251).toByte() }
            val r = runBlocking { phone.client.files.send({ ByteArrayInputStream(content) }, "later.bin", content.size.toLong(), listOf(alice)) }
            assertEquals(0, r.delivered); assertEquals(1, r.waiting)
            assertEquals(TransferStatus.WAITING, phone.transfer(r.fileId)!!.status)
            assertEquals(1, phone.client.node()!!.outbox.size())
            val ious = (py.state()["ious"] as JsonObject)[alice].toString()
            assertTrue(ious.contains("file_offer:"), ious)

            py.call("ok", "cmd" to "start_user")
            eventually("python to get the offer") { r.fileId in (py.call("offers", "cmd" to "offers", "who" to "user")["incoming"] as JsonObject).keys }
            eventually("outbox drained and transfer offered") { phone.transfer(r.fileId)?.status == TransferStatus.OFFERED }
            assertEquals(0, phone.client.node()!!.outbox.size())
            assertEquals(sha(content), py.call("file_downloaded", "cmd" to "accept_file", "who" to "user", "file_id" to r.fileId)["sha256"].asString())
        } finally { phone.close() }
    }

    @Test
    fun offerFromPythonWhileKotlinIsOfflineArrivesOnStart() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            runBlocking { phone.client.stop() }
            eventually("phone detached") { phone.pubkey !in py.hubAttached() }
            val id = py.sendFile("user", phone.pubkey, "waiting.bin", 60_000)["file_id"].asString()!!
            runBlocking { phone.client.start() }
            eventually("offer to arrive") { phone.transfer(id) != null }
            assertNotNull(runBlocking { phone.client.files.download(id) })
        } finally { phone.close() }
    }

    @Test
    fun trustedContactsUnderTheAutoLimitDownloadByThemselves() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            phone.client.files.setLimits(FileLimits(maxMb = 100.0, autoDownloadMb = 0.5))
            // A stranger's small file waits.
            val stranger = py.sendFile("user", phone.pubkey, "stranger.bin", 20_000)["file_id"].asString()!!
            eventually("stranger offer") { phone.transfer(stranger) != null }
            Thread.sleep(500)
            assertEquals(TransferStatus.PENDING, phone.transfer(stranger)!!.status)

            runBlocking { phone.client.setTrust(py.userPk!!, Trust.TRUSTED) }
            val small = py.sendFile("user", phone.pubkey, "small.bin", 100_000, seed = 3)
            val big = py.sendFile("user", phone.pubkey, "bigger.bin", 800_000, seed = 4)
            eventually("auto download") { phone.transfer(small["file_id"].asString()!!)?.status == TransferStatus.DONE }
            assertEquals(small["sha256"].asString(), sha256File(phone.client.files.location(small["file_id"].asString()!!)!!))
            eventually("big offer") { phone.transfer(big["file_id"].asString()!!) != null }
            Thread.sleep(500)
            assertEquals(TransferStatus.PENDING, phone.transfer(big["file_id"].asString()!!)!!.status)
            assertTrue(phone.events.filterIsInstance<ClientEvent.FileOffer>().any { it.trusted })
        } finally { phone.close() }
    }

    @Test
    fun blockedSendersOffersAreDropped() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            runBlocking { phone.client.setTrust(py.userPk!!, Trust.BLOCKED) }
            py.sendFile("user", phone.pubkey, "blocked.bin", 1_000)
            Thread.sleep(500)
            assertTrue(phone.client.files.transfers.value.isEmpty())
        } finally { phone.close() }
    }

    @Test
    fun fileGetForUnknownFilesIsAnError() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            val reply = runBlocking { phone.client.node()!!.handle(dev.lichen.core.obj {
                put("type", kotlinx.serialization.json.JsonPrimitive("file_get")); put("file_id", kotlinx.serialization.json.JsonPrimitive("nope"))
                put("offset", kotlinx.serialization.json.JsonPrimitive(0))
            }) }
            assertEquals("unknown file", reply["error"].asString())
        } finally { phone.close() }
    }

    @Test
    fun fileOffersSyncWithASiblingDeviceAndTheDigestsAgree() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            start(phone)
            val alice = py.userPk!!
            py.call("ok", "cmd" to "start_sibling", "keydir" to phone.dir.absolutePath, "name" to "kate")
            val a = py.sendFile("user", phone.pubkey, "one.bin", 20_000, seed = 5)["file_id"].asString()!!
            val b = py.sendFile("user", phone.pubkey, "two.bin", 30_000, seed = 6)["file_id"].asString()!!
            eventually("phone has both offers") { phone.transfer(a) != null && phone.transfer(b) != null }
            phone.client.files.decline(b)
            eventually("sibling knows both offers") {
                val inc = py.call("offers", "cmd" to "offers", "who" to "sibling")["incoming"] as JsonObject
                a in inc.keys && b in inc.keys
            }
            eventually("sibling sees the decline and the digests agree", 30_000) {
                py.call("synced", "cmd" to "sync_devices", "who" to "sibling")
                val inc = py.call("offers", "cmd" to "offers", "who" to "sibling")["incoming"] as JsonObject
                (inc[b] as JsonObject)["status"].asString() == "declined" &&
                    py.call("digest", "cmd" to "digest", "who" to "sibling")["digest"].asString() == phone.client.node()!!.deviceSync.pack().digest
            }
            val inc = py.call("offers", "cmd" to "offers", "who" to "sibling")["incoming"] as JsonObject
            assertEquals(phone.client.node()!!.files.let { (it as FileService).fileStore()!!.incoming().keys }, inc.keys)
            assertEquals("pending", (inc[a] as JsonObject)["status"].asString())
            assertEquals(alice, (inc[a] as JsonObject)["from"].asString())
        } finally { phone.close() }
    }
}
