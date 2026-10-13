package dev.lichen.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lichen.app.ui.theme.Slab
import dev.lichen.app.ui.theme.SquareAvatar
import dev.lichen.app.ui.theme.avatarTint
import dev.lichen.app.ui.theme.initialOf
import dev.lichen.core.api.Contact
import dev.lichen.core.api.Trust
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The first four groups of a key, for comparing out loud (same as identity.fingerprint). */
fun fingerprint(pubkey: String): String = (0 until 4).joinToString(" ") { i -> pubkey.drop(i * 4).take(4) }.trim()

/** "now", "12:04", "Yesterday", "Mon", "3 Oct". */
fun relativeTime(ts: Double, nowMs: Long = System.currentTimeMillis()): String {
    val ms = (ts * 1000).toLong()
    val diff = nowMs - ms
    if (diff < 60_000) return "now"
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance().apply { timeInMillis = nowMs }
    fun dayStart(cal: Calendar) = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = ((dayStart(today) - dayStart(c)) / 86_400_000L).toInt()
    return when {
        days <= 0 -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
        days == 1 -> "Yesterday"
        days < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))
    }
}

fun clockTime(ts: Double): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((ts * 1000).toLong()))

/** How someone is reachable right now, for the badge on their avatar and the tag next to their name. */
data class Connection(val label: String, val color: Color, val badge: Color)

@Composable
fun connectionOf(contact: Contact): Connection {
    val p = Slab.colors
    return when {
        contact.trust == Trust.BLOCKED -> Connection("BLOCKED", MaterialTheme.colorScheme.error, p.off)
        contact.trust == Trust.UNKNOWN -> Connection("REQUEST", p.alert, p.alert)
        contact.online -> Connection("ONLINE", p.accent, p.accent)
        else -> Connection("OFFLINE", p.muted, p.off)
    }
}

/** A contact's square avatar; with [showOnline], the connection badge in the corner. */
@Composable
fun Avatar(contact: Contact, size: Dp = 46.dp, showOnline: Boolean = true, badgeRing: Color = Slab.colors.ground) {
    SquareAvatar(
        initialOf(contact.displayName), avatarTint(contact.pubkey), size,
        badge = if (showOnline) connectionOf(contact).badge else null, badgeRing = badgeRing,
    )
}
