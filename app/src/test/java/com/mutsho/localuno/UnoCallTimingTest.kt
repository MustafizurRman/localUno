package com.mutsho.localuno

import com.mutsho.localuno.engine.BotBrain
import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNO is declared at TWO cards - before the card that takes you down to one.
 *
 * The old rule accepted the call at one card, which made it a formality: you played, saw the
 * result, and only then declared, risking nothing. Declaring first is a real decision, and it
 * interacts with the last-card rule in a way worth pinning down - see the two-power-cards case
 * below, which is the exact situation the rule was described from.
 */
class UnoCallTimingTest {

    private val settings = GameSettings(
        gameMode = GameMode.NO_MERCY,
        rules = GameRules(stacking = true, lastCardMustBeNumber = true)
    )

    private fun engineWith(vararg hand: Card): Pair<GameEngine, GameState> {
        val engine = GameEngine(settings)
        val players = listOf(
            Player(id = "me", name = "Me", avatarColor = 0),
            Player(id = "them", name = "Them", avatarColor = 1)
        )
        engine.initializeGame(players)
        val base = engine.getState()
        val seeded = base.copy(
            phase = GamePhase.PLAYING,
            currentPlayerIndex = 0,
            currentColor = CardColor.RED,
            discardPile = listOf(Card(900, CardColor.RED, CardType.THREE)),
            players = listOf(
                base.players[0].copy(hand = hand.toMutableList(), hasCalledUno = false),
                base.players[1].copy(hand = mutableListOf(Card(901, CardColor.BLUE, CardType.FIVE)))
            )
        )
        engine.setStateForTest(seeded)
        return engine to seeded
    }

    private fun handOf(engine: GameEngine, id: String) =
        engine.getState().getPlayerById(id)!!

    @Test
    fun `declaring at two cards is valid`() {
        val (engine, _) = engineWith(
            Card(1, CardColor.RED, CardType.SKIP),
            Card(2, CardColor.RED, CardType.SEVEN)
        )
        engine.callUno("me")
        assertTrue("a call at two cards should stand", handOf(engine, "me").hasCalledUno)
        assertEquals("a valid call must not draw", 2, handOf(engine, "me").hand.size)
    }

    @Test
    fun `declaring at one card is too late and penalised`() {
        // The whole point of the change: by the time you hold one card the window has closed.
        val (engine, _) = engineWith(Card(1, CardColor.RED, CardType.FIVE))
        engine.callUno("me")
        val me = handOf(engine, "me")
        assertFalse("a late call must not protect", me.hasCalledUno)
        assertEquals("a late call draws two", 3, me.hand.size)
    }

    @Test
    fun `declaring early with a full hand is penalised`() {
        val (engine, _) = engineWith(
            Card(1, CardColor.RED, CardType.FIVE),
            Card(2, CardColor.RED, CardType.SIX),
            Card(3, CardColor.RED, CardType.SEVEN)
        )
        engine.callUno("me")
        assertEquals(5, handOf(engine, "me").hand.size)
        assertFalse(handOf(engine, "me").hasCalledUno)
    }

    @Test
    fun `a declaration survives the play it was made for`() {
        // Declare on two, play one, and the protection must still be in force at one card.
        val (engine, _) = engineWith(
            Card(1, CardColor.RED, CardType.SKIP),
            Card(2, CardColor.RED, CardType.SEVEN)
        )
        engine.callUno("me")
        engine.playCard("me", Card(1, CardColor.RED, CardType.SKIP))
        val me = handOf(engine, "me")
        assertEquals(1, me.hand.size)
        assertTrue("the declaration must carry across the play", me.hasCalledUno)

        // ...and an opponent's catch must find nothing.
        engine.penalizeUnoNotCalled("them", "me")
        assertEquals("a declared player cannot be caught", 1, handOf(engine, "me").hand.size)
    }

    @Test
    fun `playing down to one without declaring is catchable`() {
        val (engine, _) = engineWith(
            Card(1, CardColor.RED, CardType.SKIP),
            Card(2, CardColor.RED, CardType.SEVEN)
        )
        engine.playCard("me", Card(1, CardColor.RED, CardType.SKIP))
        assertFalse(handOf(engine, "me").hasCalledUno)

        engine.penalizeUnoNotCalled("them", "me")
        assertEquals("catching an undeclared player draws them two", 3, handOf(engine, "me").hand.size)
    }

    /**
     * The case the rule was described from: holding two power cards, you may still declare, but
     * the last-card rule means you cannot finish on the one you keep - so you are publicly
     * committed to a hand you must draw out of.
     */
    @Test
    fun `two power cards can declare but cannot be finished on`() {
        val skip = Card(1, CardColor.RED, CardType.SKIP)
        val reverse = Card(2, CardColor.RED, CardType.REVERSE)
        val (engine, _) = engineWith(skip, reverse)

        engine.callUno("me")
        assertTrue("declaring on two power cards is legal", handOf(engine, "me").hasCalledUno)

        engine.playCard("me", skip)
        assertEquals(1, handOf(engine, "me").hand.size)

        // The survivor is a power card, and the last-card rule locks it: it cannot be played to win.
        assertFalse(
            "a power card must not be playable as the winning card",
            engine.canPlayCard(reverse, "me")
        )
        assertTrue(
            "with nothing legal to play, the hand has to draw",
            engine.getPlayableCards("me").isEmpty()
        )
    }

    @Test
    fun `a number card left over can be finished on`() {
        // Same shape, but keeping a number - the legal way to close out a hand.
        val skip = Card(1, CardColor.RED, CardType.SKIP)
        val five = Card(2, CardColor.RED, CardType.FIVE)
        val (engine, _) = engineWith(skip, five)

        engine.callUno("me")
        engine.playCard("me", skip)
        assertTrue("a number must remain playable as the winning card", engine.canPlayCard(five, "me"))
    }

    @Test
    fun `bots declare before playing and are sometimes caught out`() {
        // Bots must forget occasionally, or CATCH is dead UI in every solo game.
        var declares = 0
        val total = 200
        repeat(total) { i ->
            val bot = Player(
                id = "bot$i", name = "B", avatarColor = 0, isBot = true,
                hand = mutableListOf(
                    Card(i * 2, CardColor.RED, CardType.FIVE),
                    Card(i * 2 + 1, CardColor.BLUE, CardType.SIX)
                )
            )
            if (BotBrain.shouldDeclareUno(bot)) declares++
        }
        assertTrue("bots should usually declare, saw $declares/$total", declares in 120..190)
        assertTrue("bots must sometimes forget, saw ${total - declares}/$total", total - declares >= 10)
    }

    @Test
    fun `a bot's forgetfulness does not re-roll on every state change`() {
        // Consulted on every state update; a fresh roll each time would converge on always-declare
        // and quietly remove the mechanic.
        val bot = Player(
            id = "bot", name = "B", avatarColor = 0, isBot = true,
            hand = mutableListOf(
                Card(4, CardColor.RED, CardType.FIVE),
                Card(9, CardColor.BLUE, CardType.SIX)
            )
        )
        val first = BotBrain.shouldDeclareUno(bot)
        repeat(50) { assertEquals(first, BotBrain.shouldDeclareUno(bot)) }
    }

    @Test
    fun `humans are catchable too`() {
        // The rule has to bind the player, not just the bots - in solo there is nobody else to hold
        // them to it.
        val human = Player(id = "me", name = "Me", avatarColor = 0, isBot = false,
            hand = mutableListOf(Card(1, CardColor.RED, CardType.FIVE)))
        assertTrue(BotBrain.canBeCaughtForUno(human))

        val declared = human.copy(hasCalledUno = true)
        assertFalse(BotBrain.canBeCaughtForUno(declared))
    }
}
