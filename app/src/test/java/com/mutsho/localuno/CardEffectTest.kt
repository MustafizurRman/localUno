package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card effects driven through the engine's public surface with hand-built states, covering the
 * paths the whole-round bot simulations in BotBrainTest can reach but can't make assertions about
 * precisely (they play whatever the deck deals).
 */
class CardEffectTest {

    private fun seats(n: Int) = (0 until n).map { i -> Player(id = "p$i", name = "P$i") }

    /**
     * Both roulette tests stack the draw pile explicitly rather than trusting the shuffle. An
     * earlier version asserted on draw COUNTS from a random pile and was flaky for a real reason:
     * in No Mercy a roulette that runs long can push the victim past the Mercy threshold, at which
     * point checkMercyRule clears their hand and the assertion is measuring a knocked-out seat.
     */
    private fun engineWithPile(pile: List<Card>, currentColor: CardColor): GameEngine {
        val engine = GameEngine(GameSettings(gameMode = GameMode.NO_MERCY, turnTimeoutSeconds = null))
        engine.initializeGame(seats(3))
        engine.setStateForTest(
            engine.getState().copy(drawPile = pile, currentColor = currentColor)
        )
        return engine
    }

    private fun card(id: Int, color: CardColor) = Card(id = id, color = color, type = CardType.FIVE)

    /**
     * A client controls chosenColor, and the host used to hand it to applyCardEffect unsanitized -
     * only currentColor was being checked. CardColor.WILD as the roulette's target makes
     * drawUntilColor's stop condition unsatisfiable (a card whose colour is WILD *is* a wild card),
     * so it ran to its 40-card safety limit: an instant Mercy knockout from one message.
     */
    @Test
    fun `colour roulette never draws the whole pile when WILD is sent as the colour`() {
        // Red is in force. A sanitized roulette must fall back to it and stop on the first red.
        val pile = listOf(
            card(1, CardColor.GREEN), card(2, CardColor.YELLOW), card(3, CardColor.RED),
            card(4, CardColor.BLUE), card(5, CardColor.GREEN), card(6, CardColor.BLUE)
        )
        val engine = engineWithPile(pile, CardColor.RED)

        val roulette = Card(id = 9001, color = CardColor.WILD, type = CardType.WILD_COLOR_ROULETTE)
        val players = engine.getState().players.toMutableList()
        players[0] = players[0].copy(hand = players[0].hand + roulette)
        engine.setStateForTest(engine.getState().copy(players = players))

        val before = engine.getState().players[1].cardCount
        engine.playCard("p0", roulette, CardColor.WILD)
        val drawn = engine.getState().players[1].cardCount - before

        // green, yellow, red = 3. With the bug this consumed the entire pile instead.
        assertEquals("should stop on the first red, not exhaust the pile", 3, drawn)
        assertTrue(engine.getState().currentColor != CardColor.WILD)
    }

    /** The ordinary case: draws until the chosen colour turns up, then stops. */
    @Test
    fun `colour roulette stops on the first card of the chosen colour`() {
        val pile = listOf(
            card(1, CardColor.GREEN), card(2, CardColor.BLUE), card(3, CardColor.RED),
            card(4, CardColor.BLUE)
        )
        val engine = engineWithPile(pile, CardColor.RED)

        val roulette = Card(id = 9002, color = CardColor.WILD, type = CardType.WILD_COLOR_ROULETTE)
        val players = engine.getState().players.toMutableList()
        players[0] = players[0].copy(hand = players[0].hand + roulette)
        engine.setStateForTest(engine.getState().copy(players = players))

        val handBefore = engine.getState().players[1].hand
        engine.playCard("p0", roulette, CardColor.BLUE)
        val drawn = engine.getState().players[1].hand.drop(handBefore.size)

        assertEquals("green then blue - stops on the blue", 2, drawn.size)
        assertEquals(CardColor.BLUE, drawn.last().color)
        assertEquals(CardColor.BLUE, engine.getState().currentColor)
    }

    /** Playing a wild without naming a colour must never leave the table on WILD, which matches
     *  nothing and would soft-lock the round. */
    @Test
    fun `a wild played with no colour leaves a playable colour on the table`() {
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, turnTimeoutSeconds = null))
        engine.initializeGame(seats(3))

        val wild = Card(id = 9003, color = CardColor.WILD, type = CardType.WILD)
        val withWild = engine.getState().players.toMutableList()
        withWild[0] = withWild[0].copy(hand = withWild[0].hand + wild)
        engine.setStateForTest(engine.getState().copy(players = withWild))

        engine.playCard("p0", wild, null)
        assertTrue(engine.getState().currentColor != CardColor.WILD)
    }

    /**
     * "Last card must be a number" is enforced in canPlayCard by blocking a non-number while the
     * hand is down to one card. That guards the ordinary way a hand empties - playCard removing the
     * single card you played - but Discard All empties a hand a SECOND way: it sweeps every card
     * matching its own colour out of your hand in one move. Hold [DiscardAll(red), red5, red7] and
     * the guard never fires (hand size is 3), yet the play ends the round and you have won on a
     * power card, which is exactly what the rule promises cannot happen.
     */
    @Test
    fun `discard all cannot be used to win while last card must be a number`() {
        val engine = GameEngine(
            GameSettings(
                gameMode = GameMode.NO_MERCY,
                turnTimeoutSeconds = null,
                rules = GameRules(lastCardMustBeNumber = true)
            )
        )
        engine.initializeGame(seats(3))

        val discardAll = Card(id = 9300, color = CardColor.RED, type = CardType.DISCARD_ALL)
        val red5 = Card(id = 9301, color = CardColor.RED, type = CardType.FIVE)
        val red7 = Card(id = 9302, color = CardColor.RED, type = CardType.SEVEN)

        val players = engine.getState().players.toMutableList()
        players[0] = players[0].copy(hand = listOf(discardAll, red5, red7))
        engine.setStateForTest(
            engine.getState().copy(players = players, currentColor = CardColor.RED)
        )

        assertTrue(
            "Discard All would sweep every remaining card and win on a power card",
            !engine.canPlayCard(discardAll, "p0")
        )
    }

    /** The same card stays perfectly legal when it does NOT empty the hand - the rule locks a
     *  winning move, not the card itself. */
    @Test
    fun `discard all is still playable when a non-matching card would remain`() {
        val engine = GameEngine(
            GameSettings(
                gameMode = GameMode.NO_MERCY,
                turnTimeoutSeconds = null,
                rules = GameRules(lastCardMustBeNumber = true)
            )
        )
        engine.initializeGame(seats(3))

        val discardAll = Card(id = 9400, color = CardColor.RED, type = CardType.DISCARD_ALL)
        val red5 = Card(id = 9401, color = CardColor.RED, type = CardType.FIVE)
        val blue9 = Card(id = 9402, color = CardColor.BLUE, type = CardType.NINE)

        val players = engine.getState().players.toMutableList()
        players[0] = players[0].copy(hand = listOf(discardAll, red5, blue9))
        engine.setStateForTest(
            engine.getState().copy(players = players, currentColor = CardColor.RED)
        )

        assertTrue(
            "the blue 9 survives the sweep, so this is not a winning play and must be allowed",
            engine.canPlayCard(discardAll, "p0")
        )
    }

    /**
     * A player knocked out at the Mercy threshold keeps watching the round (GameState.isSpectating)
     * and their board still draws every opponent seat. Nothing stopped them tapping CATCH and
     * forcing two cards onto a live player - someone already eliminated could still change the
     * outcome of the round they were out of.
     */
    @Test
    fun `a knocked-out player cannot catch a missed UNO call`() {
        val engine = GameEngine(
            GameSettings(gameMode = GameMode.NO_MERCY, turnTimeoutSeconds = null)
        )
        engine.initializeGame(seats(3))

        val players = engine.getState().players.toMutableList()
        // p2 is out; p1 is sitting on one uncalled card.
        players[1] = players[1].copy(hand = listOf(card(1, CardColor.RED)), hasCalledUno = false)
        players[2] = players[2].copy(hand = emptyList(), isConnected = false)
        engine.setStateForTest(engine.getState().copy(players = players))

        val before = engine.getState().players[1].cardCount
        engine.penalizeUnoNotCalled("p2", "p1")
        assertEquals(
            "a spectator's catch must not land",
            before, engine.getState().players[1].cardCount
        )

        // A player still in the round catches exactly as before.
        engine.penalizeUnoNotCalled("p0", "p1")
        assertEquals(
            "a live player's catch should still cost the target two cards",
            before + 2, engine.getState().players[1].cardCount
        )
    }

    /**
     * Drawing is only legal when you have nothing you can play. Without that guard the Classic
     * branch of drawCardForPlayer leaked a fishing exploit: drawing a PLAYABLE card keeps the turn,
     * and nothing stopped a second draw, so a player could pull card after card on a single turn,
     * holding the turn whenever the card happened to be playable and stopping whenever they liked.
     */
    @Test
    fun `drawing is refused while a playable card is in hand`() {
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, turnTimeoutSeconds = null))
        engine.initializeGame(seats(3))

        val players = engine.getState().players.toMutableList()
        // A red 5 with red in force - definitely playable.
        players[0] = players[0].copy(hand = listOf(card(1, CardColor.RED), card(2, CardColor.BLUE)))
        engine.setStateForTest(
            engine.getState().copy(
                players = players,
                currentPlayerIndex = 0,
                currentColor = CardColor.RED
            )
        )

        val before = engine.getState()
        engine.drawCardForPlayer("p0")
        assertEquals(
            "the draw should have been refused outright",
            before.sequenceNumber, engine.getState().sequenceNumber
        )
        assertEquals(2, engine.getState().players[0].cardCount)
    }

    /** ...but a hand with nothing legal can still draw, or the turn could never advance. */
    @Test
    fun `drawing is allowed when nothing in hand is playable`() {
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, turnTimeoutSeconds = null))
        engine.initializeGame(seats(3))

        val players = engine.getState().players.toMutableList()
        // Blue cards while red is in force, and a red top card so no type match either.
        players[0] = players[0].copy(hand = listOf(card(1, CardColor.BLUE), card(2, CardColor.BLUE)))
        engine.setStateForTest(
            engine.getState().copy(
                players = players,
                currentPlayerIndex = 0,
                currentColor = CardColor.RED,
                discardPile = listOf(Card(id = 77, color = CardColor.RED, type = CardType.NINE))
            )
        )

        engine.drawCardForPlayer("p0")
        assertEquals(3, engine.getState().players[0].cardCount)
    }

    /**
     * A live draw stack is the deliberate exception: taking the penalty is a legitimate choice even
     * when a stackable card would also be legal, so the guard must not close that door.
     */
    @Test
    fun `taking a draw penalty is allowed even with a stackable card in hand`() {
        val engine = GameEngine(
            GameSettings(
                gameMode = GameMode.CLASSIC,
                turnTimeoutSeconds = null,
                rules = GameRules(stacking = true)
            )
        )
        engine.initializeGame(seats(3))

        val drawTwo = Card(id = 80, color = CardColor.RED, type = CardType.DRAW_TWO)
        val players = engine.getState().players.toMutableList()
        players[1] = players[1].copy(hand = listOf(Card(id = 81, color = CardColor.BLUE, type = CardType.DRAW_TWO)))
        engine.setStateForTest(
            engine.getState().copy(
                players = players,
                currentPlayerIndex = 1,
                pendingDrawCount = 2,
                discardPile = listOf(drawTwo)
            )
        )

        val before = engine.getState().players[1].cardCount
        engine.drawCardForPlayer("p1")
        assertEquals(
            "should have taken the +2 rather than being forced to stack",
            before + 2, engine.getState().players[1].cardCount
        )
    }

    /** Seven-swap hands over the whole hand, and both sides must re-call UNO afterwards - the flag
     *  describes a hand, not a seat. */
    @Test
    fun `seven swap exchanges hands and clears both uno flags`() {
        val engine = GameEngine(
            GameSettings(
                gameMode = GameMode.CLASSIC,
                turnTimeoutSeconds = null,
                rules = GameRules(sevenSwaps = true)
            )
        )
        engine.initializeGame(seats(3))

        val seven = Card(id = 9004, color = CardColor.RED, type = CardType.SEVEN)
        val players = engine.getState().players.toMutableList()
        players[0] = players[0].copy(hand = players[0].hand + seven, hasCalledUno = true)
        players[1] = players[1].copy(hasCalledUno = true)
        engine.setStateForTest(
            engine.getState().copy(players = players, currentColor = CardColor.RED)
        )

        val chooserHandBefore = engine.getState().players[0].hand.filter { it.id != seven.id }
        val targetHandBefore = engine.getState().players[1].hand

        engine.playCard("p0", seven, null)
        engine.chooseSwapTarget("p0", "p1")

        val after = engine.getState()
        assertEquals(targetHandBefore, after.players[0].hand)
        assertEquals(chooserHandBefore, after.players[1].hand)
        assertTrue("chooser must re-call UNO on a new hand", !after.players[0].hasCalledUno)
        assertTrue("target must re-call UNO on a new hand", !after.players[1].hasCalledUno)
    }
}
