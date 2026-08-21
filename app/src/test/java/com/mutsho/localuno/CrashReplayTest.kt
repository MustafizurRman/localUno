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

    @Test fun `the round the app died in ended in a swap`() {
        // Not a correctness claim - a record of the position, so a later theory can be checked
        // against what was actually on screen. The last two moves were a Discard All and then a 7
        // with seven-swaps on, which means the board was holding the hand-transfer animation and
        // the swap picker at the same moment.
        val engine = SeedJournal.replay(log())
        val s = engine.getState()
        assertEquals(GamePhase.CHOOSING_SWAP, s.phase)
    }
}
