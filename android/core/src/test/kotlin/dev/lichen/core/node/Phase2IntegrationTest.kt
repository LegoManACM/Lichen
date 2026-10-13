package dev.lichen.core.node

import dev.lichen.core.api.MessageStatus
import dev.lichen.core.api.Trust
import dev.lichen.core.asString
import dev.lichen.core.markLinked
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 2 against real Python: signals, requests, device sync with a Python device of the same identity, the vault. */
class Phase2IntegrationTest {
    private fun <T> withPeer(body: (PyPeer) -> T): T = PyPeer(true).use(body)

    private fun bool(e: JsonObject, k: String) = e[k].toString() == "true"

    /** Phone and alice trust each other, so signals flow both ways. */
    private fun befriend(py: PyPeer, phone: Phone) {
        runBlocking { phone.client.setTrust(py.userPk!!, Trust.TRUSTED) }
        py.call("ok", "cmd" to "trust", "who" to "user", "pk" to phone.pubkey, "level" to "trusted")
    }

    private fun startedPhone(py: PyPeer, linked: Boolean = false): Phone {
        val phone = Phone(listOf(py.hubAddr))
        if (linked) markLinked(phone.dir)
        runBlocking { phone.client.start(); phone.client.awaitCatchUp() }
        return phone
    }

    private fun pyTexts(py: PyPeer, who: String, peer: String): List<String> =
        ((py.call("dms", "cmd" to "dms", "who" to who, "peer" to peer)["messages"]) as JsonArray).map { (it as JsonObject)["text"].asString()!! }

    @Test
    fun pythonTypingShowsUpAndExpires() = withPeer { py ->
        val phone = startedPhone(py)
        try {
            val alice = py.userPk!!
            befriend(py, phone)
            py.call("ok", "cmd" to "signal", "who" to "user", "peer" to phone.pubkey, "kind" to "typing")
            eventually("typing") { alice in phone.client.typing.value }
            eventually("typing to expire", 15_000) { alice !in phone.client.typing.value }
        } finally { phone.close() }
    }

    @Test
    fun kotlinTypingReachesPython() = withPeer { py ->
        val phone = startedPhone(py)
        try {
            val alice = py.userPk!!
            befriend(py, phone)
            phone.client.typing(alice)
            eventually("python sees typing") { bool(py.call("status", "cmd" to "status", "who" to "user", "peer" to phone.pubkey), "typing") }
        } finally { phone.close() }
    }

    @Test
    fun readReceiptsBothWays() = withPeer { py ->
        val phone = startedPhone(py)
        try {
            val alice = py.userPk!!
            befriend(py, phone)
            // Kotlin -> Python message, Python reads it.
            assertEquals(MessageStatus.DELIVERED, runBlocking { phone.client.send(alice, "did you read this?") })
            py.awaitReceived("did you read this?")
            eventually("delivered mark") { phone.chat(alice).single().status == MessageStatus.DELIVERED }
            py.call("ok", "cmd" to "signal", "who" to "user", "peer" to phone.pubkey, "kind" to "read")
            eventually("read mark") { phone.chat(alice).single().status == MessageStatus.READ }

            // Python -> Kotlin message, the phone opens the chat.
            assertEquals("delivered", py.send("user", phone.pubkey, "ping"))
            eventually("incoming") { phone.chat(alice).any { it.text == "ping" } }
            val id = phone.chat(alice).first { it.text == "ping" }.id
            phone.client.markRead(alice)
            // Python's own message to the phone is `ping`; its receipts are about messages *it* sent:
            eventually("python shows ping as read") {
                py.call("receipt", "cmd" to "receipt", "who" to "user", "peer" to phone.pubkey, "id" to id)["status"].asString() == "read"
            }
        } finally { phone.close() }
    }

    @Test
    fun presenceBothWays() = withPeer { py ->
        val phone = startedPhone(py)
        try {
            val alice = py.userPk!!
            befriend(py, phone)
            assertTrue(bool(py.call("ok", "cmd" to "signal", "who" to "user", "peer" to phone.pubkey, "kind" to "here"), "result"))
            eventually("phone sees alice online") { phone.client.node()!!.online(alice) }
            runBlocking { phone.client.node()!!.signals.presenceRound() }
            eventually("python sees the phone online") { bool(py.call("status", "cmd" to "status", "who" to "user", "peer" to phone.pubkey), "online") }
            // Strangers get nothing: an untrusted peer's signal is refused.
            py.call("ok", "cmd" to "trust", "who" to "user", "pk" to phone.pubkey, "level" to "unknown")
            runBlocking { phone.client.setTrust(alice, Trust.UNKNOWN) }
            assertFalse(runBlocking { phone.client.node()!!.signals.signal(alice, mapOf("signal" to kotlinx.serialization.json.JsonPrimitive("here"))) })
        } finally { phone.close() }
    }

    @Test
    fun strangersAreRequestsAndSafetyCodeAndVerified() = withPeer { py ->
        val phone = startedPhone(py)
        try {
            val alice = py.userPk!!
            assertTrue(phone.client.requests.value.isEmpty())
            py.send("user", phone.pubkey, "hello stranger")
            eventually("a request") { phone.client.requests.value.singleOrNull()?.pubkey == alice }
            assertEquals("hello stranger", phone.client.requests.value.single().lastMessage?.text)
            val code = phone.client.safetyCode(alice)
            assertTrue(Regex("\\d{5} \\d{5} \\d{5} \\d{5}").matches(code), code)
            assertFalse(phone.client.contacts.value.single().verified)
            runBlocking { phone.client.setVerified(alice, true) }
            eventually("verified") { phone.client.contacts.value.single().verified }
            runBlocking { phone.client.setTrust(alice, Trust.TRUSTED) }
            eventually("no longer a request") { phone.client.requests.value.isEmpty() }
            runBlocking { phone.client.setVerified(alice, false) }
            eventually("unverified") { !phone.client.contacts.value.single().verified }
        } finally { phone.close() }
    }

    @Test
    fun logLinesAreCollected() = withPeer { py ->
        val phone = startedPhone(py)
        try {
            eventually("log lines") { phone.client.log.value.any { it.contains("joined via") } }
        } finally { phone.close() }
    }

    @Test
    fun liveDeviceSyncBothWays() = withPeer { py ->
        val phone = startedPhone(py)
        try {
            val alice = py.userPk!!
            py.call("ok", "cmd" to "start_sibling", "keydir" to phone.dir.absolutePath, "name" to "kate")
            // The sibling has a chat the phone lacks, and a contact setting.
            py.call("ok", "cmd" to "inject", "who" to "sibling", "peer" to alice, "text" to "from my pc")
            py.call("ok", "cmd" to "nickname", "who" to "sibling", "pk" to alice, "nickname" to "ally")
            eventually("the chat appears on the phone") { phone.chat(alice).any { it.text == "from my pc" } }
            eventually("nickname synced") { phone.client.node()!!.trust.nickname(alice) == "ally" }
            assertTrue(phone.client.node()!!.deviceSync.linked)

            // And the other way.
            assertEquals(MessageStatus.DELIVERED, runBlocking { phone.client.send(alice, "from my phone") })
            eventually("the phone's message reaches the PC") { "from my phone" in pyTexts(py, "sibling", alice) }

            // Both now agree on the digest.
            eventually("same digest") {
                py.call("digest", "cmd" to "digest", "who" to "sibling")["digest"].asString() == phone.client.node()!!.deviceSync.pack().digest
            }
        } finally { phone.close() }
    }

    @Test
    fun vaultSnapshotLeftByPythonIsMergedAtStart() = withPeer { py ->
        val phone = startedPhone(py, linked = true)
        val alice = py.userPk!!
        try {
            runBlocking { phone.client.stop() }
            eventually("phone detached") { phone.pubkey !in py.hubAttached() }
            py.call("ok", "cmd" to "start_sibling", "keydir" to phone.dir.absolutePath, "name" to "kate")
            py.call("ok", "cmd" to "inject", "who" to "sibling", "peer" to alice, "text" to "left in the vault")
            py.call("ok", "cmd" to "nickname", "who" to "sibling", "pk" to alice, "nickname" to "vaulty")
            py.call("ok", "cmd" to "stop_sibling")  // a final snapshot is left on the hub
            eventually("snapshot on the hub") { (py.call("vault", "cmd" to "vault")["snaps"] as JsonObject)[phone.pubkey] != null }

            phone.reopen()
            runBlocking { phone.client.start(); phone.client.awaitCatchUp() }
            eventually("merged from the vault") { phone.chat(alice).any { it.text == "left in the vault" } }
            assertEquals("vaulty", phone.client.node()!!.trust.nickname(alice))
        } finally { phone.close() }
    }

    @Test
    fun kotlinPushesToTheVaultAndPythonPulls() = withPeer { py ->
        val phone = startedPhone(py, linked = true)
        val alice = py.userPk!!
        try {
            fun rev(): Long = (((py.call("vault", "cmd" to "vault")["snaps"] as JsonObject)[phone.pubkey] as? JsonObject)?.values?.maxOfOrNull { it.toString().toLong() }) ?: 0L
            eventually("start-up snapshot") { rev() > 0 }
            val before = rev()
            phone.client.node()!!.deviceSync.vaultMinGapMs = 0  // (as in Python, a change within 15 s of the last upload waits for the next round)
            assertEquals(MessageStatus.DELIVERED, runBlocking { phone.client.send(alice, "see you in the vault") })
            // Debounced push: ~5 s after the change.
            eventually("a newer snapshot pushed", 25_000) { rev() > before }
            runBlocking { phone.client.stop() }  // (also leaves a final one if anything is still unsent)
            py.call("ok", "cmd" to "start_sibling", "keydir" to phone.dir.absolutePath, "name" to "kate")
            eventually("python pulled it") { "see you in the vault" in pyTexts(py, "sibling", alice) }
        } finally { phone.close() }
    }

    @Test
    fun finalPushOnStopLeavesLateChanges() = withPeer { py ->
        val phone = startedPhone(py, linked = true)
        val alice = py.userPk!!
        try {
            runBlocking { phone.client.send(alice, "last words") }
            runBlocking { phone.client.stop() }  // well inside the 5 s debounce: only the final push can have uploaded it
            eventually("snapshot on the hub") { (py.call("vault", "cmd" to "vault")["snaps"] as JsonObject)[phone.pubkey] != null }
            py.call("ok", "cmd" to "start_sibling", "keydir" to phone.dir.absolutePath, "name" to "kate")
            eventually("python has it") { "last words" in pyTexts(py, "sibling", alice) }
        } finally { phone.close() }
    }
}
