package com.mutsho.localuno

import com.mutsho.localuno.model.CardColor as Col
import com.mutsho.localuno.model.CardType as T
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.TimeoutAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table around the cards: UNO calls, scoring, hand-moving rules, knockouts, players coming and
 * going, and the turn clock.
 *
 * These are the paths a real evening actually exercises and a solo soak never does - somebody puts
 * their phone down, somebody walks out of Wi-Fi range, somebody forgets to say UNO.
 */
class TableStateProbeTest {

    private val P = Probe

    // ── Saying UNO ──────────────────────────────────────────────────────────

    @Test fun `UNO can be called at two cards`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE), P.card(2, Col.RED, T.ONE)), listOf()),
            seatCount = 2
        )
        e.callUno("p0")
        assertTrue(e.getState().getPlayerById("p0")?.hasCalledUno == true)
    }

    @Test fun `UNO cannot be called holding a full hand`() {
        val e = P.table(
            listOf(
                listOf(
                    P.card(1, Col.RED, T.NINE), P.card(2, Col.RED, T.ONE),
                    P.card(3, Col.RED, T.TWO), P.card(4, Col.RED, T.THREE)
                ),
                listOf()
            ),
            seatCount = 2
        )
        e.callUno("p0")
        assertFalse(e.getState().getPlayerById("p0")?.hasCalledUno == true)
    }

    @Test fun `a called UNO survives the play that empties you to one`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE), P.card(2, Col.RED, T.ONE)), listOf()),
            seatCount = 2
        )
        e.callUno("p0")
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        assertTrue(e.getState().getPlayerById("p0")?.hasCalledUno == true)
    }

    @Test fun `being caught without calling costs cards`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(P.card(2, Col.BLUE, T.ONE))),
            seatCount = 2
        )
        val before = P.count(e, "p0")
        e.penalizeUnoNotCalled("p1", "p0")
        assertTrue("the catch has to cost something", P.count(e, "p0") > before)
    }

    @Test fun `a player who did call cannot be caught`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE), P.card(2, Col.RED, T.ONE)), listOf()),
            seatCount = 2
        )
        e.callUno("p0")
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        val before = P.count(e, "p0")
        e.penalizeUnoNotCalled("p1", "p0")
        assertEquals(before, P.count(e, "p0"))
    }

    @Test fun `a player holding several cards cannot be caught`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.NINE), P.card(2, Col.RED, T.ONE), P.card(3, Col.RED, T.TWO)),
                listOf()
            ),
            seatCount = 2
        )
        val before = P.count(e, "p0")
        e.penalizeUnoNotCalled("p1", "p0")
        assertEquals(before, P.count(e, "p0"))
    }

    @Test fun `a UNO catch conserves the deck`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(P.card(2, Col.BLUE, T.ONE))),
            seatCount = 2
        )
        val before = P.allCards(e.getState()).size
        e.penalizeUnoNotCalled("p1", "p0")
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `drawing clears a previously called UNO`() {
        // You said UNO at one card, then drew - you are no longer on one card.
        val e = P.table(
            listOf(listOf(P.card(1, Col.BLUE, T.NINE), P.card(2, Col.BLUE, T.ONE)), listOf()),
            colour = Col.RED, seatCount = 2
        )
        e.callUno("p0")
        e.drawCardForPlayer("p0")
        assertFalse(e.getState().getPlayerById("p0")?.hasCalledUno == true)
    }

    // ── Scoring ─────────────────────────────────────────────────────────────

    @Test fun `a number card is worth its face value`() {
        assertEquals(9, com.mutsho.localuno.model.Card.calculatePoints(T.NINE))
    }

    @Test fun `a zero is worth nothing`() {
        assertEquals(0, com.mutsho.localuno.model.Card.calculatePoints(T.ZERO))
    }

    @Test fun `an action card outscores every number`() {
        assertTrue(
            com.mutsho.localuno.model.Card.calculatePoints(T.SKIP) >
                com.mutsho.localuno.model.Card.calculatePoints(T.NINE)
        )
    }

    @Test fun `a wild outscores an action card`() {
        assertTrue(
            com.mutsho.localuno.model.Card.calculatePoints(T.WILD) >
                com.mutsho.localuno.model.Card.calculatePoints(T.SKIP)
        )
    }

    @Test fun `the winner scores what everyone else still holds`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(P.card(2, Col.BLUE, T.FIVE))),
            seatCount = 2
        )
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        assertEquals(5, e.calculateScores()["p0"])
    }

    @Test fun `a loser is scored on what they were caught holding`() {
        // Not zero: the loser's score IS their hand's value, which is what makes exploding at the
        // mercy line rank worse than surviving with a small hand. See calculateScores.
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(P.card(2, Col.BLUE, T.FIVE))),
            seatCount = 2
        )
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        assertEquals(5, e.calculateScores()["p1"])
    }

    @Test fun `scores are never negative`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(P.card(2, Col.BLUE, T.FIVE))),
            seatCount = 2
        )
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        assertTrue(e.calculateScores().values.all { it >= 0 })
    }

    // ── The hand-moving rules ───────────────────────────────────────────────

    @Test fun `a zero rotates hands when the rule is on`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.ZERO), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO)),
                listOf(P.card(4, Col.BLUE, T.THREE))
            ),
            settings = P.settings(zeroRotates = true), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.ZERO))
        assertFalse(
            "p0's remaining card should have moved on",
            P.hand(e, "p0").any { it.id == 9002 }
        )
    }

    @Test fun `a zero is an ordinary number when the rule is off`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.ZERO), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO)),
                listOf(P.card(4, Col.BLUE, T.THREE))
            ),
            settings = P.settings(zeroRotates = false), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.ZERO))
        assertTrue(P.hand(e, "p0").any { it.id == 9002 })
    }

    @Test fun `a rotation conserves the deck`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.ZERO), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO)),
                listOf(P.card(4, Col.BLUE, T.THREE))
            ),
            settings = P.settings(zeroRotates = true), seatCount = 3
        )
        val before = P.allCards(e.getState()).size
        e.playCard("p0", P.card(1, Col.RED, T.ZERO))
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `a seven asks who to swap with`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.SEVEN), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(sevenSwaps = true), seatCount = 2
        )
        e.playCard("p0", P.card(1, Col.RED, T.SEVEN))
        assertEquals(GamePhase.CHOOSING_SWAP, e.getState().phase)
    }

    @Test fun `choosing a swap target exchanges the two hands`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.SEVEN), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(sevenSwaps = true), seatCount = 2
        )
        e.playCard("p0", P.card(1, Col.RED, T.SEVEN))
        e.chooseSwapTarget("p0", "p1")
        assertTrue("p0 should now hold p1's card", P.hand(e, "p0").any { it.id == 9003 })
    }

    @Test fun `a swap conserves the deck`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.SEVEN), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(sevenSwaps = true), seatCount = 2
        )
        val before = P.allCards(e.getState()).size
        e.playCard("p0", P.card(1, Col.RED, T.SEVEN))
        e.chooseSwapTarget("p0", "p1")
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `a swap leaves the round playable`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.SEVEN), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(sevenSwaps = true), seatCount = 2
        )
        e.playCard("p0", P.card(1, Col.RED, T.SEVEN))
        e.chooseSwapTarget("p0", "p1")
        assertEquals(GamePhase.PLAYING, e.getState().phase)
    }

    @Test fun `a seven is an ordinary number when the rule is off`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.SEVEN), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(sevenSwaps = false), seatCount = 2
        )
        e.playCard("p0", P.card(1, Col.RED, T.SEVEN))
        assertNotEquals(GamePhase.CHOOSING_SWAP, e.getState().phase)
    }

    @Test fun `you cannot swap with yourself`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.SEVEN), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(sevenSwaps = true), seatCount = 2
        )
        e.playCard("p0", P.card(1, Col.RED, T.SEVEN))
        e.chooseSwapTarget("p0", "p0")
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `an unanswered swap resolves itself rather than hanging the round`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.SEVEN), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(sevenSwaps = true), seatCount = 2
        )
        e.playCard("p0", P.card(1, Col.RED, T.SEVEN))
        e.autoResolveSwap()
        assertNotEquals(GamePhase.CHOOSING_SWAP, e.getState().phase)
    }

    @Test fun `no swap stays pending once it is resolved`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.SEVEN), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(sevenSwaps = true), seatCount = 2
        )
        e.playCard("p0", P.card(1, Col.RED, T.SEVEN))
        e.chooseSwapTarget("p0", "p1")
        assertNull(e.getState().pendingSwapPlayerId)
    }

    // ── People coming and going ─────────────────────────────────────────────

    @Test fun `a disconnect pauses the table`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(), listOf()), seatCount = 3)
        e.beginDisconnectCountdown("p1")
        assertEquals(GamePhase.PAUSED_DISCONNECT, e.getState().phase)
    }

    @Test fun `a disconnect names who dropped`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(), listOf()), seatCount = 3)
        e.beginDisconnectCountdown("p1")
        assertEquals("p1", e.getState().disconnectedPlayerId)
    }

    @Test fun `reconnecting resumes play`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(), listOf()), seatCount = 3)
        e.beginDisconnectCountdown("p1")
        e.reconnectPlayer("p1")
        assertEquals(GamePhase.PLAYING, e.getState().phase)
    }

    @Test fun `reconnecting clears the dropped player`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(), listOf()), seatCount = 3)
        e.beginDisconnectCountdown("p1")
        e.reconnectPlayer("p1")
        assertNull(e.getState().disconnectedPlayerId)
    }

    @Test fun `the countdown ticks down`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(), listOf()), seatCount = 3)
        e.beginDisconnectCountdown("p1")
        val before = e.getState().disconnectCountdown
        e.tickDisconnectCountdown()
        assertTrue(e.getState().disconnectCountdown < before)
    }

    @Test fun `a player who leaves stops being connected`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(), listOf()), seatCount = 3)
        e.playerLeft("p1")
        assertFalse(e.getState().getPlayerById("p1")?.isConnected == true)
    }

    @Test fun `a player leaving conserves the deck`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(), listOf()), seatCount = 3)
        val before = P.allCards(e.getState()).size
        e.playerLeft("p1")
        assertEquals(before, P.allCards(e.getState()).size)
    }

    @Test fun `the turn never rests on a player who left`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(), listOf()),
            turn = 0, seatCount = 3
        )
        e.playerLeft("p1")
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        assertNotEquals("p1", P.turnId(e))
    }

    // ── The clock ───────────────────────────────────────────────────────────

    @Test fun `a timeout with the clock off is still refused for the wrong player`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf()),
            settings = P.settings(clock = null), seatCount = 2
        )
        val before = e.getState().sequenceNumber
        e.applyTurnTimeout("p1")
        assertEquals(before, e.getState().sequenceNumber)
    }

    @Test fun `a timeout never ends the round by itself`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf()),
            settings = P.settings(clock = 10), seatCount = 2
        )
        e.applyTurnTimeout("p0")
        assertNotEquals(GamePhase.ROUND_OVER, e.getState().phase)
    }

    @Test fun `a draw-one timeout leaves the deck conserved`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf()),
            settings = P.settings(clock = 10, onTimeout = TimeoutAction.DRAW_ONE), seatCount = 2
        )
        val before = P.allCards(e.getState()).size
        e.applyTurnTimeout("p0")
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    // ── No Mercy's knockout ─────────────────────────────────────────────────

    @Test fun `a player over the mercy threshold is knocked out`() {
        val big = (0 until 30).map { P.card(100 + it, Col.BLUE, T.NINE) }
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.DRAW_TWO), P.card(90, Col.BLUE, T.EIGHT)), big, listOf()),
            settings = P.settings(mode = GameMode.NO_MERCY, mercyThreshold = 25), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        assertFalse(
            "30 cards is past the line",
            e.getState().getPlayerById("p1")?.isConnected == true
        )
    }

    @Test fun `a player under the threshold stays in`() {
        val small = (0 until 5).map { P.card(200 + it, Col.BLUE, T.NINE) }
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.DRAW_TWO), P.card(90, Col.BLUE, T.EIGHT)), small, listOf()),
            settings = P.settings(mode = GameMode.NO_MERCY, mercyThreshold = 25), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        assertTrue(e.getState().getPlayerById("p1")?.isConnected == true)
    }

    @Test fun `Classic never knocks anybody out for holding cards`() {
        val big = (0 until 40).map { P.card(300 + it, Col.BLUE, T.NINE) }
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.DRAW_TWO), P.card(90, Col.BLUE, T.EIGHT)), big, listOf()),
            settings = P.settings(mode = GameMode.CLASSIC), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        assertTrue(e.getState().getPlayerById("p1")?.isConnected == true)
    }
}
