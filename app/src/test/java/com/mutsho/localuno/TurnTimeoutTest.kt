package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.Player
import com.mutsho.localuno.model.TimeoutAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What happens when the turn clock runs out.
 *
 * Previously: nothing, ever. The timeout handler called `drawCardForPlayer`, which declined to draw
 * for anybody holding a playable card - so for the commonest case there is, a player with a decent
 * hand who put their phone down, the call returned the state untouched and the turn never moved.
 * The clock sat at zero and the only way out of the round was to leave it.
 *
 * Nothing in the suite noticed, because every existing timeout test drove a hand with nothing
 * playable in it - the one case where the old path happened to work.
 */
class TurnTimeoutTest {

    private fun seats(n: Int) = (0 until n).map {
        Player(id = "p$it", name = "P$it", avatarColor = it)
    }

    /** A table where p0 is on the clock holding [hand]. */
    private fun table(
        hand: List<Card>,
        action: TimeoutAction = TimeoutAction.SKIP,
        mode: GameMode = GameMode.CLASSIC,
        pendingDraw: Int = 0
    ): GameEngine {
        val engine = GameEngine(
            GameSettings(
                gameMode = mode,
                turnTimeoutSeconds = 15,
                turnTimeoutAction = action
            )
        )
        engine.initializeGame(seats(3))
        val players = engine.getState().players.toMutableList()
        players[0] = players[0].copy(hand = hand.toMutableList())
        engine.setStateForTest(
            engine.getState().copy(
                players = players,
                phase = GamePhase.PLAYING,
                currentPlayerIndex = 0,
                currentColor = CardColor.RED,
                pendingDrawCount = pendingDraw,
                discardPile = listOf(Card(900, CardColor.RED, CardType.THREE))
            )
        )
        return engine
    }

    private fun playableHand() = listOf(
        Card(9001, CardColor.RED, CardType.FIVE),   // playable on red
        Card(9002, CardColor.BLUE, CardType.NINE)
    )

    @Test
    fun `the turn advances even when the player is holding a playable card`() {
        // The exact case the old code could not handle, and the reason the board froze.
        val engine = table(playableHand())
        engine.applyTurnTimeout("p0")

        assertNotEquals(
            "the turn must move on - this is what used to do nothing at all",
            0, engine.getState().currentPlayerIndex
        )
    }

    @Test
    fun `skip is the default and costs no cards`() {
        val engine = table(playableHand())
        assertEquals(2, engine.getState().players[0].cardCount)
        engine.applyTurnTimeout("p0")

        assertEquals("skip must not deal a penalty", 2, engine.getState().players[0].cardCount)
        assertEquals(1, engine.getState().currentPlayerIndex)
    }

    @Test
    fun `draw one costs exactly one card and still moves on`() {
        val engine = table(playableHand(), action = TimeoutAction.DRAW_ONE)
        engine.applyTurnTimeout("p0")

        assertEquals("one card, not a hand", 3, engine.getState().players[0].cardCount)
        assertEquals(1, engine.getState().currentPlayerIndex)
    }

    @Test
    fun `a player with nothing playable is also moved on`() {
        val engine = table(listOf(Card(9003, CardColor.BLUE, CardType.NINE)))
        engine.applyTurnTimeout("p0")
        assertNotEquals(0, engine.getState().currentPlayerIndex)
    }

    @Test
    fun `an owed draw stack is taken whatever the setting says`() {
        // A stack is a debt rather than a penalty, so SKIP must not wipe it.
        val engine = table(playableHand(), action = TimeoutAction.SKIP, pendingDraw = 4)
        engine.applyTurnTimeout("p0")

        assertEquals("the four owed cards land", 6, engine.getState().players[0].cardCount)
        assertEquals("and the stack is cleared", 0, engine.getState().pendingDrawCount)
        assertNotEquals(0, engine.getState().currentPlayerIndex)
    }

    @Test
    fun `a timeout on somebody else's turn does nothing`() {
        // The clock is restarted on every turn, so a late firing from a turn that already ended
        // must not steal the turn from whoever holds it now.
        val engine = table(playableHand())
        val before = engine.getState()
        engine.applyTurnTimeout("p1")
        assertEquals(before.sequenceNumber, engine.getState().sequenceNumber)
        assertEquals(0, engine.getState().currentPlayerIndex)
    }

    @Test
    fun `the deck is conserved across a timeout`() {
        val engine = table(playableHand(), action = TimeoutAction.DRAW_ONE)
        val before = engine.getState().let {
            it.players.flatMap { p -> p.hand } + it.drawPile + it.discardPile
        }
        engine.applyTurnTimeout("p0")
        val after = engine.getState().let {
            it.players.flatMap { p -> p.hand } + it.drawPile + it.discardPile
        }

        assertEquals("deck changed size", before.size, after.size)
        val dupes = after.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertEquals("duplicate cards after a timeout: ${dupes.keys}", 0, dupes.size)
    }
}
