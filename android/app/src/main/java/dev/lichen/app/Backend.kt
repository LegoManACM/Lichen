package dev.lichen.app

import android.content.Context
import dev.lichen.app.fake.FakeSetup
import dev.lichen.core.api.LichenSetup
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** A chat on screen, or one a notification wants opened. */
data class OpenChat(val profileId: String, val pubkey: String)

/**
 * The one place that decides which implementation the app runs on.
 * To use the real node, change [setup] to the core's LichenSetup implementation (one line).
 */
object Backend {
    /** Swap this for the real LichenSetup once the core lands. */
    val setup: LichenSetup = FakeSetup()

    private var profiles: Profiles? = null

    /** The app-wide profile manager (all profiles, one opened client each). */
    @Synchronized
    fun profiles(context: Context): Profiles = profiles
        ?: Profiles(File(context.applicationContext.filesDir, "lichen"), setup).also { profiles = it }

    /** The chat on screen right now (null when none, or the app isn't visible). */
    val currentChat = MutableStateFlow<OpenChat?>(null)

    /** A notification tap asks the UI to switch to this profile and open this chat. */
    val openChat = MutableStateFlow<OpenChat?>(null)
}
