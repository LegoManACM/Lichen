package dev.lichen.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaletteChoiceTest {
    @Test fun startsOnMossWithNoPrevious() {
        val c = PaletteChoice()
        assertEquals("moss", c.current)
        assertNull(c.previous)
    }

    @Test fun pickingRemembersWhatWasInUse() {
        val c = PaletteChoice().pick("ember").pick("bone")
        assertEquals(PaletteChoice("bone", "ember"), c)
    }

    @Test fun pickingTheOneInUseChangesNothing() {
        val c = PaletteChoice("ember", "moss")
        assertEquals(c, c.pick("ember"))
    }

    @Test fun swapBackToggles() {
        val c = PaletteChoice("ember", "moss")
        assertEquals(PaletteChoice("moss", "ember"), c.swapBack())
        assertEquals(c, c.swapBack().swapBack())
        assertEquals(PaletteChoice(), PaletteChoice().swapBack())
    }

    @Test fun unknownIdsFallBack() {
        assertEquals(PaletteChoice("moss", null), PaletteChoice("gone", "also-gone").sanitized())
        assertEquals(PaletteChoice("moss", null), PaletteChoice("moss", "moss").sanitized())
        assertEquals(PaletteChoice("frost", "map"), PaletteChoice("frost", "map").sanitized())
    }

    @Test fun twelvePalettesWithUniqueIds() {
        assertEquals(12, LichenPalettes.all.size)
        assertEquals(12, LichenPalettes.all.map { it.id }.toSet().size)
        assertEquals(3, LichenPalettes.all.count { it.light })
        assertEquals(LichenPalettes.Moss, LichenPalettes.byId(null))
    }
}
