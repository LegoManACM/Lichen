package dev.lichen.core.api

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// What the Android app sees of a running Lichen node. The UI only talks to this interface; the real
// implementation (dev.lichen.core.node) and the UI's fake one both implement it.

enum class Trust { TRUSTED, UNKNOWN, BLOCKED }

enum class MessageStatus {
    SENDING,    // handing it over right now
    WAITING,    // they're offline: it's in our outbox, their hubs hold an IOU
    DELIVERED,  // reached at least one of their devices
    READ,       // they've had the chat open since (phase 2: read receipts)
    FAILED,     // refused, or no encryption key for them yet
}

data class ChatMessage(
    val id: String,
    val from: String,        // author pubkey
    val to: String,          // recipient pubkey
    val ts: Double,          // seconds since the epoch, as the author signed it
    val text: String,
    val outgoing: Boolean,   // from == our pubkey
    val status: MessageStatus? = null,  // only for outgoing messages
)

data class Contact(
    val pubkey: String,
    val name: String,             // the name they gave themselves (their signed record), or a short key
    val nickname: String? = null, // the name *we* gave them, wins for display
    val trust: Trust,
    val online: Boolean = false,
    val unread: Int = 0,
    val lastMessage: ChatMessage? = null,
) {
    val displayName: String get() = nickname ?: name
}

data class HubStatus(val address: String, val connected: Boolean)

data class Profile(
    val pubkey: String,
    val name: String,
    val hubs: List<String>,
    val fingerprint: String,  // pubkey in readable groups (identity.fingerprint)
    val device: String,       // this device's id
)

sealed interface ClientEvent {
    /** A message arrived from someone else (for notifications). */
    data class Incoming(val contact: Contact, val message: ChatMessage) : ClientEvent
    /** Something worth a line in a log/snackbar ("Alice moved", "relay unreachable"). */
    data class Log(val text: String) : ClientEvent
    /** Someone offered us a file (see [ChatClient.files]); [trusted] = from a contact, worth a notification. */
    data class FileOffer(val transfer: FileTransfer, val senderName: String, val trusted: Boolean) : ClientEvent
    /** A download finished and passed its hash check. */
    data class FileDownloaded(val transfer: FileTransfer) : ClientEvent
    /** A download failed (resumable: download again). */
    data class FileFailed(val transfer: FileTransfer, val error: String) : ClientEvent
}

interface ChatClient {
    val profile: StateFlow<Profile?>
    val hubs: StateFlow<List<HubStatus>>
    /** Trusted contacts plus strangers who wrote to us (trust UNKNOWN), newest conversation first. Never blocked keys. */
    val contacts: StateFlow<List<Contact>>
    /** Everyone in the phonebook except us (for starting a new chat), sorted by name. */
    val people: StateFlow<List<Contact>>
    val events: SharedFlow<ClientEvent>
    /** File offers and transfers. */
    val files: FilesApi

    /** Conversation with pubkey, oldest first. */
    fun messages(pubkey: String): StateFlow<List<ChatMessage>>

    /** Connect to the hubs, catch up (phonebook, outbox, chat sync). Returns once started; work continues in the background. */
    suspend fun start()
    suspend fun stop()

    /** Send a direct message. Messaging someone makes them a trusted contact (unless blocked). */
    suspend fun send(pubkey: String, text: String): MessageStatus

    /** The chat with pubkey is on screen: clear its unread count (phase 2: also send a read receipt). */
    fun markRead(pubkey: String)

    suspend fun setTrust(pubkey: String, trust: Trust)
    suspend fun setNickname(pubkey: String, nickname: String?)

    /** Retry the outbox, re-sync chats, re-announce. */
    suspend fun syncNow()
}
