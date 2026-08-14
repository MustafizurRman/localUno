package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What a real table actually plays. GameRules defaults every switch to false, so a fuzz using the
 * defaults never reaches a stacked penalty, a 7's swap or a 0's rotation - and those last two move
 * WHOLE HANDS between players, which is the code most able to duplicate a card.
 */
internal val HOUSE_RULES_ON = GameRules(
    stacking = true,
    lastCardMustBeNumber = true,
    zeroRotates = true,
    sevenSwaps = true
)

/**
 * The same conservation fuzz as [CardConservationTest], but driving the transitions that only ever
 * happen when other people are involved: drops, reconnects, UNO catches, and the turn clock
 * resolving a swap nobody answered.
 *
 * These are the paths a solo test session cannot reach, which is exactly why bugs survive in them -
 * they are first exercised at a table, by four people, at once, and the failure arrives as "it
 * crashed" with no reproduction. The card-duplication bug that took down a real game
 * (WILD_REVERSE_DRAW_FOUR returning a pre-draw snapshot) was in this family: a state snapshot going
 * stale across a side effect. Every one of the methods below both snapshots and mutates, so each
 * gets the same invariant held over it after every single call:
 *
 *  - no card id exists twice (duplicate ids become duplicate `key(card.id)` in the hand, which
 *    corrupts Compose's slot table and crashes a later frame from inside the runtime), and
 *  - the deck is a conserved multiset - cards move between hands and piles, never appear or vanish.
 *
 * Also asserts the turn index stays in range, since a roster that shrinks under a knockout while an
 * index points past its end is the other classic multiplayer crash.
 */
class MultiplayerChaosTest {

    /**
     * What the fuzz actually reached. Asserted, not printed: a chaos test that spends its whole run
     * paused proves only that a paused game is stable. The first cut of this did exactly that -
     * disruptions were frequent enough that the table sat in PAUSED_DISCONNECT more often than it
     * played, and CHOOSING_SWAP and a live stacked penalty were never once reached.
     */
    private val seen = HashMap<String, Int>()
    private fun saw(k: String) { seen.merge(k, 1, Int::plus) }

    private fun players(n: Int) = (0 until n).map {
        Player(id = "p$it", name = "P$it", avatarColor = it)
    }

    private fun check(state: GameState, expected: Int, where: String) {
        val cards = state.players.flatMap { it.hand } + state.drawPile + state.discardPile
        val dupes = cards.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("$where: duplicate card ids ${dupes.keys.take(5)}", dupes.isEmpty())
        assertEquals("$where: deck changed size", expected, cards.size)
        assertTrue(
            "$where: currentPlayerIndex ${state.currentPlayerIndex} outside 0..${state.players.size - 1}",
            state.currentPlayerIndex in state.players.indices
        )
    }

    private fun chaos(mode: GameMode, playerCount: Int, seed: Int, rules: GameRules) {
        val rng = Random(seed)
        val engine = GameEngine(GameSettings(gameMode = mode, maxPlayers = playerCount, rules = rules), Random(seed))
        var state = engine.initializeGame(players(playerCount))
        val total = (state.players.flatMap { it.hand } + state.drawPile + state.discardPile).size
        check(state, total, "$mode/$playerCount/$seed deal")

        repeat(500) { move ->
            if (state.phase == GamePhase.GAME_OVER || state.phase == GamePhase.ROUND_OVER) return@repeat
            val where = "$mode/$playerCount/$seed move $move"
            val anyone = state.players[rng.nextInt(state.players.size)]
            val current = state.players.getOrNull(state.currentPlayerIndex)

            // Disruption is deliberately RARE. Each of these either pauses the table or removes a
            // player, so at any meaningful rate the game stops being a game and the fuzz stops
            // reaching the states worth testing - a stacked penalty, a swap in flight, a hand
            // approaching the mercy threshold.
            state = when (rng.nextInt(1000)) {
                in 0..14 -> engine.beginDisconnectCountdown(anyone.id)
                in 15..29 -> engine.tickDisconnectCountdown()
                in 30..49 -> engine.reconnectPlayer(anyone.id)
                in 50..53 -> engine.playerLeft(anyone.id)

                // Someone jabs UNO, or catches someone who didn't.
                in 54..73 -> engine.callUno(anyone.id)
                in 74..93 -> engine.penalizeUnoNotCalled(
                    anyone.id,
                    state.players[rng.nextInt(state.players.size)].id
                )

                // The 7's swap picker timing out instead of the player answering it.
                in 94..99 -> engine.autoResolveSwap()

                else -> when {
                    current == null -> return@repeat
                    state.phase == GamePhase.CHOOSING_SWAP -> {
                        val target = state.players.firstOrNull {
                            it.id != current.id && it.isConnected
                        } ?: return@repeat
                        engine.chooseSwapTarget(current.id, target.id)
                    }
                    state.phase != GamePhase.PLAYING -> engine.tickDisconnectCountdown()
                    else -> {
                        val playable = engine.getPlayableCards(current.id)
                        if (playable.isNotEmpty() && rng.nextInt(10) > 1) {
                            val card: Card = playable[rng.nextInt(playable.size)]
                            val colour = if (card.color == CardColor.WILD) {
                                listOf(
                                    CardColor.RED, CardColor.YELLOW,
                                    CardColor.GREEN, CardColor.BLUE
                                )[rng.nextInt(4)]
                            } else null
                            engine.playCard(current.id, card, colour)
                        } else {
                            engine.drawCardForPlayer(current.id)
                        }
                    }
                }
            }

            saw("phase:" + state.phase)
            if (state.pendingDrawCount > 0) saw("pendingDraw")
            if (state.players.any { !it.isConnected }) saw("someoneOut")
            if (state.disconnectedPlayerId != null) saw("dropPending")
            check(state, total, where)
        }
    }

    /** Fails if the fuzz never reached a state, rather than quietly passing without testing it. */
    private fun requireReached(vararg keys: String) {
        for (k in keys) {
            val n = seen[k] ?: 0
            assertTrue("fuzz never reached $k - the run proves nothing about it (saw $seen)", n >= 20)
        }
    }

    @Test
    fun `no mercy survives drops reconnects and catches`() {
        for (seed in 1..30) for (n in 2..6) chaos(GameMode.NO_MERCY, n, seed * 1000 + n, HOUSE_RULES_ON)
        requireReached(
            "phase:PLAYING", "phase:PAUSED_DISCONNECT", "phase:CHOOSING_SWAP",
            "pendingDraw", "someoneOut", "dropPending"
        )
    }

    @Test
    fun `classic survives drops reconnects and catches`() {
        for (seed in 1..30) for (n in 2..6) chaos(GameMode.CLASSIC, n, seed * 1000 + n, HOUSE_RULES_ON)
        requireReached(
            "phase:PLAYING", "phase:PAUSED_DISCONNECT", "phase:CHOOSING_SWAP",
            "pendingDraw", "someoneOut", "dropPending"
        )
    }
}
