package dev.lichen.app

import android.content.Context
import dev.lichen.app.fake.FakeSetup
import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.LichenSetup
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * The one place that decides which implementation the app runs on.
 * To use the real node, change [setup] to the core's LichenSetup implementation (one line).
 */
object Backend {
    /** Swap this for the real LichenSetup once the core lands. */
    val setup: LichenSetup = FakeSetup()

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
