package dev.lichen.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.lichen.app.ui.theme.*
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

// File offers in the chat. Offers and messages share one
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
fun offerFile(context: Context, client: ChatClient, pubkey: String, uri: Uri) = offerFile(context, client, listOf(pubkey), null, uri)

/** Offer [uri] to [to]; with [group], it is a group file offer (pass the other members as [to]). */
fun offerFile(context: Context, client: ChatClient, to: List<String>, group: String?, uri: Uri) {
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
            val r = client.files.send({ app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot read $name") }, name, size, to, group)
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

/**
 * A file in the chat, as the canvas's SlabChat draws it: a card with a square icon tile, the name, a mono
 * size line and (while downloading) a 6dp progress bar along the bottom. An incoming file that's waiting
 * for you or coming in is alert-colored; yours have the accent; finished ones are a plain card. The time
 * and state sit in mono under the card, like a message's. [header] goes above the card (a group sender's name).
 */
@Composable
fun FileBubble(files: FilesApi, t: FileTransfer, header: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    val p = Slab.colors
    val error = MaterialTheme.colorScheme.error
    val live = !t.outgoing && t.status in listOf(TransferStatus.PENDING, TransferStatus.DOWNLOADING, TransferStatus.FAILED)
    val ground = if (live) p.alertGround else p.card
    val (border, shadow) = when {
        live -> p.alert to p.alertShadow
        t.outgoing -> p.accent to p.shadow
        else -> p.edge to null
    }
    val (tile, onTile) = when {
        live -> p.alert to p.onAlert
        t.outgoing -> p.accent to p.onAccent
        else -> p.edge to p.text
    }
    Column(
        Modifier.fillMaxWidth(), horizontalAlignment = if (t.outgoing) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        header?.invoke()
        var card = Modifier.fillMaxWidth(0.78f)
        if (shadow != null) card = card.hardShadow(shadow)
        Column(card.background(ground).slabBorder(border)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(40.dp).background(tile), contentAlignment = Alignment.Center) {
                    Icon(if (t.outgoing) SlabIcons.Upload else SlabIcons.Download, null, Modifier.size(20.dp), tint = onTile)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(t.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val size = humanSize(t.size)
                    Text(
                        if (t.status == TransferStatus.DOWNLOADING) "$size · ${(t.progress * 100).toInt()}%" else size,
                        fontFamily = Slab.mono, fontSize = 10.sp, color = p.muted,
                    )
                }
            }
            val problem = when (t.status) {
                TransferStatus.FAILED -> "Download failed${t.error?.let { ": $it" } ?: ""}"
                TransferStatus.REFUSED -> "Not downloaded: ${t.error ?: "too big"}"
                else -> null
            }
            problem?.let {
                Text(it, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), fontSize = 13.sp, lineHeight = 18.sp, color = error)
            }
            val actions: List<Pair<String, () -> Unit>> = when (t.status) {
                TransferStatus.PENDING, TransferStatus.FAILED -> listOf(
                    "Decline" to { files.decline(t.id); Unit },
                    (if (t.status == TransferStatus.FAILED) "Resume" else "Download") to { appScope.launch { files.download(t.id) }; Unit },
                )
                TransferStatus.DONE -> files.location(t.id)?.let { f ->
                    listOf("Save" to { saveToDownloads(context, f, t.name) }, "Open" to { openFile(context, f) })
                } ?: emptyList()
                else -> emptyList()
            }
            if (actions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions.forEachIndexed { i, (label, act) ->
                        val primary = i == actions.lastIndex
                        SlabButton(
                            label, act, Modifier.weight(1f), if (primary) SlabButtonStyle.Primary else SlabButtonStyle.Secondary,
                            height = 40.dp, fill = if (live) p.alert else p.accent, onFill = if (live) p.onAlert else p.onAccent,
                            shadowColor = if (live) p.alertShadow else p.shadow,
                        )
                    }
                }
            }
            if (t.status == TransferStatus.DOWNLOADING) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(p.alert.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(t.progress.coerceIn(0f, 1f)).fillMaxHeight().background(p.alert))
                }
            }
        }
        val state = when (t.status) {
            TransferStatus.OFFERED -> "OFFERED"
            TransferStatus.WAITING -> "WAITING FOR THEM TO COME ONLINE"
            TransferStatus.PENDING -> "WANTS TO SEND YOU THIS"
            TransferStatus.DOWNLOADING -> "DOWNLOADING"
            TransferStatus.DONE -> if (t.outgoing) "SENT" else "DOWNLOADED"
            TransferStatus.DECLINED -> "DECLINED"
            TransferStatus.FAILED -> "FAILED"
            TransferStatus.REFUSED -> "TOO BIG"
        }
        Text("${clockTime(t.ts)} · $state", fontFamily = Slab.mono, fontSize = 10.sp, color = if (live) p.alert else p.muted)
    }
}
