package dev.lichen.app.ui

import dev.lichen.core.api.FileTransfer
import dev.lichen.core.api.GroupMessage
import dev.lichen.core.api.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupRowsTest {
    private val t0 = 1_760_000_000.0
    private fun msg(id: String, from: String, ts: Double) = GroupMessage(id, "g", from, from, ts, id, outgoing = from == "me")
    private fun file(id: String, from: String, ts: Double) =
        FileTransfer(id, "$id.bin", 10, from == "me", from, emptyList(), "g", ts, TransferStatus.PENDING)

    @Test fun filesInterleaveWithMessagesByTime() {
        val rows = groupRowsOf(listOf(msg("a", "ann", t0), msg("c", "ann", t0 + 20)), listOf(file("b", "bob", t0 + 10)))
        assertEquals(listOf("a", "f:b", "c"), rows.drop(1).map { it.key })
        assertEquals(true, rows[0] is GroupRow.Day)
    }

    @Test fun authorRunsSpanFilesAndMessages() {
        val rows = groupRowsOf(listOf(msg("a", "ann", t0), msg("d", "bob", t0 + 30)), listOf(file("b", "ann", t0 + 10), file("c", "bob", t0 + 20)))
            .drop(1)
        assertEquals(listOf(true, false, true, false), rows.map {
            when (it) {
                is GroupRow.Msg -> it.first
                is GroupRow.File -> it.first
                is GroupRow.Day -> error("one day")
            }
        })
    }
}
