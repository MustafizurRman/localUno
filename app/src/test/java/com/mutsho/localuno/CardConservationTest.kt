package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random


/**
 * Plays whole games at random and asserts the deck is conserved after every single move.
 *
 * Two invariants, both of which have UI consequences rather than just accounting ones:
 *
 *  - **No card id appears twice anywhere.** The hand renders each card inside `key(card.id)`, and
 *    duplicate keys in one composition corrupt Compose's slot table. That does not fail where the
 *    duplicate is introduced - it fails frames later, deep inside the runtime, with a stack holding
 *    no application code at all. That is exactly the shape of the crash reported from a real game
 *    (`IndexOutOfBoundsException: Index -1 out of bounds for length 0` at `Stack.pop` /
 *    `ComposerImpl.endRoot`), which is why this is checked here rather than trusted.
 *
 *  - **The multiset of cards never changes.** Cards only ever move between hands, the draw pile and
 *    the discard pile; a card that is copied instead of moved would also produce a duplicate id,
 *    and one that is dropped silently shrinks the deck until the pile cannot be rebuilt.
 *
 * Random rather than scripted on purpose: the interesting paths are the ones nobody thinks to
 * write down - a roulette that empties the pile, a mercy knockout mid-draw, a stack resolving onto
 * a reshuffle.
 */
class CardConservationTest {

    private fun players(n: Int) = (0 until n).map {
        Player(id = "p$it", name = "P$it", avatarColor = it)
    }

    private fun check(state: com.mutsho.localuno.model.GameState, expected: Int, where: String) {
        val cards = state.players.flatMap { it.hand } + state.drawPile + state.discardPile
        val ids = cards.map { it.id }
        val dupes = ids.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(
            "$where: duplicate card ids ${dupes.keys.take(5)} - this is what corrupts key(card.id)",
            dupes.isEmpty()
        )
        assertEquals("$where: deck changed size", expected, cards.size)
    }

    private fun fuzz(mode: GameMode, playerCount: Int, seed: Int, rules: GameRules) {
        val rng = Random(seed)
        val settings = GameSettings(gameMode = mode, maxPlayers = playerCount, rules = rules)
        val engine = GameEngine(settings, Random(seed))
        var state = engine.initializeGame(players(playerCount))

        val total = (state.players.flatMap { it.hand } + state.drawPile + state.discardPile).size
        check(state, total, "$mode/$playerCount/$seed after deal")

        repeat(400) { move ->
            if (state.phase == GamePhase.GAME_OVER || state.phase == GamePhase.ROUND_OVER) return@repeat
            val current = state.players.getOrNull(state.currentPlayerIndex) ?: return@repeat

            state = when {
                state.phase == GamePhase.CHOOSING_SWAP -> {
                    val target = state.players.firstOrNull {
                        it.id != current.id && it.isConnected
                    } ?: return@repeat
                    engine.chooseSwapTarget(current.id, target.id)
                }

                else -> {
                    val playable = engine.getPlayableCards(current.id)
                    if (playable.isNotEmpty() && rng.nextInt(10) > 1) {
                        val card = playable[rng.nextInt(playable.size)]
                        val colour = if (card.color == CardColor.WILD) {
                            listOf(CardColor.RED, CardColor.YELLOW, CardColor.GREEN, CardColor.BLUE)
                                .let { it[rng.nextInt(it.size)] }
                        } else null
                        engine.playCard(current.id, card, colour)
                    } else {
                        engine.drawCardForPlayer(current.id)
                    }
                }
            }

            check(state, total, "$mode/$playerCount/$seed move $move")
        }
    }

    @Test
    fun `no mercy conserves the deck across random games`() {
        for (seed in 1..40) {
            for (n in 2..6) fuzz(GameMode.NO_MERCY, n, seed * 100 + n, HOUSE_RULES_ON)
        }
    }

    @Test
    fun `classic conserves the deck across random games`() {
        for (seed in 1..40) {
            for (n in 2..6) fuzz(GameMode.CLASSIC, n, seed * 100 + n, HOUSE_RULES_ON)
        }
    }
}
