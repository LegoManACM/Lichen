package dev.lichen.core.node

import dev.lichen.core.api.MessageStatus
import dev.lichen.core.api.Trust
import dev.lichen.core.asString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Kotlin node against real Python nodes (android/tools/py_peer.py): a hub and a relay-only user attached to it. */
class NodeIntegrationTest {
    private fun <T> withPeer(user: Boolean = true, body: (PyPeer) -> T): T = PyPeer(user).use(body)

    @Test
    fun attachesToThePythonHubAndAppearsInItsPhonebook() = withPeer(user = false) { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            runBlocking { phone.client.start() }
            eventually("hub connected") { phone.client.hubs.value.singleOrNull()?.connected == true }
            assertEquals(py.hubAddr, phone.client.hubs.value.single().address)
            eventually("hub phonebook to list kate") { "kate" in py.state()["phonebook"].toString() }
            assertTrue(py.hubAttached().contains(phone.pubkey))
            assertEquals(phone.pubkey, phone.client.profile.value?.pubkey)
            // The hub's record came back with the hello.
            eventually("phone to know the hub") { phone.client.people.value.any { it.pubkey == py.hubPk && it.name == "hub" } }
        } finally { phone.close() }
    }

    @Test
    fun kotlinToPythonMessageIsDeliveredAndVerified() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            runBlocking { phone.client.start() }
            val alice = py.userPk!!
            val status = runBlocking { phone.client.send(alice, "hello alice é😀") }
            assertEquals(MessageStatus.DELIVERED, status)
            val got = py.awaitReceived("hello alice é😀")
            assertEquals(phone.pubkey, got["from"].asString())
            // Python stored it with a signature it verified; the phone shows it as delivered, and alice is now a contact.
            eventually("chat to show the message delivered") { phone.chat(alice).singleOrNull()?.status == MessageStatus.DELIVERED }
            eventually("alice among the contacts") { phone.client.contacts.value.singleOrNull()?.lastMessage != null }
            val contact = phone.client.contacts.value.single()
            assertEquals(alice, contact.pubkey)
            assertEquals(Trust.TRUSTED, contact.trust)
            assertEquals("alice", contact.name)
            assertEquals("hello alice é😀", contact.lastMessage?.text)
            assertTrue(contact.online)
        } finally { phone.close() }
    }

    @Test
    fun pythonToKotlinMessageArrivesViaTheRelay() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            runBlocking { phone.client.start(); phone.client.awaitCatchUp() }
            assertEquals("delivered", py.send("user", phone.pubkey, "hi from alice"))
            eventually("incoming event") { phone.incoming().isNotEmpty() }
            val ev = phone.incoming().single()
            assertEquals("hi from alice", ev.message.text)
            assertEquals(py.userPk, ev.contact.pubkey)
            assertFalse(ev.message.outgoing)
            eventually("alice in contacts with 1 unread") { phone.client.contacts.value.singleOrNull()?.unread == 1 }
            assertEquals(Trust.UNKNOWN, phone.client.contacts.value.single().trust)
            assertEquals("hi from alice", phone.chat(py.userPk!!).single().text)
            phone.client.markRead(py.userPk!!)
            eventually("unread cleared") { phone.client.contacts.value.single().unread == 0 }
        } finally { phone.close() }
    }

    @Test
    fun offlinePythonUserGetsMessageLaterViaIouAndNudge() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            runBlocking { phone.client.start(); phone.client.awaitCatchUp() }
            val alice = py.userPk!!
            py.call("ok", "cmd" to "stop_user")
            eventually("alice detached") { alice !in py.hubAttached() }
            val status = runBlocking { phone.client.send(alice, "are you there?") }
            assertEquals(MessageStatus.WAITING, status)
            eventually("waiting in the chat") { phone.chat(alice).singleOrNull()?.status == MessageStatus.WAITING }
            assertEquals(1, phone.client.node()!!.outbox.size())
            // The hub holds an IOU from us to alice, for exactly this message.
            val ious = py.state()["ious"] as JsonObject
            val item = ((ious[alice] as JsonObject)[phone.pubkey] as JsonObject)["items"].toString()
            assertTrue(item.contains("msg:"), item)

            py.call("ok", "cmd" to "start_user")
            py.awaitReceived("are you there?")
            eventually("delivered after the nudge") { phone.chat(alice).single().status == MessageStatus.DELIVERED }
            assertEquals(0, phone.client.node()!!.outbox.size())
        } finally { phone.close() }
    }

    @Test
    fun messageSentToKotlinWhileOfflineArrivesWhenItStarts() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            runBlocking { phone.client.start(); phone.client.awaitCatchUp(); phone.client.stop() }
            eventually("phone detached") { phone.pubkey !in py.hubAttached() }
            assertEquals("waiting", py.send("user", phone.pubkey, "while you were out"))
            assertEquals(1, py.state()["user_outbox"].toString().toInt())

            runBlocking { phone.client.start() }
            eventually("message arrives") { phone.incoming().any { it.message.text == "while you were out" } }
            eventually("python outbox drained") { py.state()["user_outbox"].toString() == "0" }
            eventually("one unread") { phone.client.contacts.value.singleOrNull()?.unread == 1 }
        } finally { phone.close() }
    }

    @Test
    fun chatSyncFillsAGap() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            runBlocking { phone.client.start(); phone.client.awaitCatchUp() }
            val alice = py.userPk!!
            assertEquals(MessageStatus.DELIVERED, runBlocking { phone.client.send(alice, "first") })
            // Alice has a message for us that never travelled: only chat sync can find it.
            py.call("ok", "cmd" to "inject", "who" to "user", "peer" to phone.pubkey, "text" to "lost in transit")
            assertTrue(phone.chat(alice).none { it.text == "lost in transit" })
            runBlocking { phone.client.syncNow() }
            eventually("gap filled") { phone.chat(alice).any { it.text == "lost in transit" } }
            assertTrue(phone.incoming().any { it.message.text == "lost in transit" })
            assertEquals(listOf("first", "lost in transit"), phone.chat(alice).map { it.text })
        } finally { phone.close() }
    }

    @Test
    fun chatSyncServesMessagesPythonLacks() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            runBlocking { phone.client.start(); phone.client.awaitCatchUp() }
            val alice = py.userPk!!
            runBlocking { phone.client.send(alice, "one"); phone.client.send(alice, "two") }
            py.awaitReceived("two")
            // Alice loses her copy; she asks us (dm_sync handled by the Kotlin node) and gets both back, verified.
            py.call("ok", "cmd" to "forget", "who" to "user", "peer" to phone.pubkey)
            assertEquals(2, py.call("synced", "cmd" to "sync", "who" to "user", "peer" to phone.pubkey)["n"].toString().toInt())
            assertEquals(0, py.call("synced", "cmd" to "sync", "who" to "user", "peer" to phone.pubkey)["n"].toString().toInt())
        } finally { phone.close() }
    }

    @Test
    fun restartKeepsHistoryOutboxAndUnread() = withPeer { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            runBlocking { phone.client.start(); phone.client.awaitCatchUp() }
            val alice = py.userPk!!
            py.send("user", phone.pubkey, "from alice")
            eventually("unread 1") { phone.client.contacts.value.singleOrNull()?.unread == 1 }
            py.call("ok", "cmd" to "stop_user")
            eventually("alice detached") { alice !in py.hubAttached() }
            assertEquals(MessageStatus.WAITING, runBlocking { phone.client.send(alice, "pending one") })
            runBlocking { phone.client.stop() }
            Thread.sleep(300)  // let the unread file land

            phone.reopen()
            eventually("history after restart") { phone.chat(alice).map { it.text } == listOf("from alice", "pending one") }
            assertEquals(MessageStatus.WAITING, phone.chat(alice).last().status)
            eventually("unread after restart") { phone.client.contacts.value.singleOrNull()?.unread == 1 }
            runBlocking { phone.client.start() }
            py.call("ok", "cmd" to "start_user")
            py.awaitReceived("pending one")
            eventually("delivered after restart") { phone.chat(alice).last().status == MessageStatus.DELIVERED }
            eventually("unread still 1") { phone.client.contacts.value.singleOrNull()?.unread == 1 }
        } finally { phone.close() }
    }

    @Test
    fun worksOverAWebSocketHubAddress() = withPeer { py ->
        val phone = Phone(listOf(py.wsAddr))
        try {
            runBlocking { phone.client.start(); phone.client.awaitCatchUp() }
            assertTrue(phone.client.hubs.value.single().connected)
            val alice = py.userPk!!
            assertEquals(MessageStatus.DELIVERED, runBlocking { phone.client.send(alice, "over ws") })
            py.awaitReceived("over ws")
            // Our record says relay:ws://...; Python reaches us through it with its own WebSocket client.
            assertEquals("delivered", py.send("user", phone.pubkey, "back over ws"))
            eventually("incoming over ws") { phone.incoming().any { it.message.text == "back over ws" } }
        } finally { phone.close() }
    }

    @Test
    fun unknownRequestTypesGetAnError() = withPeer(user = false) { py ->
        val phone = Phone(listOf(py.hubAddr))
        try {
            runBlocking { phone.client.start() }
            val reply = runBlocking { phone.client.node()!!.handle(dev.lichen.core.obj { put("type", kotlinx.serialization.json.JsonPrimitive("bogus")) }) }
            assertEquals("unknown type 'bogus'", reply["error"].asString())
            assertEquals("false", reply["ok"].toString())
        } finally { phone.close() }
    }
}
