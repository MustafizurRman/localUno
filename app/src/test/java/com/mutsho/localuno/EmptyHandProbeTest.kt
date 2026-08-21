package com.mutsho.localuno

import com.mutsho.localuno.model.CardColor as Col
import com.mutsho.localuno.model.CardType as T
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nobody may be left holding nothing while the round carries on.
 *
 * Written to chase a board that looked stuck - the table sat on "Circuit's turn" with the local
 * hand showing "hand empty" and no winner declared. The suspicion was an ordering bug: playCard
 * checks for a win on the hand left after the PLAYED card is removed and only then runs
 * applyCardEffect, so an effect that takes further cards out of the player's OWN hand could empty
 * it after the only win check had already passed. Discard All is exactly that shape, and it is a
 * No Mercy card, which fitted the symptom.
 *
 * The engine turned out to be right and the suspicion wrong: every case below passed first time.
 * The stuck board was a bisect build with the winner overlay compiled out, so a round that HAD
 * ended simply had nothing on screen to say so.
 *
 * Kept because the ordering it guards is genuinely delicate - the win check and the card effect run
 * in that order, and any future effect that removes cards from the actor's hand would reopen
 * exactly this hole without touching a line of the win check itself.
 */
class EmptyHandProbeTest {

    private val P = Probe

    private fun mercy(sevenSwaps: Boolean = false, zeroRotates: Boolean = false) =
        P.settings(mode = GameMode.NO_MERCY, sevenSwaps = sevenSwaps, zeroRotates = zeroRotates)

    @Test fun `discarding your whole hand by colour ends the round`() {
        // Discard All plus two more reds and nothing else: the effect empties the hand.
        val e = P.table(
            listOf(
                listOf(
                    P.card(1, Col.RED, T.DISCARD_ALL),
                    P.card(2, Col.RED, T.NINE),
                    P.card(3, Col.RED, T.ONE)
                ),
                listOf(P.card(4, Col.BLUE, T.TWO)),
                listOf(P.card(5, Col.BLUE, T.THREE))
            ),
            settings = mercy(), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DISCARD_ALL))

        assertEquals("the hand really is empty", 0, P.count(e, "p0"))
        assertEquals("so the round has to be over", GamePhase.ROUND_OVER, e.getState().phase)
    }

    @Test fun `discarding your whole hand by colour names a winner`() {
        val e = P.table(
            listOf(
                listOf(
                    P.card(1, Col.RED, T.DISCARD_ALL),
                    P.card(2, Col.RED, T.NINE)
                ),
                listOf(P.card(4, Col.BLUE, T.TWO)),
                listOf(P.card(5, Col.BLUE, T.THREE))
            ),
            settings = mercy(), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DISCARD_ALL))
        assertEquals("p0", e.getState().winnerId)
    }

    @Test fun `discard all with other colours left does not end the round`() {
        // The mirror: it must only win when the hand is genuinely gone.
        val e = P.table(
            listOf(
                listOf(
                    P.card(1, Col.RED, T.DISCARD_ALL),
                    P.card(2, Col.RED, T.NINE),
                    P.card(3, Col.BLUE, T.ONE)
                ),
                listOf(P.card(4, Col.BLUE, T.TWO)),
                listOf(P.card(5, Col.BLUE, T.THREE))
            ),
            settings = mercy(), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DISCARD_ALL))
        assertEquals(GamePhase.PLAYING, e.getState().phase)
        assertEquals(1, P.count(e, "p0"))
    }

    @Test fun `no player is ever left holding nothing while the round runs`() {
        // The general form of the same claim, which is what the board actually broke.
        val e = P.table(
            listOf(
                listOf(
                    P.card(1, Col.RED, T.DISCARD_ALL),
                    P.card(2, Col.RED, T.NINE)
                ),
                listOf(P.card(4, Col.BLUE, T.TWO)),
                listOf(P.card(5, Col.BLUE, T.THREE))
            ),
            settings = mercy(), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DISCARD_ALL))
        val s = e.getState()
        if (s.phase == GamePhase.PLAYING) {
            assertTrue(
                "a player holding nothing while play continues is a table that cannot finish",
                s.players.none { it.isConnected && it.hand.isEmpty() }
            )
        }
    }

    @Test fun `emptying by discard all still conserves the deck`() {
        val e = P.table(
            listOf(
                listOf(
                    P.card(1, Col.RED, T.DISCARD_ALL),
                    P.card(2, Col.RED, T.NINE)
                ),
                listOf(P.card(4, Col.BLUE, T.TWO)),
                listOf(P.card(5, Col.BLUE, T.THREE))
            ),
            settings = mercy(), seatCount = 3
        )
        val before = P.allCards(e.getState()).size
        e.playCard("p0", P.card(1, Col.RED, T.DISCARD_ALL))
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }
}
