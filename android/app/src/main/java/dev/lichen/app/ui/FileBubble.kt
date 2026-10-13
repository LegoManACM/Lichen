package dev.lichen.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.ChatMessage
import dev.lichen.core.api.FileTransfer
import dev.lichen.core.api.FilesApi
import dev.lichen.core.api.TransferStatus
import dev.lichen.core.store.humanSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

// File offers in the chat (minimal GUI; a designer is redoing the visuals). Offers and messages share one
// time-ordered list; downloads, sends and "save to Downloads" run on an app-wide scope so leaving the chat
// doesn't cancel them.

sealed interface TimelineItem {
    val ts: Double
    val key: String
    data class Msg(val m: ChatMessage) : TimelineItem { override val ts get() = m.ts; override val key get() = "m:${m.id}" }
    data class File(val t: FileTransfer) : TimelineItem { override val ts get() = t.ts; override val key get() = "f:${t.id}" }
}

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun toast(context: Context, text: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
}

/** The user picked [uri] with the system picker: offer it to [pubkey]. */
fun offerFile(context: Context, client: ChatClient, pubkey: String, uri: Uri) {
    val app = context.applicationContext
    appScope.launch {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        try {
            val r = client.files.send({ app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot read $name") }, name, size, listOf(pubkey))
            if (r.delivered + r.waiting == 0) toast(app, "Could not send the offer for $name")
        } catch (e: Exception) {
            toast(app, "Could not read $name: ${e.message}")
        }
    }
}

private fun mimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

private fun openFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(context, "No app can open ${file.name}")
    }
}

/** Copy a downloaded file into the public Downloads folder (MediaStore; no storage permission needed). */
private fun saveToDownloads(context: Context, file: File, name: String) {
    appScope.launch {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val cr = context.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("cannot create the file")
            cr.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            toast(context, "Saved to Downloads")
        } catch (e: Exception) {
            toast(context, "Could not save: ${e.message}")
        }
    }
}

@Composable
fun FileBubble(files: FilesApi, t: FileTransfer) {
    val context = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (t.outgoing) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (t.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(t.name, style = MaterialTheme.typography.bodyLarge)
                Text(humanSize(t.size), style = MaterialTheme.typography.labelSmall, color = dim)
                val note = when (t.status) {
                    TransferStatus.OFFERED -> "Offered"
                    TransferStatus.WAITING -> "Offered; waiting for them to come online"
                    TransferStatus.PENDING -> "Wants to send you this file"
                    TransferStatus.DOWNLOADING -> "Downloading... ${(t.progress * 100).toInt()}%"
                    TransferStatus.DONE -> "Downloaded"
                    TransferStatus.DECLINED -> "Declined"
                    TransferStatus.FAILED -> "Download failed${t.error?.let { ": $it" } ?: ""}"
                    TransferStatus.REFUSED -> t.error ?: "Too big"
                }
                Text(note, style = MaterialTheme.typography.bodySmall, color = if (t.status == TransferStatus.FAILED || t.status == TransferStatus.REFUSED) MaterialTheme.colorScheme.error else dim)
                if (t.status == TransferStatus.DOWNLOADING) {
                    LinearProgressIndicator(progress = { t.progress }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (t.status) {
                        TransferStatus.PENDING, TransferStatus.FAILED -> {
                            TextButton(onClick = { appScope.launch { files.download(t.id) } }) { Text(if (t.status == TransferStatus.FAILED) "Resume" else "Download") }
                            TextButton(onClick = { files.decline(t.id) }) { Text("Decline") }
                        }
                        TransferStatus.DONE -> {
                            val f = files.location(t.id)
                            if (f != null) {
                                TextButton(onClick = { openFile(context, f) }) { Text("Open") }
                                TextButton(onClick = { saveToDownloads(context, f, t.name) }) { Text("Save to Downloads") }
                            }
                        }
                        else -> {}
                    }
                }
                Text(clockTime(t.ts), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = dim)
            }
        }
    }
}
