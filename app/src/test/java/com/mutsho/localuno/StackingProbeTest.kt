package com.mutsho.localuno

import com.mutsho.localuno.model.CardColor as Col
import com.mutsho.localuno.model.CardType as T
import com.mutsho.localuno.model.Direction
import com.mutsho.localuno.model.GameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Draw cards, stacking, and the wilds that carry a penalty.
 *
 * The part of the game with the most arithmetic in it and the least direct coverage: a +2 answered
 * by a +2 answered by a +4 is four state transitions deep, and every one of them moves cards.
 */
class StackingProbeTest {

    private val P = Probe

    // ── An unstacked draw ───────────────────────────────────────────────────

    @Test fun `a draw two makes the next player take two`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.DRAW_TWO)), listOf(), listOf()),
            seatCount = 3
        )
        val before = P.count(e, "p1")
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        assertEquals(before + 2, P.count(e, "p1"))
    }

    @Test fun `a draw two skips the player who took it`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.DRAW_TWO), P.card(90, Col.BLUE, T.EIGHT)), listOf(), listOf()),
            seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        assertEquals("taking the penalty costs the turn", "p2", P.turnId(e))
    }

    @Test fun `a draw four makes the next player take four`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.DRAW_FOUR)), listOf(), listOf()),
            seatCount = 3
        )
        val before = P.count(e, "p1")
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_FOUR), Col.RED)
        assertEquals(before + 4, P.count(e, "p1"))
    }

    @Test fun `an unstacked draw conserves the deck`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.DRAW_TWO)), listOf(), listOf()),
            seatCount = 3
        )
        val before = P.allCards(e.getState()).size
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    // ── Stacking on ─────────────────────────────────────────────────────────

    @Test fun `stacking lets a draw two answer a draw two`() {
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.BLUE, T.DRAW_TWO)), listOf()),
            settings = P.settings(stacking = true),
            top = P.card(500, Col.RED, T.DRAW_TWO), colour = Col.RED,
            turn = 1, pendingDraw = 2, seatCount = 3
        )
        assertTrue(e.canPlayCard(P.card(2, Col.BLUE, T.DRAW_TWO), "p1"))
    }

    @Test fun `stacking accumulates the debt`() {
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.BLUE, T.DRAW_TWO), P.card(90, Col.BLUE, T.EIGHT)), listOf()),
            settings = P.settings(stacking = true),
            top = P.card(500, Col.RED, T.DRAW_TWO), colour = Col.RED,
            turn = 1, pendingDraw = 2, seatCount = 3
        )
        e.playCard("p1", P.card(2, Col.BLUE, T.DRAW_TWO))
        assertEquals(4, e.getState().pendingDrawCount)
    }

    @Test fun `taking a stack draws the whole tower`() {
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.BLUE, T.NINE)), listOf()),
            settings = P.settings(stacking = true),
            top = P.card(500, Col.RED, T.DRAW_TWO), colour = Col.RED,
            turn = 1, pendingDraw = 6, seatCount = 3
        )
        val before = P.count(e, "p1")
        e.drawCardForPlayer("p1")
        assertEquals(before + 6, P.count(e, "p1"))
    }

    @Test fun `taking a stack clears the debt`() {
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.BLUE, T.NINE)), listOf()),
            settings = P.settings(stacking = true),
            top = P.card(500, Col.RED, T.DRAW_TWO), colour = Col.RED,
            turn = 1, pendingDraw = 6, seatCount = 3
        )
        e.drawCardForPlayer("p1")
        assertEquals(0, e.getState().pendingDrawCount)
    }

    @Test fun `taking a stack passes the turn on`() {
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.BLUE, T.NINE)), listOf()),
            settings = P.settings(stacking = true),
            top = P.card(500, Col.RED, T.DRAW_TWO), colour = Col.RED,
            turn = 1, pendingDraw = 6, seatCount = 3
        )
        e.drawCardForPlayer("p1")
        assertNotEquals("p1", P.turnId(e))
    }

    @Test fun `taking a stack conserves the deck`() {
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.BLUE, T.NINE)), listOf()),
            settings = P.settings(stacking = true),
            top = P.card(500, Col.RED, T.DRAW_TWO), colour = Col.RED,
            turn = 1, pendingDraw = 6, seatCount = 3
        )
        val before = P.allCards(e.getState()).size
        e.drawCardForPlayer("p1")
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `a plain number cannot answer a live stack`() {
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.RED, T.NINE)), listOf()),
            settings = P.settings(stacking = true),
            top = P.card(500, Col.RED, T.DRAW_TWO), colour = Col.RED,
            turn = 1, pendingDraw = 2, seatCount = 3
        )
        assertFalse(
            "you owe two cards - a red 9 does not answer that",
            e.canPlayCard(P.card(2, Col.RED, T.NINE), "p1")
        )
    }

    @Test fun `with stacking off a draw two cannot be passed on`() {
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.BLUE, T.DRAW_TWO)), listOf()),
            settings = P.settings(stacking = false),
            top = P.card(500, Col.RED, T.DRAW_TWO), colour = Col.RED,
            turn = 1, pendingDraw = 2, seatCount = 3
        )
        assertFalse(e.canPlayCard(P.card(2, Col.BLUE, T.DRAW_TWO), "p1"))
    }

    @Test fun `a draw two cannot answer a draw four`() {
        // Same-card stacking only: the deck's own rule, and the one the board's copy states.
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.BLUE, T.DRAW_TWO)), listOf()),
            settings = P.settings(stacking = true),
            top = P.card(500, Col.RED, T.DRAW_FOUR), colour = Col.RED,
            turn = 1, pendingDraw = 4, seatCount = 3
        )
        assertFalse(e.canPlayCard(P.card(2, Col.BLUE, T.DRAW_TWO), "p1"))
    }

    @Test fun `a draw four answers a draw four`() {
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.BLUE, T.DRAW_FOUR)), listOf()),
            settings = P.settings(stacking = true),
            top = P.card(500, Col.RED, T.DRAW_FOUR), colour = Col.RED,
            turn = 1, pendingDraw = 4, seatCount = 3
        )
        assertTrue(e.canPlayCard(P.card(2, Col.BLUE, T.DRAW_FOUR), "p1"))
    }

    // ── No Mercy's heavy artillery ──────────────────────────────────────────

    @Test fun `a wild draw six costs six`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.WILD, T.WILD_DRAW_SIX)), listOf(), listOf()),
            settings = P.settings(mode = GameMode.NO_MERCY), seatCount = 3
        )
        val before = P.count(e, "p1")
        e.playCard("p0", P.card(1, Col.WILD, T.WILD_DRAW_SIX), Col.RED)
        assertEquals(before + 6, P.count(e, "p1"))
    }

    @Test fun `a wild draw ten costs ten`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.WILD, T.WILD_DRAW_TEN)), listOf(), listOf()),
            settings = P.settings(mode = GameMode.NO_MERCY), seatCount = 3
        )
        val before = P.count(e, "p1")
        e.playCard("p0", P.card(1, Col.WILD, T.WILD_DRAW_TEN), Col.RED)
        assertEquals(before + 10, P.count(e, "p1"))
    }

    @Test fun `a plus six can never be stacked even with stacking on`() {
        // Stated outright on the Custom Table screen: "+6 and +10 can never be stacked".
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.WILD, T.WILD_DRAW_SIX)), listOf()),
            settings = P.settings(mode = GameMode.NO_MERCY, stacking = true),
            top = P.card(500, Col.WILD, T.WILD_DRAW_SIX), colour = Col.RED,
            turn = 1, pendingDraw = 6, seatCount = 3
        )
        assertFalse(e.canPlayCard(P.card(2, Col.WILD, T.WILD_DRAW_SIX), "p1"))
    }

    @Test fun `a plus ten can never be stacked even with stacking on`() {
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.WILD, T.WILD_DRAW_TEN)), listOf()),
            settings = P.settings(mode = GameMode.NO_MERCY, stacking = true),
            top = P.card(500, Col.WILD, T.WILD_DRAW_TEN), colour = Col.RED,
            turn = 1, pendingDraw = 10, seatCount = 3
        )
        assertFalse(e.canPlayCard(P.card(2, Col.WILD, T.WILD_DRAW_TEN), "p1"))
    }

    @Test fun `a reverse draw four turns the direction around`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.WILD, T.WILD_REVERSE_DRAW_FOUR), P.card(90, Col.BLUE, T.EIGHT)), listOf(), listOf()),
            settings = P.settings(mode = GameMode.NO_MERCY),
            seatCount = 3, direction = Direction.CLOCKWISE
        )
        e.playCard("p0", P.card(1, Col.WILD, T.WILD_REVERSE_DRAW_FOUR), Col.RED)
        assertEquals(Direction.COUNTER_CLOCKWISE, e.getState().direction)
    }

    @Test fun `a reverse draw four hits the player the new direction points at`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.WILD, T.WILD_REVERSE_DRAW_FOUR)), listOf(), listOf()),
            settings = P.settings(mode = GameMode.NO_MERCY),
            seatCount = 3, direction = Direction.CLOCKWISE
        )
        val before = P.count(e, "p2")
        e.playCard("p0", P.card(1, Col.WILD, T.WILD_REVERSE_DRAW_FOUR), Col.RED)
        assertEquals("the direction flips first, so p2 pays", before + 4, P.count(e, "p2"))
    }

    @Test fun `a reverse draw four leaves the other neighbour alone`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.WILD, T.WILD_REVERSE_DRAW_FOUR)), listOf(), listOf()),
            settings = P.settings(mode = GameMode.NO_MERCY),
            seatCount = 3, direction = Direction.CLOCKWISE
        )
        val before = P.count(e, "p1")
        e.playCard("p0", P.card(1, Col.WILD, T.WILD_REVERSE_DRAW_FOUR), Col.RED)
        assertEquals(before, P.count(e, "p1"))
    }

    @Test fun `skip everyone returns the turn to the player`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.SKIP_EVERYONE)), listOf(), listOf()),
            settings = P.settings(mode = GameMode.NO_MERCY), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.SKIP_EVERYONE))
        assertEquals("everyone else is skipped, so it comes back round", "p0", P.turnId(e))
    }

    @Test fun `discard all empties every card of that colour from your hand`() {
        val e = P.table(
            listOf(
                listOf(
                    P.card(1, Col.RED, T.DISCARD_ALL),
                    P.card(2, Col.RED, T.NINE),
                    P.card(3, Col.RED, T.ONE),
                    P.card(4, Col.BLUE, T.FIVE)
                ),
                listOf(), listOf()
            ),
            settings = P.settings(mode = GameMode.NO_MERCY), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DISCARD_ALL))
        assertFalse(
            "no red should remain in hand",
            P.hand(e, "p0").any { it.color == Col.RED }
        )
    }

    @Test fun `discard all keeps the other colours`() {
        val e = P.table(
            listOf(
                listOf(
                    P.card(1, Col.RED, T.DISCARD_ALL),
                    P.card(2, Col.RED, T.NINE),
                    P.card(4, Col.BLUE, T.FIVE)
                ),
                listOf(), listOf()
            ),
            settings = P.settings(mode = GameMode.NO_MERCY), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DISCARD_ALL))
        assertTrue(P.hand(e, "p0").any { it.id == 9004 })
    }

    @Test fun `discard all conserves the deck`() {
        val e = P.table(
            listOf(
                listOf(
                    P.card(1, Col.RED, T.DISCARD_ALL),
                    P.card(2, Col.RED, T.NINE),
                    P.card(4, Col.BLUE, T.FIVE)
                ),
                listOf(), listOf()
            ),
            settings = P.settings(mode = GameMode.NO_MERCY), seatCount = 3
        )
        val before = P.allCards(e.getState()).size
        e.playCard("p0", P.card(1, Col.RED, T.DISCARD_ALL))
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `the pending draw count never goes negative`() {
        val e = P.table(
            listOf(listOf(), listOf(P.card(2, Col.BLUE, T.NINE)), listOf()),
            settings = P.settings(stacking = true),
            top = P.card(500, Col.RED, T.DRAW_TWO), colour = Col.RED,
            turn = 1, pendingDraw = 2, seatCount = 3
        )
        e.drawCardForPlayer("p1")
        assertTrue(e.getState().pendingDrawCount >= 0)
    }

    @Test fun `a live stack is reported to everyone by the state`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.DRAW_TWO), P.card(90, Col.BLUE, T.EIGHT)), listOf(), listOf()),
            settings = P.settings(stacking = true), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        assertEquals("the next player owes two", 2, e.getState().pendingDrawCount)
    }
}
