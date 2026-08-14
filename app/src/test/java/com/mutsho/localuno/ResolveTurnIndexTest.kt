package com.mutsho.localuno

import com.mutsho.localuno.model.Player
import com.mutsho.localuno.viewmodel.resolveTurnIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one index in the app that crosses a device boundary: the host says "seat 3 has the turn", and
 * a client has to decide what that means for ITS roster.
 *
 * The failure mode being guarded is quiet rather than loud - GameState.currentPlayer reads through
 * getOrNull, so an out-of-range value blanks the turn indicator instead of throwing. That makes it
 * both easy to ship and hard to notice, which is why the range guarantee is asserted rather than
 * trusted to the one `getOrNull` currently standing between it and a crash.
 */
class ResolveTurnIndexTest {

    private fun roster(vararg ids: String) = ids.map { Player(id = it, name = it, avatarColor = 0) }

    @Test
    fun `identity wins even when the host's index disagrees`() {
        val players = roster("a", "b", "c")
        // Rosters ordered differently on the two devices: the id is the only trustworthy signal.
        assertEquals(2, resolveTurnIndex(players, "c", hostIndex = 0, previousIndex = 0))
    }

    @Test
    fun `falls back to the host index when the id is unknown but the index fits`() {
        val players = roster("a", "b", "c")
        assertEquals(1, resolveTurnIndex(players, "ghost", hostIndex = 1, previousIndex = 0))
    }

    @Test
    fun `refuses a host index that points past our roster`() {
        // The host has five seats, we know about three - the real divergence case.
        val players = roster("a", "b", "c")
        assertEquals(2, resolveTurnIndex(players, "ghost", hostIndex = 4, previousIndex = 2))
        assertEquals(0, resolveTurnIndex(players, "ghost", hostIndex = 99, previousIndex = 0))
        assertEquals(0, resolveTurnIndex(players, "ghost", hostIndex = -1, previousIndex = 0))
    }

    @Test
    fun `clamps a stale previous index too`() {
        // Our own last-known index can be stale if the roster shrank under a knockout.
        val players = roster("a", "b")
        assertEquals(1, resolveTurnIndex(players, "ghost", hostIndex = 7, previousIndex = 5))
        assertEquals(0, resolveTurnIndex(players, "ghost", hostIndex = 7, previousIndex = -3))
    }

    @Test
    fun `an empty roster never throws`() {
        assertEquals(0, resolveTurnIndex(emptyList(), "a", hostIndex = 3, previousIndex = 2))
    }

    @Test
    fun `the result is always a legal index`() {
        val players = roster("a", "b", "c", "d")
        for (host in -5..10) {
            for (prev in -5..10) {
                for (id in listOf("a", "d", "nobody")) {
                    val i = resolveTurnIndex(players, id, host, prev)
                    assertTrue(
                        "id=$id host=$host prev=$prev gave $i",
                        i in players.indices
                    )
                }
            }
        }
    }
}
