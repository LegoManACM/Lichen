package dev.lichen.app.ui

import dev.lichen.core.api.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Test

class CollageTilesTest {
    private fun member(name: String, you: Boolean = false) = GroupMember(name.lowercase(), name, isOwner = false, isYou = you)
    private val me = member("Sam", you = true)
    private fun people(n: Int) = listOf(me) + (1..n).map { member("P$it") }

    @Test fun leavesYouOut() {
        assertEquals(listOf(CollageTile.Person("p1", "P1"), CollageTile.Person("p2", "P2")), collageTiles(people(2)))
    }

    @Test fun showsYouWhenAlone() {
        assertEquals(listOf(CollageTile.Person("sam", "Sam")), collageTiles(listOf(me)))
    }

    @Test fun fourOthersFillTheGrid() {
        assertEquals(4, collageTiles(people(4)).filterIsInstance<CollageTile.Person>().size)
    }

    @Test fun pastFourTheLastTileCountsTheRest() {
        val tiles = collageTiles(people(7))
        assertEquals(4, tiles.size)
        assertEquals(CollageTile.More(4), tiles.last())
    }
}
