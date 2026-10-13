package dev.lichen.core.node

import dev.lichen.core.api.ClientEvent
import dev.lichen.core.api.GroupHistory
import dev.lichen.core.api.GroupResult
import dev.lichen.core.asString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Group chats between the Kotlin node and real Python nodes: alice (a relay-only user) and the hub (a direct node, also usable as a member). */
class GroupsIntegrationTest {
    private fun <T> withPeer(body: (PyPeer) -> T): T = PyPeer(true).use(body)

    private fun started(py: PyPeer, name: String = "kate"): Phone {
        val phone = Phone(listOf(py.hubAddr), name = name)
        blocking { phone.client.start(); phone.client.awaitCatchUp() }
        return phone
    }

    @Test
    fun pythonOwnerInvitesKotlinThenMessagesFlowBothWays() = withPeer { py ->
        val phone = started(py)
        try {
            val alice = py.userPk!!
            val gid = py.gcreate("user", "book club", listOf(phone.pubkey))
            // An invite, nothing more: no messages, marked pending.
            eventually("invite to appear") { phone.group(gid)?.pending == true }
            val invite = phone.group(gid)!!
            assertEquals("book club", invite.name)
            assertEquals(alice, invite.owner)
            assertEquals("alice", invite.ownerName)
            assertEquals(setOf(alice, phone.pubkey), invite.members.map { it.pubkey }.toSet())
            assertFalse(invite.youOwn)
            eventually("invite event") { phone.events.any { it is ClientEvent.GroupInvite && it.group.id == gid } }

            assertEquals(GroupResult.Ok(gid), blocking { phone.groups.accept(gid) })
            eventually("accepted") { phone.group(gid)?.pending == false }

            py.gsend("user", gid, "welcome é😀")
            eventually("message from alice") { phone.groupTexts(gid) == listOf("welcome é😀") }
            val m = phone.groups.messages(gid).value.single()
            assertEquals("alice", m.authorName)
            assertFalse(m.outgoing)
            eventually("event + unread") {
                phone.events.any { it is ClientEvent.GroupIncoming && it.message.text == "welcome é😀" } && phone.group(gid)?.unread == 1
            }
            phone.groups.markRead(gid)
            eventually("unread cleared") { phone.group(gid)?.unread == 0 }

            val res = blocking { phone.groups.send(gid, "thanks alice") }.ok()
            assertEquals("delivered to 1 of 1", res.detail)
            py.awaitGroupMessage("thanks alice", "user")
            assertEquals(listOf("welcome é😀", "thanks alice"), py.gtexts("user", gid))
            eventually("own message in chat") { phone.groups.messages(gid).value.last().let { it.outgoing && it.text == "thanks alice" } }
        } finally { phone.close() }
    }

    @Test
    fun kotlinCreatesGroupWithPythonUser() = withPeer { py ->
        val phone = started(py)
        try {
            val alice = py.userPk!!
            val gid = (blocking { phone.groups.create("lichen devs", listOf(alice), GroupHistory.SHARED) }.ok()).groupId
            eventually("group listed") { phone.group(gid) != null }
            val g = phone.group(gid)!!
            assertTrue(g.youOwn); assertFalse(g.pending); assertEquals(GroupHistory.SHARED, g.history)
            eventually("alice invited") { py.gstatus("user", gid) == "pending" }
            assertEquals(phone.pubkey, py.gentry("user", gid)!!["owner"].asString())
            assertEquals("shared", py.gentry("user", gid)!!["history"].asString())
            assertEquals(0, py.gaccept("user", gid))

            blocking { phone.groups.send(gid, "hi alice") }.ok()
            py.awaitGroupMessage("hi alice", "user")
            py.gsend("user", gid, "hi kate")
            eventually("alice message") { phone.groupTexts(gid) == listOf("hi alice", "hi kate") }
            assertEquals(listOf("hi alice", "hi kate"), py.gtexts("user", gid))
        } finally { phone.close() }
    }

    @Test
    fun privateHistoryHidesEarlierMessagesFromLateJoiners() = withPeer { py ->
        val phone = started(py)
        try {
            val alice = py.userPk!!
            val gid = blocking { phone.groups.create("private one", listOf(alice), GroupHistory.PRIVATE) }.ok().groupId
            py.gaccept("user", gid)
            blocking { phone.groups.send(gid, "before the hub") }.ok()
            py.gsend("user", gid, "alice before the hub")
            // The hub joins late: it can read from now on, not what was said before.
            blocking { phone.groups.addMember(gid, py.hubPk) }.ok()
            eventually("hub invited") { py.gstatus("hub", gid) == "pending" }
            assertEquals(0, py.gaccept("hub", gid))
            assertEquals(emptyList(), py.gtexts("hub", gid))
            blocking { phone.groups.send(gid, "after the hub") }.ok()
            py.awaitGroupMessage("after the hub", "hub")
            assertEquals(listOf("after the hub"), py.gtexts("hub", gid))
            // and alice (who was there all along) still has everything
            assertEquals(3, py.gtexts("user", gid).size)
        } finally { phone.close() }
    }

    @Test
    fun sharedHistoryLetsLateJoinersReadEverything() = withPeer { py ->
        val phone = started(py)
        try {
            val alice = py.userPk!!
            val gid = blocking { phone.groups.create("shared one", listOf(alice), GroupHistory.SHARED) }.ok().groupId
            py.gaccept("user", gid)
            blocking { phone.groups.send(gid, "old news") }.ok()
            py.gsend("user", gid, "alices old news")
            blocking { phone.groups.addMember(gid, py.hubPk) }.ok()
            eventually("hub invited") { py.gstatus("hub", gid) == "pending" }
            assertEquals(2, py.gaccept("hub", gid))
            assertEquals(setOf("old news", "alices old news"), py.gtexts("hub", gid).toSet())
        } finally { phone.close() }
    }

    @Test
    fun kotlinAsLateJoinerRespectsHistoryMode() = withPeer { py ->
        val phone = started(py)
        try {
            val priv = py.gcreate("hub", "p", listOf(py.userPk!!), "private")
            val shared = py.gcreate("hub", "s", listOf(py.userPk!!), "shared")
            py.gaccept("user", priv); py.gaccept("user", shared)
            py.gsend("hub", priv, "private early"); py.gsend("hub", shared, "shared early")
            py.gsend("user", priv, "private early 2")
            py.gdo("gadd", "hub", priv, "pk" to phone.pubkey)
            py.gdo("gadd", "hub", shared, "pk" to phone.pubkey)
            eventually("both invites") { phone.group(priv)?.pending == true && phone.group(shared)?.pending == true }
            blocking { phone.groups.accept(priv) }.ok()
            blocking { phone.groups.accept(shared) }.ok()
            eventually("shared history readable") { phone.groupTexts(shared) == listOf("shared early") }
            assertEquals(emptyList(), phone.groupTexts(priv))
            // New messages come through in both.
            py.gsend("hub", priv, "private later")
            eventually("later message") { phone.groupTexts(priv) == listOf("private later") }
            // Catch-up on accept is quiet: no notification for history.
            assertTrue(phone.events.none { it is ClientEvent.GroupIncoming && it.message.text == "shared early" })
        } finally { phone.close() }
    }

    @Test
    fun groupSyncCatchesUpAfterBeingOffline() = withPeer { py ->
        val phone = started(py)
        try {
            val gid = py.gcreate("user", "catch up", listOf(phone.pubkey))
            eventually("invite") { phone.group(gid) != null }
            blocking { phone.groups.accept(gid) }.ok()
            blocking { phone.client.stop() }
            eventually("phone detached") { phone.pubkey !in py.hubAttached() }
            val sent = py.gsend("user", gid, "while you were away")
            assertEquals(0, sent["delivered"].toString().toInt())
            py.gsend("user", gid, "and another")

            phone.reopen()
            blocking { phone.client.start() }
            eventually("caught up at start") { phone.groupTexts(gid) == listOf("while you were away", "and another") }
            eventually("notified") { phone.events.any { it is ClientEvent.GroupIncoming && it.message.text == "and another" } }

            // The other way round: alice loses her copy and asks us (group_sync served by Kotlin).
            blocking { phone.groups.send(gid, "from kate") }.ok()
            py.awaitGroupMessage("from kate", "user")
            py.call("ok", "cmd" to "gforget", "who" to "user", "group" to gid)
            assertEquals(emptyList(), py.gtexts("user", gid))
            val n = py.call("gsynced", "cmd" to "gsync", "who" to "user", "group" to gid)["n"].toString().toInt()
            assertEquals(3, n)
            assertEquals(3, py.gtexts("user", gid).size)
        } finally { phone.close() }
    }

    @Test
    fun leavingDecliningAndRemoval() = withPeer { py ->
        val phone = started(py)
        try {
            val alice = py.userPk!!
            // Python owner; Kotlin accepts then leaves.
            val g1 = py.gcreate("user", "one", listOf(phone.pubkey))
            eventually("invite 1") { phone.group(g1) != null }
            blocking { phone.groups.accept(g1) }.ok()
            blocking { phone.groups.leave(g1) }.ok()
            eventually("gone from the list") { phone.group(g1) == null }
            eventually("owner removed us") { phone.pubkey !in py.gmembers("user", g1) }
            // Messages sent after we left are refused and never shown.
            py.gsend("user", g1, "after you left")
            Thread.sleep(300)
            assertEquals(emptyList(), phone.groupTexts(g1))

            // Declining an invite.
            val g2 = py.gcreate("user", "two", listOf(phone.pubkey))
            eventually("invite 2") { phone.group(g2)?.pending == true }
            blocking { phone.groups.decline(g2) }.ok()
            eventually("declined: gone") { phone.group(g2) == null }
            eventually("owner removed the decliner") { phone.pubkey !in py.gmembers("user", g2) }

            // Removed by the owner: the group disappears and later messages stay unreadable.
            val g3 = py.gcreate("user", "three", listOf(phone.pubkey))
            eventually("invite 3") { phone.group(g3) != null }
            blocking { phone.groups.accept(g3) }.ok()
            py.gsend("user", g3, "still here")
            eventually("first message") { phone.groupTexts(g3) == listOf("still here") }
            py.gdo("gremove", "user", g3, "pk" to phone.pubkey)
            eventually("removed") { phone.group(g3) == null }
            py.gsend("user", g3, "you cannot read this")
            Thread.sleep(300)
            assertFalse(phone.groupTexts(g3).contains("you cannot read this"))

            // Kotlin owner removes alice; a member leaving a Kotlin-owned group is removed by us.
            val g4 = blocking { phone.groups.create("four", listOf(alice, py.hubPk)) }.ok().groupId
            py.gaccept("user", g4)
            blocking { phone.groups.removeMember(g4, alice) }.ok()
            eventually("alice out") { alice !in py.gmembers("user", g4) }
            assertEquals("removed", py.gstatus("user", g4))
            assertEquals(setOf(phone.pubkey, py.hubPk), phone.group(g4)!!.members.map { it.pubkey }.toSet())
            py.gaccept("hub", g4)
            py.gdo("gleave", "hub", g4)
            eventually("hub removed by us") { phone.group(g4)!!.members.map { it.pubkey } == listOf(phone.pubkey) }
        } finally { phone.close() }
    }

    @Test
    fun ownershipTransfersBothWays() = withPeer { py ->
        val phone = started(py)
        try {
            val alice = py.userPk!!
            val gid = blocking { phone.groups.create("handover", listOf(alice, py.hubPk)) }.ok().groupId
            py.gaccept("user", gid); py.gaccept("hub", gid)
            blocking { phone.groups.send(gid, "kate era") }.ok()

            // Kotlin -> Python, staying a member.
            blocking { phone.groups.transfer(gid, alice) }.ok()
            eventually("alice owns it") { py.gentry("user", gid)?.get("owner").asString() == alice }
            eventually("we know") { phone.group(gid)?.owner == alice && phone.group(gid)?.youOwn == false }
            assertTrue(phone.group(gid)!!.members.any { it.isYou })
            assertTrue(blocking { phone.groups.addMember(gid, py.hubPk) } is GroupResult.Failed)  // owner only
            py.gsend("user", gid, "alice era")
            eventually("message in new epoch") { phone.groupTexts(gid).contains("alice era") }
            // Everybody still reads everything (the hand-over carried the key history).
            assertEquals(setOf("kate era", "alice era"), py.gtexts("hub", gid).toSet())

            // Python -> Kotlin.
            assertEquals("delivered", py.call("gtransferred", "cmd" to "gtransfer", "who" to "user", "group" to gid, "to" to phone.pubkey)["result"].asString())
            eventually("we own it again") { phone.group(gid)?.youOwn == true }
            blocking { phone.groups.send(gid, "kate again") }.ok()
            py.awaitGroupMessage("kate again", "hub")
            // As owner again we can change things; Python validates the chain of two transfers.
            blocking { phone.groups.setHistory(gid, GroupHistory.SHARED) }.ok()
            eventually("history shared at alice") { py.gentry("user", gid)?.get("history").asString() == "shared" }

            // Leave with a successor.
            assertTrue(blocking { phone.groups.leave(gid) } is GroupResult.Failed, "an owner must name a successor")
            blocking { phone.groups.leave(gid, newOwner = py.hubPk) }.ok()
            eventually("hub owns it, we are gone") {
                py.gentry("hub", gid)?.get("owner").asString() == py.hubPk && phone.pubkey !in py.gmembers("hub", gid)
            }
            eventually("our list dropped it") { phone.group(gid) == null }
        } finally { phone.close() }
    }

    @Test
    fun ownerCanDeleteAndMembersForget() = withPeer { py ->
        val phone = started(py)
        try {
            val alice = py.userPk!!
            val gid = blocking { phone.groups.create("doomed", listOf(alice)) }.ok().groupId
            py.gaccept("user", gid)
            py.gsend("user", gid, "remember me")
            eventually("got it") { phone.groupTexts(gid) == listOf("remember me") }
            blocking { phone.groups.delete(gid) }.ok()
            eventually("group gone from our list") { phone.group(gid) == null }
            eventually("alices copy deleted") { py.gstatus("user", gid) == "deleted" }
            assertEquals(emptyList(), py.gtexts("user", gid))

            // Python owner deletes; Kotlin forgets.
            val g2 = py.gcreate("user", "doomed too", listOf(phone.pubkey))
            eventually("invite") { phone.group(g2) != null }
            blocking { phone.groups.accept(g2) }.ok()
            py.gsend("user", g2, "bye")
            eventually("bye") { phone.groupTexts(g2) == listOf("bye") }
            py.gdo("gdelete", "user", g2)
            eventually("deleted for us") { phone.group(g2) == null }
            assertEquals(emptyList(), phone.groupTexts(g2))
            eventually("deletion noted") { phone.events.any { it is ClientEvent.Log && "doomed too" in it.text } }
        } finally { phone.close() }
    }

    @Test
    fun groupsSurviveARestart() = withPeer { py ->
        val phone = started(py)
        try {
            val gid = py.gcreate("user", "keepers", listOf(phone.pubkey))
            eventually("invite") { phone.group(gid) != null }
            blocking { phone.groups.accept(gid) }.ok()
            py.gsend("user", gid, "persist me")
            eventually("message") { phone.groupTexts(gid) == listOf("persist me") && phone.group(gid)?.unread == 1 }
            blocking { phone.client.stop() }
            Thread.sleep(300)
            phone.reopen()
            eventually("group after restart") { phone.group(gid)?.unread == 1 && phone.group(gid)?.lastMessage?.text == "persist me" }
            assertEquals(listOf("persist me"), phone.groupTexts(gid))
            blocking { phone.client.start() }
            blocking { phone.groups.send(gid, "still works") }.ok()
            py.awaitGroupMessage("still works", "user")
            assertNotNull(phone.group(gid))
        } finally { phone.close() }
    }

    @Test
    fun groupsSyncBetweenOurOwnDevices() = withPeer { py ->
        val phone = started(py)
        try {
            val alice = py.userPk!!
            py.call("ok", "cmd" to "start_sibling", "keydir" to phone.dir.absolutePath, "name" to "kate")
            // The PC (Python sibling, same identity) has a group with alice and a message in it.
            val gid = py.gcreate("sibling", "from my pc", listOf(alice))
            py.gaccept("user", gid)
            py.gsend("sibling", gid, "hello from the pc")
            eventually("the group reaches the phone through device sync") { phone.group(gid)?.pending == false }
            assertTrue(phone.group(gid)!!.youOwn)
            eventually("its message too") { phone.groupTexts(gid) == listOf("hello from the pc") }
            // The phone can speak for the group straight away (it got the epoch keys).
            blocking { phone.groups.send(gid, "hello from the phone") }.ok()
            py.awaitGroupMessage("hello from the phone", "user")
            eventually("both devices agree") {
                py.call("digest", "cmd" to "digest", "who" to "sibling")["digest"].asString() == phone.client.node()!!.deviceSync.pack().digest
            }
            assertEquals(2, py.gtexts("sibling", gid).size)
        } finally { phone.close() }
    }
}
