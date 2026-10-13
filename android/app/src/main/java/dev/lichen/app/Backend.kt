package dev.lichen.app

import android.content.Context
import dev.lichen.core.FileSetup
import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.LichenSetup
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * The one place that decides which implementation the app runs on.
 * The real node (FileSetup); the fakes in dev.lichen.app.fake are for previews.
 */
object Backend {
    val setup: LichenSetup = FileSetup()

    fun dataDir(context: Context): File = File(context.applicationContext.filesDir, "lichen").also { it.mkdirs() }

    private var current: ChatClient? = null

    /** The app-wide client (opened, not necessarily started; LichenService starts and stops it). */
    @Synchronized
    fun client(context: Context): ChatClient =
        current ?: setup.open(dataDir(context)).also { current = it }

    /** Forget the client, e.g. after the hubs changed and it has to be re-opened. */
    @Synchronized
    fun reset() { current = null }

    /** Pubkey of the chat on screen right now (null when none, or the app isn't visible). */
    val currentChat = MutableStateFlow<String?>(null)

    /** A notification tap asks the UI to open this chat. */
    val openChat = MutableStateFlow<String?>(null)
}
