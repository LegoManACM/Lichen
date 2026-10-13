package dev.lichen.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.view.WindowCompat
import dev.lichen.app.R

/** The palette in use. Read it through [Slab.colors]. */
val LocalLichenPalette = staticCompositionLocalOf { LichenPalettes.Default }

/** Shortcuts for Slab screens: `Slab.colors.accent`, `Slab.mono`. */
object Slab {
    val colors: LichenPalette
        @Composable get() = LocalLichenPalette.current

    val sans = FontFamily(
        Font(R.font.plex_sans_regular, FontWeight.Normal),
        Font(R.font.plex_sans_medium, FontWeight.Medium),
        Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
        Font(R.font.plex_sans_bold, FontWeight.Bold),
    )
    val mono = FontFamily(
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium),
        Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
    )
}

/** Zero radius everywhere: Material components that take their shape from the theme come out square. */
private val SquareShapes = RoundedCornerShape(0.dp).let { Shapes(extraSmall = it, small = it, medium = it, large = it, extraLarge = it) }

private val SlabTypography: Typography = Typography().run {
    fun TextStyle.sans() = copy(fontFamily = Slab.sans)
    fun TextStyle.mono() = copy(fontFamily = Slab.mono, letterSpacing = 0.06.em)
    Typography(
        displayLarge = displayLarge.sans().copy(fontWeight = FontWeight.Bold),
        displayMedium = displayMedium.sans().copy(fontWeight = FontWeight.Bold),
        displaySmall = displaySmall.sans().copy(fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.sans().copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.sans().copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.sans().copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.sans().copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.sans().copy(fontWeight = FontWeight.Bold),
        titleSmall = titleSmall.sans().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.sans(),
        bodyMedium = bodyMedium.sans(),
        bodySmall = bodySmall.sans(),
        labelLarge = labelLarge.sans().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.mono(),
        labelSmall = labelSmall.mono(),
    )
}

private fun schemeOf(p: LichenPalette) = if (p.light) {
    lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accent, onPrimaryContainer = p.onAccent,
        secondary = p.muted, onSecondary = p.ground, secondaryContainer = p.card, onSecondaryContainer = p.text,
        tertiary = p.alert, onTertiary = p.onAlert, tertiaryContainer = p.alertGround, onTertiaryContainer = p.text,
        background = p.ground, onBackground = p.text, surface = p.ground, onSurface = p.text,
        surfaceVariant = p.card, onSurfaceVariant = p.muted, surfaceTint = Color.Transparent,
        surfaceContainerLowest = p.ground, surfaceContainerLow = p.card, surfaceContainer = p.card,
        surfaceContainerHigh = p.card, surfaceContainerHighest = p.card, surfaceBright = p.card, surfaceDim = p.ground,
        inverseSurface = p.text, inverseOnSurface = p.ground, inversePrimary = p.accent,
        outline = p.edge, outlineVariant = p.line, scrim = Color.Black,
        error = Color(0xFFB3261E), onError = Color.White,
        errorContainer = lerp(p.card, Color(0xFFB3261E), 0.12f), onErrorContainer = p.text,
    )
} else {
    darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accent, onPrimaryContainer = p.onAccent,
        secondary = p.muted, onSecondary = p.ground, secondaryContainer = p.card, onSecondaryContainer = p.text,
        tertiary = p.alert, onTertiary = p.onAlert, tertiaryContainer = p.alertGround, onTertiaryContainer = p.text,
        background = p.ground, onBackground = p.text, surface = p.ground, onSurface = p.text,
        surfaceVariant = p.card, onSurfaceVariant = p.muted, surfaceTint = Color.Transparent,
        surfaceContainerLowest = p.bar, surfaceContainerLow = p.card, surfaceContainer = p.card,
        surfaceContainerHigh = p.card, surfaceContainerHighest = p.card, surfaceBright = p.card, surfaceDim = p.ground,
        inverseSurface = p.text, inverseOnSurface = p.ground, inversePrimary = p.accent,
        outline = p.edge, outlineVariant = p.line, scrim = Color.Black,
        error = Color(0xFFFF6B5B), onError = p.bar,
        errorContainer = lerp(p.card, Color(0xFFFF6B5B), 0.15f), onErrorContainer = p.text,
    )
}

/** The app theme: the palette the user picked (persisted in [ThemePrefs]), mapped onto Material 3. */
@Composable
fun LichenTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val choice by ThemePrefs.choice(ctx).collectAsState()
    LichenTheme(LichenPalettes.byId(choice.current), content)
}

@Composable
fun LichenTheme(palette: LichenPalette, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = palette.light
                isAppearanceLightNavigationBars = palette.light
            }
        }
    }
    CompositionLocalProvider(LocalLichenPalette provides palette) {
        MaterialTheme(colorScheme = schemeOf(palette), shapes = SquareShapes, typography = SlabTypography, content = content)
    }
}
