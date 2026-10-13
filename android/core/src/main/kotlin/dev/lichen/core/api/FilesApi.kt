package dev.lichen.core.api

import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream

// File sharing as the app sees it (ChatClient.files). An offer is a signed, sealed `file_offer` DM-style
// request (name, size, sha256, key); nothing moves until the recipient downloads, then the file is pulled
// in encrypted 48 KB chunks, resumable, and checked against the hash.

enum class TransferStatus {
    OFFERED,      // outgoing: delivered to at least one recipient (they choose whether to download)
    WAITING,      // outgoing: some recipient is offline; the offer sits in our outbox
    PENDING,      // incoming: waiting for you to download or decline
    DOWNLOADING,  // incoming
    DONE,         // incoming: saved (see [FilesApi.location])
    DECLINED,     // incoming
    FAILED,       // incoming: tap Download again to resume
    REFUSED,      // incoming: bigger than your limit
}

data class FileTransfer(
    val id: String,
    val name: String,
    val size: Long,
    val outgoing: Boolean,
    val from: String,            // sender pubkey (ours for outgoing)
    val to: List<String>,        // recipients for outgoing; just us ([]) for incoming
    val group: String?,          // group id when the offer was made to a group (DM chat views ignore those)
    val ts: Double,
    val status: TransferStatus,
    val done: Long = 0,          // bytes downloaded so far (DOWNLOADING); size when DONE
    val error: String? = null,
) {
    val progress: Float get() = if (size <= 0) (if (status == TransferStatus.DONE) 1f else 0f) else (done.toFloat() / size).coerceIn(0f, 1f)
    /** The other party of a direct-message transfer: the sender for incoming, the (first) recipient for outgoing. */
    fun peer(me: String): String? = if (from == me) to.firstOrNull() else from
}

/** Offers bigger than [maxMb] are refused; offers from trusted contacts up to [autoDownloadMb] download by themselves (0 = always ask). */
data class FileLimits(val maxMb: Double = 100.0, val autoDownloadMb: Double = 0.0)

data class SendResult(val fileId: String, val delivered: Int, val waiting: Int, val failed: Int)

interface FilesApi {
    /** Every offer made to us and by us, oldest first. Updated as downloads progress. */
    val transfers: StateFlow<List<FileTransfer>>
    val limits: StateFlow<FileLimits>
    fun setLimits(limits: FileLimits)

    /**
     * Offer a file to [to]. [open] is called once to read the bytes (e.g. `contentResolver.openInputStream`);
     * they are copied into app storage so the file stays available for as long as the offer stands.
     * [group] is for the groups feature: pass the group id and its members (minus us) as [to].
     */
    suspend fun send(open: () -> InputStream, name: String, size: Long, to: List<String>, group: String? = null): SendResult

    /** Accept an offer (also resumes a failed one). Returns the saved file, or null if it failed (see [FileTransfer.error]). */
    suspend fun download(fileId: String): File?
    fun decline(fileId: String)

    /** Where a finished download (or a file we offered) lives on disk, null if it isn't there. */
    fun location(fileId: String): File?
}
