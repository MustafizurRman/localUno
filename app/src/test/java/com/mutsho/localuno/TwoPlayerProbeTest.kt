package com.mutsho.localuno

import com.mutsho.localuno.model.CardColor as Col
import com.mutsho.localuno.model.CardType as T
import com.mutsho.localuno.model.Direction
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A two-player table is a different game, and almost every rule that moves the turn has a special
 * case for it.
 *
 * Round a table of four, "skip the next player" and "reverse direction" are distinct instructions
 * with distinct effects. Head to head they collapse onto each other - both mean "I go again" - and
 * anything that forgets this hands the turn straight back to the opponent, which is the opposite of
 * what the card says. The same collapse hits every card that names a direction or a neighbour: a 7's
 * swap has exactly one possible target, a 0's rotation IS a swap, and a draw stack has nowhere to
 * travel but back and forth.
 *
 * Written because two-player play was reported as inconsistent without a specific symptom, so this
 * asks the engine about every turn-moving rule at a head-to-head table rather than guessing which
 * one was meant.
 */
class TwoPlayerProbeTest {

    private val P = Probe

    private fun duel(
        p0: List<com.mutsho.localuno.model.Card>,
        p1: List<com.mutsho.localuno.model.Card>,
        mode: GameMode = GameMode.CLASSIC,
        stacking: Boolean = false,
        sevenSwaps: Boolean = false,
        zeroRotates: Boolean = false,
        colour: Col = Col.RED,
        top: com.mutsho.localuno.model.Card = P.card(500, Col.RED, T.THREE),
        turn: Int = 0,
        pendingDraw: Int = 0
    ) = P.table(
        listOf(p0, p1),
        settings = P.settings(
            mode = mode, stacking = stacking, sevenSwaps = sevenSwaps,
            zeroRotates = zeroRotates, seats = 2
        ),
        top = top, colour = colour, turn = turn, pendingDraw = pendingDraw, seatCount = 2
    )

    private fun turnOf(e: com.mutsho.localuno.engine.GameEngine) = e.getState().currentPlayer?.id

    // ── The turn-moving cards ───────────────────────────────────────────────

    @Test fun `a reverse means you go again`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.REVERSE), P.card(2, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.TWO))
        )
        e.playCard("p0", P.card(1, Col.RED, T.REVERSE))
        assertEquals("head to head a reverse is a skip", "p0", turnOf(e))
    }

    @Test fun `a skip means you go again`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.SKIP), P.card(2, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.TWO))
        )
        e.playCard("p0", P.card(1, Col.RED, T.SKIP))
        assertEquals("p0", turnOf(e))
    }

    @Test fun `an ordinary number passes the turn`() {
        // The control. If this failed, the two above would prove nothing.
        val e = duel(
            listOf(P.card(1, Col.RED, T.NINE), P.card(2, Col.RED, T.ONE)),
            listOf(P.card(3, Col.BLUE, T.TWO))
        )
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        assertEquals("p1", turnOf(e))
    }

    @Test fun `two reverses in a row still leave it with you`() {
        val e = duel(
            listOf(
                P.card(1, Col.RED, T.REVERSE),
                P.card(2, Col.RED, T.REVERSE),
                P.card(3, Col.RED, T.NINE)
            ),
            listOf(P.card(4, Col.BLUE, T.TWO))
        )
        e.playCard("p0", P.card(1, Col.RED, T.REVERSE))
        e.playCard("p0", P.card(2, Col.RED, T.REVERSE))
        assertEquals("p0", turnOf(e))
    }

    @Test fun `skip everyone leaves it with you`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.SKIP_EVERYONE), P.card(2, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.TWO)),
            mode = GameMode.NO_MERCY
        )
        e.playCard("p0", P.card(1, Col.RED, T.SKIP_EVERYONE))
        assertEquals("p0", turnOf(e))
    }

    @Test fun `a wild passes the turn`() {
        val e = duel(
            listOf(P.card(1, Col.WILD, T.WILD), P.card(2, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.TWO))
        )
        e.playCard("p0", P.card(1, Col.WILD, T.WILD), Col.GREEN)
        assertEquals("p1", turnOf(e))
        assertEquals(Col.GREEN, e.getState().currentColor)
    }

    // ── Draw cards ──────────────────────────────────────────────────────────

    @Test fun `a draw two hands the penalty to the only opponent and skips them`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.DRAW_TWO), P.card(2, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.TWO))
        )
        val before = P.count(e, "p1")
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        // With stacking off the penalty is taken immediately and the turn comes back.
        assertTrue("the opponent has to take the two", P.count(e, "p1") > before)
        assertEquals("and loses their turn for it", "p0", turnOf(e))
    }

    @Test fun `a stacked draw bounces back to the player who started it`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.DRAW_TWO), P.card(2, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.DRAW_TWO), P.card(4, Col.BLUE, T.ONE)),
            stacking = true
        )
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        assertEquals("the answer is the opponent's to give", "p1", turnOf(e))
        e.playCard("p1", P.card(3, Col.BLUE, T.DRAW_TWO))
        assertEquals("and it comes straight back", "p0", turnOf(e))
        assertEquals("now worth four", 4, e.getState().pendingDrawCount)
    }

    @Test fun `taking a stack ends the turn head to head`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.ONE)),
            stacking = true, pendingDraw = 4, turn = 0
        )
        val before = P.count(e, "p0")
        e.drawCardForPlayer("p0")
        assertEquals("all four are taken", before + 4, P.count(e, "p0"))
        assertEquals(0, e.getState().pendingDrawCount)
        assertEquals("p1", turnOf(e))
    }

    @Test fun `a reverse draw four head to head does not skip the wrong seat`() {
        val e = duel(
            listOf(P.card(1, Col.WILD, T.WILD_REVERSE_DRAW_FOUR), P.card(2, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.TWO)),
            mode = GameMode.NO_MERCY
        )
        val p1Before = P.count(e, "p1")
        e.playCard("p0", P.card(1, Col.WILD, T.WILD_REVERSE_DRAW_FOUR), Col.GREEN)

        // The engine used to do the exact opposite of all three of these: p0 drew the four
        // themselves, p1 took nothing, and the turn went to p1. Playing the card cost you four
        // cards and your turn and gave your opponent a free move.
        assertEquals("the opponent takes the four", p1Before + 4, P.count(e, "p1"))
        assertEquals("the player who played it takes nothing", 1, P.count(e, "p0"))
        assertEquals("and it comes back to them", "p0", turnOf(e))
    }

    @Test fun `a reverse draw four can be stacked head to head`() {
        // The old two-player branch was checked BEFORE the stacking branch, so head to head this
        // card could never be answered even with stacking on - while a Draw Two could. Two draw
        // cards behaving differently under the same rule is the inconsistency, whichever way it
        // is resolved.
        val e = duel(
            listOf(P.card(1, Col.WILD, T.WILD_REVERSE_DRAW_FOUR), P.card(2, Col.RED, T.NINE)),
            listOf(P.card(3, Col.WILD, T.WILD_DRAW_SIX), P.card(4, Col.BLUE, T.TWO)),
            mode = GameMode.NO_MERCY, stacking = true
        )
        e.playCard("p0", P.card(1, Col.WILD, T.WILD_REVERSE_DRAW_FOUR), Col.GREEN)
        assertEquals("the opponent gets the chance to answer", "p1", turnOf(e))
        assertEquals("with a live penalty on the table", 4, e.getState().pendingDrawCount)
    }

    // ── Hand-moving rules, where two players collapse the rule ──────────────

    @Test fun `a seven head to head has exactly one target`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.SEVEN), P.card(2, Col.BLUE, T.ONE)),
            listOf(P.card(3, Col.BLUE, T.TWO), P.card(4, Col.BLUE, T.THREE)),
            sevenSwaps = true
        )
        e.playCard("p0", P.card(1, Col.RED, T.SEVEN))
        val s = e.getState()
        if (s.phase == GamePhase.CHOOSING_SWAP) {
            val candidates = s.players.filter { it.id != s.pendingSwapPlayerId && it.isConnected }
            assertEquals("a picker with one option is a picker that should not be shown",
                1, candidates.size)
        }
    }

    @Test fun `a seven head to head actually swaps the hands`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.SEVEN), P.card(2, Col.BLUE, T.ONE)),
            listOf(P.card(3, Col.BLUE, T.TWO), P.card(4, Col.BLUE, T.THREE)),
            sevenSwaps = true
        )
        e.playCard("p0", P.card(1, Col.RED, T.SEVEN))
        if (e.getState().phase == GamePhase.CHOOSING_SWAP) e.chooseSwapTarget("p0", "p1")
        assertEquals("p0 should be holding what p1 had", 2, P.count(e, "p0"))
        assertEquals(1, P.count(e, "p1"))
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `a zero head to head swaps rather than losing a hand`() {
        // A rotation among two players is a straight swap. The failure worth catching is a rotation
        // that drops one hand on the floor - conservation is the whole claim.
        val e = duel(
            listOf(P.card(1, Col.RED, T.ZERO), P.card(2, Col.BLUE, T.ONE)),
            listOf(P.card(3, Col.BLUE, T.TWO), P.card(4, Col.BLUE, T.THREE)),
            zeroRotates = true
        )
        val before = P.allCards(e.getState()).size
        e.playCard("p0", P.card(1, Col.RED, T.ZERO))
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
        assertEquals("p0 takes p1's two", 2, P.count(e, "p0"))
        assertEquals("p1 takes p0's one", 1, P.count(e, "p1"))
    }

    // ── Ending a two-player round ───────────────────────────────────────────

    @Test fun `a knockout head to head ends the round`() {
        // No Mercy knocks a player out at the mercy line. With two players that leaves one, and a
        // table of one cannot keep playing - it has to be over.
        val e = duel(
            listOf(P.card(1, Col.RED, T.DRAW_FOUR), P.card(2, Col.RED, T.NINE)),
            (0 until 24).map { P.card(100 + it, Col.BLUE, T.ONE) },
            mode = GameMode.NO_MERCY
        )
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_FOUR))
        val s = e.getState()
        val alive = s.players.count { it.isConnected }
        if (alive <= 1) {
            assertTrue(
                "one player left and the round still running is a table that cannot finish",
                s.phase == GamePhase.ROUND_OVER || s.phase == GamePhase.GAME_OVER
            )
        }
    }

    @Test fun `the opponent leaving ends the round rather than passing the turn to nobody`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.TWO))
        )
        e.playerLeft("p1")
        val s = e.getState()
        assertTrue(
            "with one player left the round must not still be PLAYING",
            s.phase != GamePhase.PLAYING
        )
    }

    @Test fun `winning head to head names the winner`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.TWO))
        )
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        assertEquals("p0", e.getState().winnerId)
        assertEquals(GamePhase.ROUND_OVER, e.getState().phase)
    }

    // ── Saying UNO with one opponent ────────────────────────────────────────

    @Test fun `the opponent can catch a missed UNO`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.TWO))
        )
        val before = P.count(e, "p0")
        e.penalizeUnoNotCalled("p1", "p0")
        assertTrue(P.count(e, "p0") > before)
    }

    @Test fun `you cannot catch yourself`() {
        val e = duel(
            listOf(P.card(1, Col.RED, T.NINE)),
            listOf(P.card(3, Col.BLUE, T.TWO))
        )
        val before = P.count(e, "p0")
        e.penalizeUnoNotCalled("p0", "p0")
        assertEquals("catching yourself is not a move", before, P.count(e, "p0"))
    }

    // ── The turn never leaves the table ─────────────────────────────────────

    @Test fun `no sequence of plays ever leaves the turn on a departed player`() {
        // The general form of every bug above: whatever happens, the seat holding the turn has to
        // be one that can actually act.
        val e = duel(
            listOf(
                P.card(1, Col.RED, T.REVERSE), P.card(2, Col.RED, T.SKIP),
                P.card(3, Col.RED, T.DRAW_TWO), P.card(4, Col.RED, T.NINE)
            ),
            listOf(P.card(5, Col.BLUE, T.ONE), P.card(6, Col.BLUE, T.TWO)),
            stacking = true
        )
        listOf(
            P.card(1, Col.RED, T.REVERSE),
            P.card(2, Col.RED, T.SKIP),
            P.card(3, Col.RED, T.DRAW_TWO)
        ).forEach { card ->
            if (e.getState().phase != GamePhase.PLAYING) return@forEach
            val actor = e.getState().currentPlayer?.id ?: return@forEach
            if (actor == "p0") e.playCard("p0", card)
            val s = e.getState()
            if (s.phase == GamePhase.PLAYING) {
                assertTrue(
                    "the turn is on a seat that cannot act",
                    s.currentPlayer?.isConnected == true
                )
            }
        }
    }

    @Test fun `direction is irrelevant head to head`() {
        // Both directions must produce the same next player, or the board's arrow is telling the
        // players something the engine does not believe.
        val cw = duel(
            listOf(P.card(1, Col.RED, T.NINE), P.card(2, Col.RED, T.ONE)),
            listOf(P.card(3, Col.BLUE, T.TWO))
        )
        cw.playCard("p0", P.card(1, Col.RED, T.NINE))
        val ccw = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.NINE), P.card(2, Col.RED, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(seats = 2),
            direction = Direction.COUNTER_CLOCKWISE, seatCount = 2
        )
        ccw.playCard("p0", P.card(1, Col.RED, T.NINE))
        assertEquals(turnOf(cw), turnOf(ccw))
    }
}
