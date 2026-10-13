package dev.lichen.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The palette in use and the one before it (Settings shows both, so a change is one tap to undo).
 * Plain ids, no Android, so it is unit-tested on the JVM.
 */
data class PaletteChoice(val current: String = LichenPalettes.Default.id, val previous: String? = null) {
    /** Use [id]; what was in use becomes the previous one. Picking the one in use changes nothing. */
    fun pick(id: String): PaletteChoice = if (id == current) this else PaletteChoice(id, current)

    /** Go back to the previous palette (and keep this one as previous, so it toggles). */
    fun swapBack(): PaletteChoice = previous?.let { PaletteChoice(it, current) } ?: this

    /** Unknown ids (a palette that was removed) fall back to the default, and drop a stale previous. */
    fun sanitized(): PaletteChoice {
        val cur = LichenPalettes.byId(current).id
        val prev = previous?.takeIf { p -> p != cur && LichenPalettes.all.any { it.id == p } }
        return PaletteChoice(cur, prev)
    }
}

/** The palette choice, kept in the app's UI preferences ("lichen_ui", next to the other UI flags). */
object ThemePrefs {
    private const val FILE = "lichen_ui"
    private const val KEY_CURRENT = "palette"
    private const val KEY_PREVIOUS = "palette_previous"

    private var state: MutableStateFlow<PaletteChoice>? = null

    fun choice(context: Context): StateFlow<PaletteChoice> = flow(context)

    @Synchronized
    private fun flow(context: Context): MutableStateFlow<PaletteChoice> = state ?: run {
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val loaded = PaletteChoice(
            p.getString(KEY_CURRENT, null) ?: LichenPalettes.Default.id,
            p.getString(KEY_PREVIOUS, null),
        ).sanitized()
        MutableStateFlow(loaded).also { state = it }
    }

    fun pick(context: Context, id: String) = update(context) { it.pick(id) }

    fun swapBack(context: Context) = update(context) { it.swapBack() }

    @Synchronized
    private fun update(context: Context, f: (PaletteChoice) -> PaletteChoice) {
        val flow = flow(context)
        val next = f(flow.value).sanitized()
        if (next == flow.value) return
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_CURRENT, next.current)
            .putString(KEY_PREVIOUS, next.previous)
            .apply()
        flow.value = next
    }
}
