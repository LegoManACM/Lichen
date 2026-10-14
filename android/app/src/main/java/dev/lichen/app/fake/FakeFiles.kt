package dev.lichen.app.fake

import dev.lichen.core.api.FileLimits
import dev.lichen.core.api.FileTransfer
import dev.lichen.core.api.FilesApi
import dev.lichen.core.api.SendResult
import dev.lichen.core.api.TransferStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream

/** Files for the fake client: what you offer (to a person or a group) shows up as offered; nothing ever arrives. */
class FakeFiles(private val me: String = "") : FilesApi {
    private val _transfers = MutableStateFlow<List<FileTransfer>>(emptyList())
    override val transfers: StateFlow<List<FileTransfer>> = _transfers
    override val limits: StateFlow<FileLimits> = MutableStateFlow(FileLimits())
    override fun setLimits(limits: FileLimits) {}
    override suspend fun send(open: () -> InputStream, name: String, size: Long, to: List<String>, group: String?): SendResult {
        val id = "fake${_transfers.value.size}"
        val t = FileTransfer(id, name, size.coerceAtLeast(0), true, me, to, group, System.currentTimeMillis() / 1000.0, TransferStatus.OFFERED)
        _transfers.value = _transfers.value + t
        return SendResult(id, to.size, 0, 0)
    }
    override suspend fun download(fileId: String): File? = null
    override fun decline(fileId: String) {}
    override fun location(fileId: String): File? = null
}
