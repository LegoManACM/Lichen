package dev.lichen.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lichen.core.api.Contact
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun LichenTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF9BD08A), secondary = Color(0xFFB9CCAE))
        else -> lightColorScheme(primary = Color(0xFF3F6B34), secondary = Color(0xFF55624C))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

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

@Composable
fun Avatar(contact: Contact, size: Dp = 48.dp, showOnline: Boolean = true) {
    val hue = (contact.pubkey.hashCode().toUInt() % 360u).toFloat()
    val bg = Color.hsv(hue, 0.35f, if (isSystemInDarkTheme()) 0.5f else 0.8f)
    Box(Modifier.size(size)) {
        Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Text(
                contact.displayName.trim().take(1).uppercase().ifEmpty { "?" },
                fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            )
        }
        if (showOnline && contact.online) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size / 4).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface).padding(2.dp),
            ) { Box(Modifier.size(size / 4 - 4.dp).clip(CircleShape).background(Color(0xFF2EB85C))) }
        }
    }
}

