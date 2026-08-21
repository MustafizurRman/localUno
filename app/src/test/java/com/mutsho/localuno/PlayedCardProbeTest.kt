package com.mutsho.localuno

import com.mutsho.localuno.model.CardColor as Col
import com.mutsho.localuno.model.CardType as T
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.Player
import com.mutsho.localuno.model.detectPlayedCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Who played the card that just landed.
 *
 * The flight animation exists to answer that question without anybody reading the log, so getting
 * the attribution wrong is worse than not animating at all - a card confidently flying from the
 * wrong seat teaches you something false about a game you are trying to follow.
 *
 * The rule is that the player whose turn it WAS is the one who played it, which holds for every
 * path that puts a card on the pile because none of them can run on somebody else's turn. Most of
 * these tests are about the cases where nobody played anything and the answer must be silence.
 */
class PlayedCardProbeTest {

    private val P = Probe

    private fun state(
        top: com.mutsho.localuno.model.Card?,
        turn: Int,
        round: Long = 1000L,
        seats: Int = 3
    ): GameState {
        val players = (0 until seats).map { Player(id = "p$it", name = "P$it", hand = mutableListOf()) }
        return GameState(
            players = players,
            currentPlayerIndex = turn,
            discardPile = if (top != null) mutableListOf(top) else mutableListOf(),
            phase = GamePhase.PLAYING,
            roundStartTime = round
        )
    }

    private val red3 = P.card(1, Col.RED, T.THREE)
    private val blue5 = P.card(2, Col.BLUE, T.FIVE)

    // ── Attribution ─────────────────────────────────────────────────────────

    @Test fun `the player whose turn it was is credited`() {
        val before = state(red3, turn = 1)
        val after = state(blue5, turn = 2)
        assertEquals("p1", detectPlayedCard(before, after)?.byPlayerId)
    }

    @Test fun `the card that landed is the one reported`() {
        val before = state(red3, turn = 0)
        val after = state(blue5, turn = 1)
        assertEquals(blue5.id, detectPlayedCard(before, after)?.card?.id)
    }

    @Test fun `a play that keeps the turn is still credited to that player`() {
        // Drawing a playable card and playing it keeps the turn where it was.
        val before = state(red3, turn = 2)
        val after = state(blue5, turn = 2)
        assertEquals("p2", detectPlayedCard(before, after)?.byPlayerId)
    }

    @Test fun `a reverse that sends play backwards still credits the actor`() {
        // The turn moves the other way afterwards; who ACTED is unaffected by where it goes next.
        val before = state(red3, turn = 1)
        val after = state(blue5, turn = 0)
        assertEquals("p1", detectPlayedCard(before, after)?.byPlayerId)
    }

    // ── Silence, which is most of the work ──────────────────────────────────

    @Test fun `an unchanged pile reports nothing`() {
        val s = state(red3, turn = 1)
        assertNull(detectPlayedCard(s, s))
    }

    @Test fun `a turn passing with no play reports nothing`() {
        // A timeout skip moves the turn and touches nothing on the pile.
        assertNull(detectPlayedCard(state(red3, turn = 1), state(red3, turn = 2)))
    }

    @Test fun `a draw reports nothing`() {
        // Drawing changes hands, never the pile.
        assertNull(detectPlayedCard(state(red3, turn = 0), state(red3, turn = 0)))
    }

    @Test fun `a new round's opening card is not attributed to anyone`() {
        // The deal flips a starting card. Flying it in from whichever seat happened to be current
        // would be a small lie told at the start of every single round.
        val before = state(red3, turn = 1, round = 1000L)
        val after = state(blue5, turn = 0, round = 2000L)
        assertNull(detectPlayedCard(before, after))
    }

    @Test fun `a client's very first update is not attributed`() {
        // No prior board to compare against - the roster is still empty.
        val empty = GameState(players = emptyList(), phase = GamePhase.PLAYING)
        val first = state(red3, turn = 0)
        assertNull(detectPlayedCard(empty, first))
    }

    @Test fun `an empty pile reports nothing`() {
        assertNull(detectPlayedCard(state(red3, turn = 0), state(null, turn = 1)))
    }

    @Test fun `a state with no current player reports nothing rather than guessing`() {
        val before = state(red3, turn = 0, seats = 0)
        val after = state(blue5, turn = 0, seats = 0)
        assertNull(detectPlayedCard(before, after))
    }

    // ── Shape the overlay relies on ─────────────────────────────────────────

    @Test fun `the reported player is always a real seat`() {
        // The overlay looks the id up in the anchor map; an id nobody holds means no flight at all,
        // which would look like the feature silently not working.
        val before = state(red3, turn = 2)
        val after = state(blue5, turn = 0)
        val played = detectPlayedCard(before, after)
        assertNotNull(played)
        assertEquals(true, before.players.any { it.id == played!!.byPlayerId })
    }
}
