package com.mutsho.localuno

import com.mutsho.localuno.engine.SeedJournal
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.ui.components.occurrenceIndices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the round that was on screen when the app died, move for move.
 *
 * The crash is a slot-table failure inside the Compose recomposer with no application code on the
 * trace, so the report says nothing about what the app was doing. The seed log does - it was added
 * for exactly this and this is its first real use. The captured round is checked in as a test
 * resource so the state at the moment of death is a thing anyone can inspect on a desktop JVM
 * instead of a thing that has to be provoked again on a device.
 *
 * What this can and cannot settle: the crash is timing- and UI-dependent, so replaying the moves
 * will never reproduce it here. What it CAN do is rule game state in or out as the trigger - if the
 * board was being handed something structurally impossible (a hand holding the same card twice,
 * which is the one shape known to corrupt the slot table through duplicate composition keys), that
 * shows up here immediately and unambiguously.
 */
class CrashReplayTest {

    private fun log(): String =
        javaClass.classLoader!!.getResourceAsStream("crash-2026-08-21.seedlog")!!
            .bufferedReader().readText()

    @Test fun `the captured round replays`() {
        val engine = SeedJournal.replay(log())
        // Sanity: it actually got somewhere, rather than bouncing off the first move.
        assertTrue(engine.getState().sequenceNumber > 100)
    }

    @Test fun `no hand ever holds the same card twice`() {
        // The one game-state shape that is known to be able to cause THIS crash: two cards sharing
        // an id in one hand means two siblings sharing a composition key, and Compose does not fail
        // where the duplicate is - it takes down a later frame from inside the runtime with no
        // application frames, which is exactly the report we have. HandArc.occurrenceIndices was
        // written to defuse it; this checks whether it was ever being asked to.
        val rec = SeedJournal.parse(log())
        val engine = SeedJournal.replay(log(), upTo = 0)
        for (step in rec.moves.indices) {
            val e = SeedJournal.replay(log(), upTo = step)
            e.getState().players.forEach { p ->
                val ids = p.hand.map { it.id }
                assertEquals(
                    "duplicate card in ${p.id}'s hand after $step moves: $ids",
                    ids.size, ids.toSet().size
                )
            }
        }
        // And the whole table, not just within one hand.
        val all = Probe.allCards(engine.getState())
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test fun `every hand produces collision-free composition keys`() {
        // The property HandArc actually depends on, asserted directly rather than inferred from the
        // absence of duplicate ids.
        val rec = SeedJournal.parse(log())
        for (step in rec.moves.indices step 5) {
            SeedJournal.replay(log(), upTo = step).getState().players.forEach { p ->
                val ids = p.hand.map { it.id }
                val keys = ids.zip(occurrenceIndices(ids))
                assertEquals("colliding keys in ${p.id}'s hand at move $step", keys.size, keys.toSet().size)
            }
        }
    }

    /**
     * The replay no longer ends where the real session did, and that is now the point of it.
     *
     * This used to assert CHOOSING_SWAP - the position actually on screen when the app died, with
     * the board holding a hand transfer and the swap picker at the same moment. It stopped being
     * true when the two-player Reverse Draw Four special case was removed from the engine, and the
     * divergence is recorded here rather than quietly rewritten to match, because a replay that
     * silently stops reproducing the captured position is a diagnostic tool that has become a
     * decoration.
     *
     * Why a FOUR-seat table went through a two-player branch at all: this is No Mercy, so knockouts
     * take players out mid-round, and the old branch keyed off the number of players still
     * CONNECTED rather than the number of seats. The journal holds five Reverse Draw Fours, and by
     * the time some of them were played the table really was down to two. So this recording is also
     * direct evidence that the bug being fixed was reachable in an ordinary game, not a corner case.
     *
     * The moves before that point still replay identically, which is what the three tests above
     * depend on - they walk the round move by move and stop mattering only after the divergence.
     */
    @Test fun `the replay diverges from the captured round at the reverse draw four fix`() {
        val engine = SeedJournal.replay(log())
        val s = engine.getState()

        // Under current rules the round does not reach the swap the player was looking at.
        assertEquals(GamePhase.PLAYING, s.phase)
        assertTrue("the replay still has to get somewhere real", s.sequenceNumber > 100)
    }

    @Test fun `the captured table really did fall to two active players`() {
        // The premise of the test above, checked rather than asserted in prose: without this, the
        // explanation for the divergence is just a story someone told about a resource file.
        val rec = SeedJournal.parse(log())
        val everFellToTwo = rec.moves.indices.any { step ->
            SeedJournal.replay(log(), upTo = step).getState()
                .players.count { it.isConnected } == 2
        }
        assertTrue(
            "a 4-seat table can only reach the old two-player branch via No Mercy knockouts",
            everFellToTwo
        )
    }
}
