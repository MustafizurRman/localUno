package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.Direction
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a card still does on the turn it wins the round.
 *
 * The round ends the moment the last card lands, which used to mean the card's effect was simply
 * discarded - playCard returned at ROUND_OVER before applyCardEffect ran. Winning on a +4 cost the
 * next player nothing, and because scoring sums what everyone is left holding, closing a round on a
 * +4 was worth strictly less than closing it on a plain 9. That is backwards.
 *
 * The draw lands; nothing else does. Skip and Reverse are meaningless once the round is over, and
 * the hand-moving rules must be suppressed - 0 and 7 are number cards, so they can legally BE the
 * winning card even under "last card must be a number", and rotating or swapping hands afterwards
 * would deal the winner a new hand and undo the win.
 */
class WinningCardEffectTest {

    private fun rules(lastCardMustBeNumber: Boolean = false, stacking: Boolean = true) =
        GameSettings(
            gameMode = GameMode.NO_MERCY,
            maxPlayers = 3,
            rules = GameRules(
                stacking = stacking,
                lastCardMustBeNumber = lastCardMustBeNumber,
                zeroRotates = true,
                sevenSwaps = true
            )
        )

    /** A table where p0 holds exactly [winningCard] and it is their turn. */
    private fun onePlayCard(
        winningCard: Card,
        settings: GameSettings,
        pendingDrawCount: Int = 0,
        direction: Direction = Direction.CLOCKWISE
    ): Pair<GameEngine, GameState> {
        val engine = GameEngine(settings)
        val base = engine.initializeGame(
            (0 until 3).map { Player(id = "p$it", name = "P$it", avatarColor = it) }
        )
        val seeded = base.copy(
            phase = GamePhase.PLAYING,
            currentPlayerIndex = 0,
            direction = direction,
            currentColor = CardColor.RED,
            pendingDrawCount = pendingDrawCount,
            discardPile = listOf(Card(800, CardColor.RED, CardType.THREE)),
            players = base.players.mapIndexed { i, p ->
                when (i) {
                    0 -> p.copy(hand = mutableListOf(winningCard))
                    else -> p.copy(hand = mutableListOf(Card(810 + i, CardColor.BLUE, CardType.FIVE)))
                }
            }
        )
        engine.setStateForTest(seeded)
        return engine to seeded
    }

    private fun handSize(engine: GameEngine, id: String) =
        engine.getState().getPlayerById(id)!!.hand.size

    @Test
    fun `winning on a draw two still makes the next player draw`() {
        val plusTwo = Card(900, CardColor.RED, CardType.DRAW_TWO)
        val (engine, _) = onePlayCard(plusTwo, rules())
        engine.playCard("p0", plusTwo)

        assertEquals("the round should be over", GamePhase.ROUND_OVER, engine.getState().phase)
        assertEquals("p0 should have won", "p0", engine.getState().winnerId)
        assertEquals("the next player takes the two", 3, handSize(engine, "p1"))
        assertEquals("and nobody else is touched", 1, handSize(engine, "p2"))
    }

    @Test
    fun `winning on a draw four makes the next player draw four`() {
        val plusFour = Card(900, CardColor.RED, CardType.DRAW_FOUR)
        val (engine, _) = onePlayCard(plusFour, rules())
        engine.playCard("p0", plusFour)
        assertEquals(5, handSize(engine, "p1"))
    }

    @Test
    fun `winning on a stacked draw hands over the whole tower`() {
        // p0 answers a live +4 with their own +4 and goes out. Nobody is left to pass it on, so
        // the next player owes all eight.
        val plusFour = Card(900, CardColor.RED, CardType.DRAW_FOUR)
        val (engine, _) = onePlayCard(plusFour, rules(stacking = true), pendingDrawCount = 4)
        engine.playCard("p0", plusFour)
        assertEquals("4 pending plus 4 played", 9, handSize(engine, "p1"))
        assertEquals("and the stack is cleared out", 0, engine.getState().pendingDrawCount)
    }

    @Test
    fun `a reverse draw four points the other way round the table`() {
        // The direction flips before the card points at anyone, so the victim is the player the
        // CURRENT direction would have skipped.
        val revFour = Card(900, CardColor.WILD, CardType.WILD_REVERSE_DRAW_FOUR)
        val (engine, _) = onePlayCard(revFour, rules(), direction = Direction.CLOCKWISE)
        engine.playCard("p0", revFour, CardColor.RED)

        assertEquals("p2 is next once the direction reverses", 5, handSize(engine, "p2"))
        assertEquals("p1 must be untouched", 1, handSize(engine, "p1"))
    }

    @Test
    fun `winning on a plain number costs nobody anything`() {
        val nine = Card(900, CardColor.RED, CardType.NINE)
        val (engine, _) = onePlayCard(nine, rules())
        engine.playCard("p0", nine)
        assertEquals(GamePhase.ROUND_OVER, engine.getState().phase)
        assertEquals(1, handSize(engine, "p1"))
        assertEquals(1, handSize(engine, "p2"))
    }

    @Test
    fun `winning on a skip changes nothing`() {
        val skip = Card(900, CardColor.RED, CardType.SKIP)
        val (engine, _) = onePlayCard(skip, rules())
        engine.playCard("p0", skip)
        assertEquals(1, handSize(engine, "p1"))
        assertEquals(1, handSize(engine, "p2"))
    }

    @Test
    fun `winning on a seven does NOT swap hands`() {
        // A 7 is a number, so it can be the winning card even under "last card must be a number".
        // Swapping here would hand the winner a full hand back and undo the win.
        val seven = Card(900, CardColor.RED, CardType.SEVEN)
        val (engine, _) = onePlayCard(seven, rules(lastCardMustBeNumber = true))
        engine.playCard("p0", seven)

        assertEquals("the winner must stay empty-handed", 0, handSize(engine, "p0"))
        assertEquals("the round must actually be over", GamePhase.ROUND_OVER, engine.getState().phase)
        assertEquals("p0", engine.getState().winnerId)
        assertTrue(
            "no swap should have been requested",
            engine.getState().pendingSwapPlayerId == null
        )
    }

    @Test
    fun `winning on a zero does NOT rotate hands`() {
        val zero = Card(900, CardColor.RED, CardType.ZERO)
        val (engine, _) = onePlayCard(zero, rules(lastCardMustBeNumber = true))
        engine.playCard("p0", zero)

        assertEquals("the winner must stay empty-handed", 0, handSize(engine, "p0"))
        assertEquals(GamePhase.ROUND_OVER, engine.getState().phase)
        assertEquals("p0", engine.getState().winnerId)
        assertEquals("nobody's hand should have moved", 1, handSize(engine, "p1"))
        assertEquals(1, handSize(engine, "p2"))
    }

    @Test
    fun `the penalty is worth points to the winner`() {
        // The whole reason this matters: scores sum what everyone still holds.
        val plusFour = Card(900, CardColor.RED, CardType.DRAW_FOUR)
        val (engine, _) = onePlayCard(plusFour, rules())
        engine.playCard("p0", plusFour)

        val scores = engine.calculateScores()
        val winnerScore = scores["p0"] ?: 0
        assertTrue(
            "the winner's score should reflect the four cards they forced (got $scores)",
            winnerScore > 0
        )
    }

    @Test
    fun `the deck is still conserved when a round ends on a draw card`() {
        // The penalty draw happens after the win check, which is the kind of late edit that has
        // duplicated cards here before.
        val plusFour = Card(900, CardColor.RED, CardType.DRAW_FOUR)
        val (engine, seeded) = onePlayCard(plusFour, rules())
        val before = (seeded.players.flatMap { it.hand } + seeded.drawPile + seeded.discardPile)
        engine.playCard("p0", plusFour)
        val after = engine.getState().let { it.players.flatMap { p -> p.hand } + it.drawPile + it.discardPile }

        assertEquals("deck changed size", before.size, after.size)
        val dupes = after.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("duplicate card ids after a winning draw: ${dupes.keys}", dupes.isEmpty())
    }
}
