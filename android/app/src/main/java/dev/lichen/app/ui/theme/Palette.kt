package dev.lichen.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * One Slab color palette. The ten base tokens are exactly the ones on the design canvas
 * (PaletteLab / SlabPalettes); the derived ones below fill in the in-between shades the mockups use.
 */
@Immutable
data class LichenPalette(
    val id: String,
    val name: String,       // tile label, upper case ("MOSS")
    val from: String,       // what lichen it comes from, shown on the preview card
    val light: Boolean,
    val ground: Color,      // screen background
    val bar: Color,         // top bar, bottom bar, popovers; also the drop shadow under cards
    val card: Color,        // cards, their bubbles, inputs
    val edge: Color,        // 2px borders
    val text: Color,
    val muted: Color,       // meta, timestamps, section labels
    val accent: Color,      // primary buttons, my bubbles, the rule under top bars
    val onAccent: Color,
    val shadow: Color,      // hard offset shadow under accent things
    val alert: Color,       // requests, file transfers
    val onAlert: Color,
    val alertShadow: Color,
) {
    /** "Moss": the name as a word, for "Use Moss". */
    val title: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    /** Secondary text: message previews, card bodies. */
    val subtle: Color get() = lerp(muted, text, 0.3f)
    /** Faint text: keys under a card body. */
    val dim: Color get() = lerp(muted, ground, 0.35f)
    /** Borders on the bars (bottom nav buttons, the bar's top edge). */
    val line: Color get() = lerp(edge, bar, 0.2f)
    /** Outline of secondary buttons. */
    val strongLine: Color get() = lerp(edge, muted, 0.1f)
    /** Background of alert-bordered cards (the requests banner, a file in a chat). */
    val alertGround: Color get() = lerp(ground, alert, if (light) 0.08f else 0.05f)
    /** Connection badge of someone who is offline. */
    val off: Color get() = lerp(edge, muted, 0.25f)
}

private fun c(hex: Long) = Color(0xFF000000 or hex)

object LichenPalettes {
    val Moss = LichenPalette(
        "moss", "MOSS", "chartreuse on dark moss", false,
        ground = c(0x121511), bar = c(0x0B0D0A), card = c(0x1C201A), edge = c(0x343B30), text = c(0xE6EADF),
        muted = c(0x8E9787), accent = c(0xC5D86D), onAccent = c(0x0B0D0A), shadow = c(0x3B4A1E),
        alert = c(0xE8892B), onAlert = c(0x1C1813), alertShadow = c(0x4A2E12),
    )
    val Ember = LichenPalette(
        "ember", "EMBER", "Xanthoria · orange on grey bark", false,
        ground = c(0x1D1E20), bar = c(0x141517), card = c(0x2A2B2E), edge = c(0x46484C), text = c(0xEEEFEC),
        muted = c(0xA9ACA6), accent = c(0xF08C1E), onAccent = c(0x141517), shadow = c(0x5A320C),
        alert = c(0x6FB7C4), onAlert = c(0x141517), alertShadow = c(0x1F4249),
    )
    val Soldier = LichenPalette(
        "soldier", "SOLDIER", "Cladonia · red caps", false,
        ground = c(0x15120E), bar = c(0x0E0C09), card = c(0x211C16), edge = c(0x3F372D), text = c(0xECE6DC),
        muted = c(0xA39888), accent = c(0xE5432B), onAccent = c(0x15120E), shadow = c(0x5C1A10),
        alert = c(0xA9C6B8), onAlert = c(0x15120E), alertShadow = c(0x3A4A42),
    )
    val Oakmoss = LichenPalette(
        "oakmoss", "OAKMOSS", "Evernia · grey-green on bark", false,
        ground = c(0x1A1C19), bar = c(0x111310), card = c(0x252822), edge = c(0x41473D), text = c(0xE6EADF),
        muted = c(0x99A28E), accent = c(0xC9D4BC), onAccent = c(0x1A1C19), shadow = c(0x4A5245),
        alert = c(0xC9B98A), onAlert = c(0x1A1C19), alertShadow = c(0x4D4632),
    )
    val Reindeer = LichenPalette(
        "reindeer", "REINDEER", "dyed reindeer moss · magenta", false,
        ground = c(0x160D14), bar = c(0x0E080D), card = c(0x22151E), edge = c(0x43303F), text = c(0xF0E4EC),
        muted = c(0xA893A2), accent = c(0xD46AAE), onAccent = c(0x160D14), shadow = c(0x5A1F47),
        alert = c(0xE8B04A), onAlert = c(0x160D14), alertShadow = c(0x5A4214),
    )
    val Lungwort = LichenPalette(
        "lungwort", "LUNGWORT", "Lobaria · teal-green", false,
        ground = c(0x0F1716), bar = c(0x0A100F), card = c(0x172221), edge = c(0x2E403D), text = c(0xE0ECE8),
        muted = c(0x8AA19B), accent = c(0x5FC7A8), onAccent = c(0x0A100F), shadow = c(0x1D4A3E),
        alert = c(0xE8A23B), onAlert = c(0x0A100F), alertShadow = c(0x5A3D10),
    )
    val Map = LichenPalette(
        "map", "MAP", "Rhizocarpon · acid yellow on black", false,
        ground = c(0x0A0A08), bar = c(0x000000), card = c(0x161612), edge = c(0x33332A), text = c(0xF2F2E4),
        muted = c(0x9A9A88), accent = c(0xD9F03A), onAccent = c(0x0A0A08), shadow = c(0x4B5410),
        alert = c(0xFF6A3D), onAlert = c(0x0A0A08), alertShadow = c(0x5C2312),
    )
    val Usnea = LichenPalette(
        "usnea", "USNEA", "old man’s beard · sage on slate", false,
        ground = c(0x1B1F22), bar = c(0x13171A), card = c(0x252B2F), edge = c(0x3E474D), text = c(0xE6ECEE),
        muted = c(0x96A2A8), accent = c(0xB7D3A8), onAccent = c(0x13171A), shadow = c(0x3D5235),
        alert = c(0xE0B25A), onAlert = c(0x13171A), alertShadow = c(0x55431C),
    )
    val Candle = LichenPalette(
        "candle", "CANDLE", "Candelaria · yolk yellow", false,
        ground = c(0x1A1812), bar = c(0x12110C), card = c(0x26231A), edge = c(0x433E2E), text = c(0xF0EBDC),
        muted = c(0xA69F88), accent = c(0xF2D23C), onAccent = c(0x1A1812), shadow = c(0x5C4E12),
        alert = c(0x7FB0D8), onAlert = c(0x1A1812), alertShadow = c(0x27425A),
    )
    val Bone = LichenPalette(
        "bone", "BONE", "deep moss on pale stone", true,
        ground = c(0xE6E4DC), bar = c(0xF4F2EC), card = c(0xF7F6F1), edge = c(0xB9B6AA), text = c(0x171A15),
        muted = c(0x5A6054), accent = c(0x2F4A2A), onAccent = c(0xF4F2EC), shadow = c(0x171A15),
        alert = c(0xA9520F), onAlert = c(0xF7F6F1), alertShadow = c(0x171A15),
    )
    val Paper = LichenPalette(
        "paper", "PAPER", "rust on warm paper", true,
        ground = c(0xF1EEE4), bar = c(0xFAF8F1), card = c(0xFFFFFF), edge = c(0xCFC9B8), text = c(0x1E1C16),
        muted = c(0x6A6555), accent = c(0xC2661A), onAccent = c(0x1E1C16), shadow = c(0x1E1C16),
        alert = c(0x2F4A2A), onAlert = c(0xFAF8F1), alertShadow = c(0x1E1C16),
    )
    val Frost = LichenPalette(
        "frost", "FROST", "pine on mint-grey", true,
        ground = c(0xE3E8E6), bar = c(0xF3F6F5), card = c(0xFBFCFC), edge = c(0xB4BFBB), text = c(0x14201C),
        muted = c(0x55645F), accent = c(0x1F6B57), onAccent = c(0xF3F6F5), shadow = c(0x14201C),
        alert = c(0xC2452B), onAlert = c(0xFBFCFC), alertShadow = c(0x14201C),
    )

    /** In picker order: the nine dark ones, then the three light ones. */
    val all: List<LichenPalette> = listOf(Moss, Ember, Soldier, Oakmoss, Reindeer, Lungwort, Map, Usnea, Candle, Bone, Paper, Frost)

    val Default = Moss

    /** The palette with this id, or the default for an unknown/missing one. */
    fun byId(id: String?): LichenPalette = all.firstOrNull { it.id == id } ?: Default
}
