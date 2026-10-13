package dev.lichen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.lichen.app.ui.theme.*

/**
 * A palette as a little picture of itself: its ground, a card bar (theirs), an accent bar with its
 * shadow (mine), and its name. The selected one gets the app's accent ring and a hard shadow.
 */
@Composable
private fun PaletteTile(
    palette: LichenPalette, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    height: Dp = 66.dp, role: String? = null,
) {
    val app = Slab.colors
    val big = height >= 80.dp
    var m = modifier.height(height)
    if (selected) m = m.hardShadow(app.shadow, 3.dp)
    m = m.background(palette.ground).slabBorder(if (selected) app.accent else app.edge)
    Column(
        m.semantics(mergeDescendants = true) {
            contentDescription = "${palette.title} colors" + (role?.let { ", ${it.lowercase()}" } ?: "")
            this.selected = selected
        }.clickable(role = Role.RadioButton, onClick = onClick).padding(if (big) 8.dp else 7.dp),
        verticalArrangement = Arrangement.spacedBy(if (big) 6.dp else 5.dp),
    ) {
        val bar = if (big) 11.dp else 8.dp
        Box(Modifier.fillMaxWidth(0.64f).height(bar).background(palette.card).border(1.dp, palette.edge))
        Box(
            Modifier.align(Alignment.End).padding(end = 2.dp).fillMaxWidth(0.56f).height(bar)
                .hardShadow(palette.shadow, 2.dp).background(palette.accent),
        )
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                palette.name, fontFamily = Slab.mono, fontWeight = FontWeight.SemiBold, fontSize = if (big) 11.sp else 10.sp,
                letterSpacing = 0.08.em, color = palette.text, maxLines = 1, lineHeight = 13.sp,
            )
            if (role != null) {
                Text(role, fontFamily = Slab.mono, fontSize = 9.sp, letterSpacing = 0.1.em, lineHeight = 11.sp, color = palette.text.copy(alpha = 0.75f))
            }
        }
    }
}

/**
 * Settings' COLORS row: the palette IN USE, the PREVIOUS one (tap to swap back) and ALL 12, which
 * opens the full picker.
 */
@Composable
fun ColorsSection(onAll: () -> Unit) {
    val ctx = LocalContext.current
    val app = Slab.colors
    val choice by ThemePrefs.choice(ctx).collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("COLORS")
        Row(Modifier.fillMaxWidth().padding(end = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PaletteTile(LichenPalettes.byId(choice.current), true, {}, Modifier.weight(1f), 84.dp, role = "IN USE")
            val prev = choice.previous?.let { LichenPalettes.byId(it) }
            if (prev != null) {
                PaletteTile(prev, false, { ThemePrefs.swapBack(ctx) }, Modifier.weight(1f), 84.dp, role = "PREVIOUS")
            } else {
                Box(Modifier.weight(1f).height(84.dp).dashedBorder(app.edge).padding(8.dp), contentAlignment = Alignment.BottomStart) {
                    Text("NO PREVIOUS", fontFamily = Slab.mono, fontSize = 9.sp, letterSpacing = 0.1.em, color = app.muted)
                }
            }
            Column(
                Modifier.weight(1f).height(84.dp).background(app.card).slabBorder(app.edge)
                    .semantics(mergeDescendants = true) { contentDescription = "Browse all color palettes" }
                    .clickable(role = Role.Button, onClick = onAll).padding(8.dp),
            ) {
                // Two rows of six accent chips, one per palette.
                LichenPalettes.all.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        row.forEach { Box(Modifier.weight(1f).height(9.dp).background(it.accent)) }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ALL ${LichenPalettes.all.size}", Modifier.weight(1f), fontFamily = Slab.mono, fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp, letterSpacing = 0.08.em, color = app.text,
                    )
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = app.text)
                }
            }
        }
    }
}

/** All the palettes, dark then light, with a live preview of the one picked; "Use X" applies it. */
@Composable
fun PaletteScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = Slab.colors
    val choice by ThemePrefs.choice(ctx).collectAsState()
    var sel by rememberSaveable { mutableStateOf(choice.current) }
    val t = LichenPalettes.byId(sel)
    Scaffold(
        containerColor = app.ground,
        topBar = { SlabTopBar("Colors", onBack = onBack) },
        bottomBar = {
            SlabBottomBar {
                SlabButton(
                    if (sel == choice.current) "Using ${t.title}" else "Use ${t.title}",
                    { ThemePrefs.pick(ctx, sel); onBack() }, Modifier.weight(1f).padding(end = 4.dp, bottom = 4.dp),
                    height = 52.dp, shadow = 4.dp, enabled = sel != choice.current,
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            // Live preview: the picked palette's own colors, whatever the app is using right now.
            Column(
                Modifier.padding(start = 14.dp, end = 18.dp, top = 14.dp).fillMaxWidth()
                    .hardShadow(app.bar).background(t.ground).slabBorder(t.edge).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.name, fontFamily = Slab.mono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.1.em, color = t.text)
                    Text(
                        t.from, fontSize = 11.sp, fontStyle = FontStyle.Italic, color = t.text.copy(alpha = 0.75f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "are you on the new hub yet?", Modifier.background(t.card).slabBorder(t.edge).padding(horizontal = 9.dp, vertical = 6.dp),
                    fontSize = 13.sp, color = t.text,
                )
                Text(
                    "yep, merged instantly",
                    Modifier.align(Alignment.End).padding(end = 3.dp, bottom = 3.dp).hardShadow(t.shadow, 3.dp).background(t.accent)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.onAccent,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("1", Modifier.background(t.alert).padding(horizontal = 7.dp, vertical = 1.dp), fontFamily = Slab.mono,
                        fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = t.onAlert)
                    Text("Requests", fontSize = 12.sp, color = t.muted)
                }
            }

            Column(Modifier.padding(start = 14.dp, end = 17.dp, top = 14.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("DARK")
                PaletteGrid(LichenPalettes.all.filter { !it.light }, sel) { sel = it }
                SectionLabel("LIGHT", Modifier.padding(top = 6.dp))
                PaletteGrid(LichenPalettes.all.filter { it.light }, sel) { sel = it }
            }
        }
    }
}

@Composable
private fun PaletteGrid(palettes: List<LichenPalette>, selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        palettes.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { PaletteTile(it, it.id == selected, { onPick(it.id) }, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
