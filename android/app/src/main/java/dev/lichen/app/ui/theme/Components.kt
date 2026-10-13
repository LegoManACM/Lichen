package dev.lichen.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// The Slab building blocks: square, 2dp borders, hard offset shadows with no blur. See android/DESIGN.md.

/**
 * A hard drop shadow: the same shape, solid [color], moved [offset] right and down. Put it *before*
 * `background` in the chain so the background paints over it. Leave room for it (padding) at the edges.
 */
fun Modifier.hardShadow(color: Color, offset: Dp = 4.dp, shape: Shape = RectangleShape): Modifier = drawBehind {
    val o = offset.toPx()
    translate(o, o) { drawOutline(shape.createOutline(size, layoutDirection, this), color) }
}

/** The standard 2dp square border. */
fun Modifier.slabBorder(color: Color, width: Dp = 2.dp): Modifier = border(width, color, RectangleShape)

/** A 2dp dashed border, for "add" and "advanced" actions. */
fun Modifier.dashedBorder(color: Color, width: Dp = 2.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRect(
        color, topLeft = Offset(w / 2, w / 2), size = size.copy(size.width - w, size.height - w),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 3, w * 2))),
    )
}

/** The Lichen mark: a cluster of accent dots with one alert-colored fruiting body. */
@Composable
fun LichenMark(size: Dp = 30.dp) {
    val p = Slab.colors
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 26f
        drawCircle(p.accent, 6 * u, Offset(9 * u, 10 * u))
        drawCircle(p.accent.copy(alpha = 0.7f), 4 * u, Offset(17 * u, 8 * u))
        drawCircle(p.accent.copy(alpha = 0.85f), 5 * u, Offset(16 * u, 17 * u))
        drawCircle(p.alert, 2.5f * u, Offset(7 * u, 19 * u))
        drawCircle(p.accent.copy(alpha = 0.5f), 1.8f * u, Offset(21 * u, 21 * u))
    }
}

/**
 * The top bar: bar-colored, under the status bar, with a 3dp [rule] along the bottom.
 * [onBack] adds a back (or, with [close], an X) button on the left.
 */
@Composable
fun SlabTopBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    close: Boolean = false,
    rule: Color = Slab.colors.accent,
    actions: @Composable RowScope.() -> Unit = {},
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val p = Slab.colors
    Column(Modifier.fillMaxWidth().background(p.bar)) {
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides p.text) {
                if (onBack != null) {
                    SlabIconButton(
                        if (close) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                        if (close) "Close" else "Back", onBack, bordered = false,
                    )
                }
                if (content != null) content()
                else if (title != null) {
                    Text(
                        title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.01).em,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.text,
                    )
                }
                actions()
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(rule))
    }
}

/** The bar along the bottom (composer, nav, sticky actions): bar color, a 2dp line on top. */
@Composable
fun SlabBottomBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val p = Slab.colors
    Column(modifier.fillMaxWidth().background(p.bar)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.line))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content,
        )
    }
}

enum class SlabButtonStyle { Primary, Secondary, Card, Danger, Dashed }

/**
 * Buttons. Primary: filled accent with a hard shadow. Secondary: transparent with a 2dp outline.
 * Card: card-filled with an edge border (big list-style actions). Danger: outlined in the error color.
 * Dashed: dashed outline, for "add another".
 */
@Composable
fun SlabButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SlabButtonStyle = SlabButtonStyle.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 44.dp,
    shadow: Dp = 3.dp,
    alignStart: Boolean = false,
) {
    val p = Slab.colors
    val danger = androidx.compose.material3.MaterialTheme.colorScheme.error
    val (bg, fg) = when (style) {
        SlabButtonStyle.Primary -> p.accent to p.onAccent
        SlabButtonStyle.Card -> p.card to p.text
        SlabButtonStyle.Danger -> Color.Transparent to danger
        else -> Color.Transparent to p.text
    }
    var m = modifier.heightIn(min = height).alpha(if (enabled) 1f else 0.4f)
    if (style == SlabButtonStyle.Primary && enabled && shadow > 0.dp) m = m.hardShadow(p.shadow, shadow)
    m = m.background(bg)
    m = when (style) {
        SlabButtonStyle.Secondary -> m.slabBorder(p.strongLine)
        SlabButtonStyle.Card -> m.slabBorder(p.edge)
        SlabButtonStyle.Danger -> m.slabBorder(danger)
        SlabButtonStyle.Dashed -> m.dashedBorder(p.strongLine)
        SlabButtonStyle.Primary -> m
    }
    Row(
        m.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = if (alignStart) Arrangement.spacedBy(12.dp) else Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = fg)
        Text(
            text, color = fg, fontFamily = Slab.sans, fontSize = if (height >= 52.dp) 16.sp else 14.sp,
            fontWeight = if (style == SlabButtonStyle.Primary) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = if (alignStart) TextAlign.Start else TextAlign.Center, maxLines = 2,
        )
    }
}

/** A square icon button. [bordered]: 2dp outline (header actions); [filled]: accent with a shadow (send). */
@Composable
fun SlabIconButton(
    icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    bordered: Boolean = true, filled: Boolean = false, enabled: Boolean = true, size: Dp = 44.dp,
) {
    val p = Slab.colors
    var m = modifier.size(size).alpha(if (enabled) 1f else 0.4f)
    m = when {
        filled -> (if (enabled) m.hardShadow(p.shadow, 3.dp) else m).background(p.accent)
        bordered -> m.slabBorder(p.line)
        else -> m
    }
    Box(m.clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(20.dp), tint = if (filled) p.onAccent else p.text)
    }
}

/** Mono, upper case, wide tracking: "PROFILES ON THIS PHONE", "COLORS". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Slab.colors.muted) {
    Text(text.uppercase(), modifier, fontFamily = Slab.mono, fontSize = 10.sp, letterSpacing = 0.14.em, color = color)
}

/** A small filled mono tag ("3", "NEW PERSON", "4 new"). */
@Composable
fun MonoTag(
    text: String, background: Color, color: Color, modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp, shadow: Color? = null,
) {
    var m = modifier
    if (shadow != null) m = m.hardShadow(shadow, 2.dp)
    Text(
        text, m.background(background).padding(horizontal = 7.dp, vertical = 2.dp),
        fontFamily = Slab.mono, fontWeight = FontWeight.SemiBold, fontSize = fontSize, letterSpacing = 0.06.em, color = color,
        maxLines = 1,
    )
}

/** Avatar tints from the mockups: mid-tone, so dark letters read on them in every palette. */
private val AvatarTints = listOf(
    Color(0xFF9DB88A), Color(0xFFC7A541), Color(0xFF6E8C9A), Color(0xFFD9822B), Color(0xFFB9C9A3), Color(0xFFA88FB0),
)

fun avatarTint(key: String): Color = AvatarTints[(key.hashCode().toUInt() % AvatarTints.size.toUInt()).toInt()]

/**
 * A square avatar with a mono initial. [badge] is the connection square in the bottom-right corner,
 * ringed in [badgeRing] (the color behind the avatar).
 */
@Composable
fun SquareAvatar(
    initial: String, tint: Color, size: Dp = 46.dp, modifier: Modifier = Modifier,
    color: Color = Color(0xFF0B0D0A), badge: Color? = null, badgeRing: Color = Slab.colors.ground,
    shadow: Color? = null, border: Color? = null,
) {
    Box(modifier.size(size)) {
        var m = Modifier.size(size)
        if (shadow != null) m = m.hardShadow(shadow, 3.dp)
        m = m.background(tint)
        if (border != null) m = m.slabBorder(border)
        Box(m, contentAlignment = Alignment.Center) {
            Text(
                initial, color = color, fontFamily = Slab.mono, fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.39f).sp,
            )
        }
        if (badge != null) {
            val b = (size.value * 0.24f).coerceAtLeast(9f).dp
            Box(
                Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(b + 4.dp).background(badgeRing).padding(2.dp)
                    .background(badge),
            )
        }
    }
}

/** First letter of a name for an avatar ("#" stays "#"). */
fun initialOf(name: String): String = name.trim().take(1).uppercase().ifEmpty { "?" }

/** A square switch: accent track with a dark knob when on, outlined with a muted knob when off. */
@Composable
fun SlabSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = Slab.colors
    Box(
        modifier.size(50.dp, 28.dp).alpha(if (enabled) 1f else 0.4f)
            .background(if (checked) p.accent else Color.Transparent)
            .slabBorder(if (checked) p.accent else p.strongLine)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).background(if (checked) p.onAccent else p.muted))
    }
}

/** A card: card color, 2dp edge, hard shadow in the bar color. */
@Composable
fun SlabCard(
    modifier: Modifier = Modifier, border: Color = Slab.colors.edge, background: Color = Slab.colors.card,
    shadow: Color? = Slab.colors.bar, shadowOffset: Dp = 4.dp, content: @Composable ColumnScope.() -> Unit,
) {
    var m = modifier
    if (shadow != null) m = m.hardShadow(shadow, shadowOffset)
    Column(m.background(background).slabBorder(border), content = content)
}

/** Colors for OutlinedTextField in Slab: card fill, edge border, accent when focused. */
@Composable
fun slabFieldColors(): TextFieldColors {
    val p = Slab.colors
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = p.card, unfocusedContainerColor = p.card, disabledContainerColor = p.card,
        errorContainerColor = p.card,
        focusedBorderColor = p.accent, unfocusedBorderColor = p.edge, disabledBorderColor = p.edge.copy(alpha = 0.5f),
        focusedTextColor = p.text, unfocusedTextColor = p.text, cursorColor = p.accent,
        focusedLabelColor = p.accent, unfocusedLabelColor = p.muted,
        focusedPlaceholderColor = p.muted, unfocusedPlaceholderColor = p.muted,
    )
}
