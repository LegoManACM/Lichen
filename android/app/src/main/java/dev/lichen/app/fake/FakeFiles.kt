package dev.lichen.app.fake

import dev.lichen.core.api.FileLimits
import dev.lichen.core.api.FileTransfer
import dev.lichen.core.api.FilesApi
import dev.lichen.core.api.SendResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream

/** Files for the fake client: nothing is ever offered. */
class FakeFiles : FilesApi {
    override val transfers: StateFlow<List<FileTransfer>> = MutableStateFlow(emptyList())
    override val limits: StateFlow<FileLimits> = MutableStateFlow(FileLimits())
    override fun setLimits(limits: FileLimits) {}
    override suspend fun send(open: () -> InputStream, name: String, size: Long, to: List<String>, group: String?) = SendResult("fake", 0, 0, to.size)
    override suspend fun download(fileId: String): File? = null
    override fun decline(fileId: String) {}
    override fun location(fileId: String): File? = null
}
