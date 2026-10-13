package dev.lichen.core.api

import kotlinx.coroutines.flow.StateFlow

// Group chats as the UI sees them (ChatClient.groups). Python reference: lichen/groups.py, node.py.
// Messages are end-to-end encrypted with a per-epoch group key; the owner (host) keeps the member list.
// Being added is an *invite*: it shows up with pending = true until you accept or decline.

enum class GroupHistory {
    PRIVATE,  // people who join later only see messages from after they joined
    SHARED,   // people who join later can read what was said before too
}

data class GroupMember(
    val pubkey: String,
    val name: String,       // nickname, else the name they gave themselves, else a short key
    val isOwner: Boolean,
    val isYou: Boolean,
)

data class GroupMessage(
    val id: String,
    val groupId: String,
    val from: String,        // author pubkey
    val authorName: String,
    val ts: Double,          // seconds since the epoch, as the author signed it
    val text: String,
    val outgoing: Boolean,   // from == our pubkey
)

data class GroupInfo(
    val id: String,
    val name: String,
    val owner: String,
    val ownerName: String,
    val members: List<GroupMember>,   // owner first, then by name
    val history: GroupHistory,
    val youOwn: Boolean,
    val pending: Boolean,             // an invite we haven't answered
    val unread: Int = 0,
    val lastMessage: GroupMessage? = null,
)

sealed interface GroupResult {
    /** [groupId] is the group concerned; [detail] a short human line when there is something to say ("delivered to 2 of 3"). */
    data class Ok(val groupId: String = "", val detail: String? = null) : GroupResult
    data class Failed(val reason: String) : GroupResult
}

interface GroupsApi {
    /** Groups we are in plus pending invites (those first), then by latest activity. Left, declined, removed and deleted groups are not listed. */
    val groups: StateFlow<List<GroupInfo>>

    /** The group's messages, oldest first, with author names. */
    fun messages(groupId: String): StateFlow<List<GroupMessage>>

    /** Start a group with [members] (pubkeys; we are always in it). Everyone gets an invite. */
    suspend fun create(name: String, members: List<String>, history: GroupHistory = GroupHistory.PRIVATE): GroupResult

    /** Join; then fetch whatever history we may read. */
    suspend fun accept(groupId: String): GroupResult
    /** Refuse an invite (the owner is told). */
    suspend fun decline(groupId: String): GroupResult
    /** Leave. Owners must name a successor ([newOwner], another member) or delete the group. */
    suspend fun leave(groupId: String, newOwner: String? = null): GroupResult

    // owner only
    suspend fun addMember(groupId: String, pubkey: String): GroupResult
    suspend fun removeMember(groupId: String, pubkey: String): GroupResult
    /** Hand the group to another member and stay in it. */
    suspend fun transfer(groupId: String, newOwner: String): GroupResult
    /** Delete for everyone. */
    suspend fun delete(groupId: String): GroupResult
    suspend fun setHistory(groupId: String, history: GroupHistory): GroupResult

    suspend fun send(groupId: String, text: String): GroupResult

    /** Ask the other members of every group (or just [groupId]) for messages we're missing. Returns how many new messages arrived. */
    suspend fun sync(groupId: String? = null): Int

    /** The group's chat is on screen: clear its unread count. */
    fun markRead(groupId: String)
}
