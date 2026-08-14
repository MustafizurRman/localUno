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
import com.mutsho.localuno.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives full rounds of bots against each other with no ViewModel, no coroutines and no network.
 *
 * The thing actually being tested is that the bot can always produce a legal move that MOVES THE
 * ROUND ON. GameViewModel's bot job is scheduled off state changes, so a bot that returns an action
 * the engine treats as a no-op doesn't just misplay - it stalls the table until the turn clock
 * rescues it, and with the clock set to Off it stalls forever. That failure is invisible in a build
 * and obvious here.
 */
class BotBrainTest {

    private fun bots(n: Int) = (0 until n).map { i ->
        Player(id = "bot-$i", name = "Bot$i", isBot = true)
    }

    /** Runs one round to completion the same way the ViewModel's bot job would, step by step. */
    private fun playOutRound(settings: GameSettings, seats: Int = 4): GameState2 {
        val engine = GameEngine(settings)
        engine.initializeGame(bots(seats))

        var steps = 0
        val maxSteps = 4000
        var stalls = 0

        while (steps < maxSteps) {
            val state = engine.getState()
            if (state.phase == GamePhase.ROUND_OVER || state.phase == GamePhase.GAME_OVER) break

            val before = state.sequenceNumber

            // Mirrors scheduleBotAction: the seat about to play its second-to-last card declares
            // first, then acts - but only while PLAYING, since callUno is a no-op in any other
            // phase. UNO is declared AT TWO CARDS now, so this fires before the play rather than
            // settling a debt after it.
            val declarer = if (state.phase == GamePhase.PLAYING) {
                state.currentPlayer?.takeIf { BotBrain.shouldDeclareUno(it) }
            } else null
            if (declarer != null) {
                engine.callUno(declarer.id)
            } else if (state.phase == GamePhase.CHOOSING_SWAP) {
                val chooser = state.pendingSwapPlayerId!!
                val target = BotBrain.chooseSwapTarget(state, chooser)
                if (target == null) engine.autoResolveSwap()
                else engine.chooseSwapTarget(chooser, target)
            } else {
                val botId = state.currentPlayer!!.id
                val playable = engine.getPlayableCards(botId)
                if (playable.isEmpty()) {
                    engine.drawCardForPlayer(botId)
                } else {
                    val card = BotBrain.chooseCard(state, botId, playable)
                    assertTrue(
                        "chose a card outside the legal set",
                        playable.any { it.id == card.id }
                    )
                    val color = if (card.isWildCard()) {
                        BotBrain.chooseColor(
                            state.getPlayerById(botId)!!.hand.filter { it.id != card.id },
                            state.currentColor
                        )
                    } else null
                    assertTrue("declared WILD as a colour", color != CardColor.WILD)
                    engine.playCard(botId, card, color)
                }
            }

            // The whole point: every bot step must change the state. A step that doesn't is the
            // stall the ViewModel cannot recover from on its own.
            if (engine.getState().sequenceNumber == before) stalls++
            steps++
        }

        val finalState = engine.getState()
        assertEquals("bot round stalled - engine state stopped changing", 0, stalls)
        assertTrue("bot round never finished within $maxSteps steps", steps < maxSteps)
        return GameState2(finalState.phase, finalState.winnerId, steps)
    }

    private data class GameState2(val phase: GamePhase, val winnerId: String?, val steps: Int)

    @Test
    fun `classic round of bots finishes with a winner`() {
        repeat(40) {
            val result = playOutRound(GameSettings(gameMode = GameMode.CLASSIC, turnTimeoutSeconds = null))
            assertEquals(GamePhase.ROUND_OVER, result.phase)
            assertTrue("round ended with no winner", result.winnerId != null)
        }
    }

    @Test
    fun `no mercy with every house rule on finishes`() {
        repeat(40) {
            val result = playOutRound(
                GameSettings(
                    gameMode = GameMode.NO_MERCY,
                    turnTimeoutSeconds = null,
                    rules = GameRules(
                        stacking = true,
                        lastCardMustBeNumber = true,
                        zeroRotates = true,
                        sevenSwaps = true
                    )
                )
            )
            assertEquals(GamePhase.ROUND_OVER, result.phase)
        }
    }

    /**
     * The rule that most easily traps a naive bot: at one card left a power card can never be
     * played, so a bot that always leads with its number cards ends up holding an unplayable card
     * and drawing forever. Two seats keeps the round short and the pressure high.
     */
    @Test
    fun `last card must be a number does not trap a bot`() {
        repeat(60) {
            val result = playOutRound(
                GameSettings(
                    gameMode = GameMode.CLASSIC,
                    turnTimeoutSeconds = null,
                    rules = GameRules(lastCardMustBeNumber = true)
                ),
                seats = 2
            )
            assertEquals(GamePhase.ROUND_OVER, result.phase)
        }
    }

    @Test
    fun `seven swap target is always a legal opponent`() {
        val engine = GameEngine(GameSettings(rules = GameRules(sevenSwaps = true)))
        engine.initializeGame(bots(4))
        val state = engine.getState()
        val target = BotBrain.chooseSwapTarget(state, "bot-0")
        assertTrue("swap target must not be the chooser", target != "bot-0")
        assertTrue("swap target must be a seat at the table", state.getPlayerById(target!!) != null)
    }

    /**
     * The rule both setup screens state verbatim: "+2 answers +2, +4 answers +4, reverse +4
     * answers reverse +4 · +6 and +10 can never be stacked".
     *
     * This is what produced a +14 on the table: the old test was `answerValue >= pendingValue`, so
     * a +10 counted as a legal answer to a +4 and the two penalties compounded.
     */
    @Test
    fun `draw stacking matches by card type and never involves +6 or +10`() {
        val d2 = CardType.DRAW_TWO
        val d4 = CardType.DRAW_FOUR
        val rev4 = CardType.WILD_REVERSE_DRAW_FOUR
        val d6 = CardType.WILD_DRAW_SIX
        val d10 = CardType.WILD_DRAW_TEN

        // Like answers like.
        assertTrue(Card.canStack(d2, d2))
        assertTrue(Card.canStack(d4, d4))
        assertTrue(Card.canStack(rev4, rev4))

        // Same draw value is NOT enough - a +4 and a reverse +4 are both worth 4 and still
        // cannot answer each other.
        assertFalse(Card.canStack(rev4, d4))
        assertFalse(Card.canStack(d4, rev4))

        // No escalating across types, in either direction. The +10-onto-+4 case is the bug.
        assertFalse("a +10 must not be playable onto a +4", Card.canStack(d10, d4))
        assertFalse(Card.canStack(d6, d4))
        assertFalse(Card.canStack(d4, d2))
        assertFalse(Card.canStack(d2, d4))

        // +6 and +10 are out entirely - they cannot answer, cannot be answered, not even by
        // their own kind.
        assertFalse(Card.canStack(d6, d6))
        assertFalse(Card.canStack(d10, d10))
        assertFalse(Card.canStack(d2, d10))

        // Non-draw cards never enter into it.
        assertFalse(Card.canStack(CardType.SKIP, d2))
        assertFalse(Card.canStack(d2, CardType.SKIP))
        assertFalse(Card.canStack(CardType.WILD, CardType.WILD))
    }

    /**
     * Walks whole No Mercy rounds and asserts the running penalty is always a total the rules can
     * actually produce. Guards the outcome the unit test above only covers a rule for: any future
     * path that adds to pendingDrawCount without going through canStack shows up here.
     */
    @Test
    fun `pending draw totals stay within what the stacking rule allows`() {
        repeat(60) {
            val engine = GameEngine(
                GameSettings(
                    gameMode = GameMode.NO_MERCY,
                    turnTimeoutSeconds = null,
                    rules = GameRules(stacking = true)
                )
            )
            engine.initializeGame(bots(4))

            var steps = 0
            while (steps < 4000) {
                val state = engine.getState()
                if (state.phase == GamePhase.ROUND_OVER || state.phase == GamePhase.GAME_OVER) break

                val pending = state.pendingDrawCount
                if (pending > 0) {
                    val penalty = state.topCard!!
                    val unit = Card.drawValue(penalty.type)
                    assertTrue(
                        "pending $pending is not a whole number of +$unit cards (top card ${penalty.type})",
                        unit > 0 && pending % unit == 0
                    )
                    // +6 and +10 end the exchange, so they can never have compounded.
                    if (penalty.type == CardType.WILD_DRAW_SIX || penalty.type == CardType.WILD_DRAW_TEN) {
                        assertEquals(
                            "an unstackable ${penalty.type} compounded with something else",
                            unit, pending
                        )
                    }
                }

                val botId = state.currentPlayer!!.id
                val playable = engine.getPlayableCards(botId)
                if (playable.isEmpty()) {
                    engine.drawCardForPlayer(botId)
                } else {
                    val card = BotBrain.chooseCard(state, botId, playable)
                    val color = if (card.isWildCard()) {
                        BotBrain.chooseColor(
                            state.getPlayerById(botId)!!.hand.filter { it.id != card.id },
                            state.currentColor
                        )
                    } else null
                    engine.playCard(botId, card, color)
                }
                steps++
            }
        }
    }

    /** With stacking off, a penalty must be taken - including the reverse +4, which used to open
     *  a stack regardless of the setting. */
    @Test
    fun `stacking off means nothing answers a penalty`() {
        val engine = GameEngine(
            GameSettings(gameMode = GameMode.NO_MERCY, rules = GameRules(stacking = false))
        )
        engine.initializeGame(bots(4))
        var steps = 0
        while (steps < 3000) {
            val state = engine.getState()
            if (state.phase != GamePhase.PLAYING && state.phase != GamePhase.CHOOSING_SWAP) break
            assertEquals(
                "a draw penalty was left pending with stacking switched off",
                0, state.pendingDrawCount
            )
            val botId = state.currentPlayer!!.id
            val playable = engine.getPlayableCards(botId)
            if (playable.isEmpty()) engine.drawCardForPlayer(botId)
            else {
                val card = BotBrain.chooseCard(state, botId, playable)
                val color = if (card.isWildCard()) {
                    BotBrain.chooseColor(
                        state.getPlayerById(botId)!!.hand.filter { it.id != card.id },
                        state.currentColor
                    )
                } else null
                engine.playCard(botId, card, color)
            }
            steps++
        }
    }

    @Test
    fun `wild colour falls back to a real colour when the hand has none`() {
        val chosen = BotBrain.chooseColor(emptyList(), CardColor.WILD)
        assertTrue("must never declare WILD as the active colour", chosen != CardColor.WILD)
    }
}
